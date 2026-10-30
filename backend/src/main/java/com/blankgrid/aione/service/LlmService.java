package com.blankgrid.aione.service;

import com.blankgrid.aione.service.SettingsService.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议调用(SSE 流式)。兼容 GLM / OpenAI / DeepSeek 等。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    public static class ChatMessage {
        public final String role;
        public final String content;
        /** assistant 决定调用工具时携带;普通消息为 null */
        public final List<ToolCall> toolCalls;
        /** role=tool 时,表示这是哪一次调用的结果 */
        public final String toolCallId;
        /** role=tool 时带上工具名,部分服务商要求 */
        public final String name;

        public ChatMessage(String role, String content) {
            this(role, content, null, null, null);
        }

        public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {
            this.role = role;
            this.content = content == null ? "" : content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
            this.name = name;
        }

        /** 回填一次工具执行结果 */
        public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
            return new ChatMessage("tool", content, null, toolCallId, toolName);
        }
    }

    /** 模型要求调用的一次工具。id 由服务商生成,回填结果时必须原样带回。 */
    public record ToolCall(String id, String name, String arguments) {}

    /** 提供给模型的工具声明。 */
    public record ToolSpec(String name, String description, String parameters) {}

    /**
     * 一轮调用的结果。可能是"说完了"(content),也可能是"我要用工具"(toolCalls),
     * 两者可以同时出现(模型先说一句话再调工具)。
     */
    public record Turn(String content, List<ToolCall> toolCalls, String finishReason) {
        public boolean wantsTools() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    /** send 的可选参数。收进一个对象,免得参数列表长到看不清哪个是哪个。 */
    private static final class Opts {
        boolean jsonMode;
        int maxTokens;
        boolean usageOpts = true;
        boolean thinkOff;
        List<ToolSpec> tools;

        Opts copy() {
            Opts o = new Opts();
            o.jsonMode = jsonMode; o.maxTokens = maxTokens;
            o.usageOpts = usageOpts; o.thinkOff = thinkOff; o.tools = tools;
            return o;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsService settings;
    private final Path diagnoseDir;
    /** 最近一次流式回复的结束原因:length 表示被输出上限截断 */
    private final ThreadLocal<String> lastFinishReason = ThreadLocal.withInitial(() -> "");
    /** 最近一次调用实际发给服务商的模型名(可能被省钱护栏降级过) */
    private final ThreadLocal<String> lastModel = ThreadLocal.withInitial(() -> "");
    /** 最近一次调用调用方原本想用的模型名:和 lastModel 不同就说明降级了 */
    private final ThreadLocal<String> wantedModel = ThreadLocal.withInitial(() -> "");
    /** 服务商回传的真实用量(有些服务商不返回,则全是 0) */
    private final ThreadLocal<int[]> lastUsage = ThreadLocal.withInitial(() -> new int[2]);
    /** 模型只吐了思考过程、正文为空时,把思考内容留一份,便于判断是截断还是路由问题 */
    private final ThreadLocal<String> lastReasoning = ThreadLocal.withInitial(() -> "");
    /** 供界面展示的"最近一次调用"快照:线程无关,别的接口也能读到 */
    private volatile Map<String, Object> lastCall = Map.of();

    /**
     * 服务商侧故障:重试用尽,或者熔断已经打开。
     *
     * <p>单独一个类型,是为了让上层能回答一个必须回答的问题 ——
     * "这次没交出东西,是这个岗位不行,还是上游坏了?"
     * 靠错误文字里有没有"503"去猜,迟早猜错;一次猜错就是给一个称职的岗位记上一笔失败。
     */
    public static class UnavailableException extends IllegalStateException {
        private final int attempts;

        public UnavailableException(String message, int attempts) {
            super(message);
            this.attempts = attempts;
        }

        /** 这次一共试了几回(熔断直接拒掉时为 0) */
        public int attempts() { return attempts; }
    }

    /** 值得重试的状态码:限流、上游过载、网关错误。4xx 里的参数/鉴权错误重试一万次也一样。 */
    private static final java.util.Set<Integer> RETRY_STATUS = java.util.Set.of(408, 429, 500, 502, 503, 504);
    /** 一次调用最多试几次(含首次)。再多就是拿钱和时间赌上游自愈。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 重试总预算:退避加起来超过这个数就放弃,别让一个任务卡十分钟。 */
    private static final long RETRY_BUDGET_MS = 45_000;
    /** 连续几次「重试用尽」就判定上游整体不可用 */
    private static final int BREAKER_THRESHOLD = 4;
    /** 熔断初始冷却;反复失败时翻倍,最长 2 分钟 */
    private static final long BREAKER_BASE_MS = 20_000;
    private static final long BREAKER_MAX_MS = 120_000;

    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 熔断打开到什么时候(毫秒时间戳) */
    private volatile long breakerUntil = 0;
    private volatile String breakerReason = "";
    private volatile int breakerTrips = 0;
    /** 最近一次调用试了几回:1 = 一次就成,>1 = 中间有过重试 */
    private final ThreadLocal<Integer> lastAttempts = ThreadLocal.withInitial(() -> 1);

    /** 上游现在是整体不可用(熔断打开)吗?给状态页/诊断用。 */
    public boolean breakerOpen() {
        return System.currentTimeMillis() < breakerUntil;
    }

    /** 熔断现状快照:冷却还剩几秒、上次撞上什么、今天开了几次。 */
    public Map<String, Object> breakerInfo() {
        long left = Math.max(0, breakerUntil - System.currentTimeMillis());
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("open", left > 0);
        m.put("remainSeconds", left / 1000);
        m.put("reason", breakerReason);
        m.put("trips", breakerTrips);
        return m;
    }

    public LlmService(SettingsService settings,
                      @Value("${aione.data-dir:./data}") String dataDir) {
        this.settings = settings;
        this.diagnoseDir = Path.of(dataDir, "diagnose");
    }

    /**
     * 流式对话。每个增量片段回调 onDelta,返回完整回复。走主用服务商。
     */
    public String chatStream(List<ChatMessage> messages, String model, Consumer<String> onDelta) {
        return chatStream(messages, model, onDelta, null, false, 0);
    }

    /**
     * 流式对话,可指定服务商 —— CEO 决策走独立槽位,岗位执行仍走主用,两者互不干扰。
     */
    public String chatStream(List<ChatMessage> messages, String model, Consumer<String> onDelta, Provider forced) {
        return chatStream(messages, model, onDelta, forced, false, 0);
    }

    /**
     * @param jsonMode 要不要服务端强制 JSON 输出(OpenAI 兼容的 response_format)。
     *                 拆解/评审这类"必须吐结构化数据"的调用开启它,模型跑偏的概率会低一个量级。
     * @param maxTokens 输出上限;<=0 表示不传,交给服务商默认值。
     *                  注意默认值往往只有 4K:中文长清单写到一半被截断,表现就是"不是有效 JSON"。
     */
    public String chatStream(List<ChatMessage> messages, String model, Consumer<String> onDelta, Provider forced,
                            boolean jsonMode, int maxTokens) {
        return chatWithTools(messages, model, onDelta, forced, jsonMode, maxTokens, null).content();
    }

    /**
     * 决策链路专用:允许跳过省钱护栏。
     *
     * <p>拍板是全公司唯一的大脑。CEO 槽位配了推理模型却被护栏悄悄换成便宜档,
     * 决策质量塌下来是看不出来的 —— 它照样输出一份像模像样的 JSON,
     * 只是判断变差了。这种"省了钱、坏了事"的降级必须由调用方显式豁免。
     */
    public String chatStreamStrong(List<ChatMessage> messages, String model, Consumer<String> onDelta,
                                   Provider forced, boolean jsonMode, int maxTokens) {
        return chatWithTools(messages, model, onDelta, forced, jsonMode, maxTokens, null, true).content();
    }

    /**
     * 带工具的流式对话。
     *
     * <p>返回 Turn 而不是 String,是因为"说完了"和"要去调工具"是两种不同的结束方式。
     * 调用方必须能区分 —— 否则就只能把 tool_calls 当成"模型没返回内容"报错,
     * 这正是不接工具时那个错误信息的来源。
     */
    public Turn chatWithTools(List<ChatMessage> messages, String model, Consumer<String> onDelta,
                              Provider forced, boolean jsonMode, int maxTokens, List<ToolSpec> tools) {
        return chatWithTools(messages, model, onDelta, forced, jsonMode, maxTokens, tools, false);
    }

    /**
     * @param skipCheap 跳过省钱护栏。只有决策链路该传 true,其余一律 false ——
     *                  护栏存在的意义就是拦住各处写死的贵模型,豁免口子开得越少越好。
     */
    public Turn chatWithTools(List<ChatMessage> messages, String model, Consumer<String> onDelta,
                              Provider forced, boolean jsonMode, int maxTokens, List<ToolSpec> tools,
                              boolean skipCheap) {
        Provider p = forced != null ? forced : settings.primaryProvider();
        if (p == null || p.baseUrl == null || p.baseUrl.isBlank()) {
            throw new IllegalStateException("尚未配置大模型 API,请在「设置」中填写服务商信息");
        }
        String want = (model != null && !model.isBlank()) ? model : p.model;
        // 省钱护栏:不管这个模型名是从员工档案、任务记录还是决策槽位来的,
        // 只要它属于贵的那一档,就在这里换成省钱模型。唯一出口,漏不掉。
        // 决策链路可以显式豁免(见 chatStreamStrong)。
        String useModel = skipCheap && want != null && !want.isBlank()
                ? want.trim() : settings.resolveModel(want);
        if (useModel == null || useModel.isBlank()) {
            throw new IllegalStateException("尚未配置默认模型,请在「设置」中填写");
        }
        wantedModel.set(want == null ? "" : want);
        lastModel.set(useModel);
        if (!ModelGuard.norm(want).isEmpty() && ModelGuard.downgraded(want, useModel)) {
            log.info("省钱模式:模型 {} → {}", want, useModel);
        }
        Opts o = new Opts();
        o.jsonMode = jsonMode;
        o.maxTokens = maxTokens;
        o.thinkOff = settings.thinkingOff();
        o.tools = (tools == null || tools.isEmpty()) ? null : tools;
        // 要工具就别同时强制 JSON:两者都在要求"输出结构",同时开容易互相打架。
        // 带工具的调用本来就靠 tool_calls 传结构,不需要 response_format。
        if (o.tools != null) o.jsonMode = false;
        return send(p, messages, useModel, onDelta, o, 2);
    }

    /**
     * 内部信号:这次失败值得再试一遍。不往外抛 —— 外面只该看到两种结论,
     * 成功了,或者服务商确实不行了。
     */
    private static final class RetryableFailure extends Exception {
        final long retryAfterMs;

        RetryableFailure(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }

    /**
     * 带重试的外壳。
     *
     * <p>上游的 503「too busy」和 connection reset 都是几分钟内会自己好的东西。
     * 以前它们一路抛到任务层,表现是"这个岗位这次没交出东西" —— 一份写到一半的
     * 正文就这么被当成品送进评审,拿个低分,再花一整轮返工。
     *
     * <p>重试有三条硬约束,不是"多试几次"那么简单:
     * <ol>
     *   <li>只对 408/429/5xx 和连接类故障重试。参数写错、密钥失效重试一万次也一样,
     *       白等三轮还会把真正的错误埋掉。</li>
     *   <li>已经往外吐过字就不重试。流式输出是单向的:前面那几百字已经渲染在界面上、
     *       甚至写进了正文,重来一遍就是从第二个开头接上,成品里会出现两遍开头。
     *       宁可这次失败,也不能交一份"重复了两遍"的稿子。</li>
     *   <li>连续失败到阈值就整体熔断。二十个岗位各试三次,等于朝一个已经趴下的
     *       服务商同时拍六十个请求 —— 重试反而让它更起不来。</li>
     * </ol>
     */
    private Turn send(Provider p, List<ChatMessage> messages, String useModel, Consumer<String> onDelta,
                      Opts opts, int downgrades) {
        long waitBreak = breakerUntil - System.currentTimeMillis();
        if (waitBreak > 0) {
            throw new UnavailableException("服务商连续失败,暂停请求 " + (waitBreak / 1000 + 1)
                    + " 秒后再试(上次原因:" + breakerReason + ")", 0);
        }
        long deadline = System.currentTimeMillis() + RETRY_BUDGET_MS;
        // 一旦吐过字就不能再重来,所以外层得知道这件事
        final boolean[] spoke = {false};
        Consumer<String> once = onDelta == null ? null : piece -> {
            spoke[0] = true;
            onDelta.accept(piece);
        };
        for (int attempt = 1; ; attempt++) {
            try {
                Turn turn = attempt(p, messages, useModel, once, opts, downgrades);
                lastAttempts.set(attempt);
                consecutiveFailures.set(0);
                return turn;
            } catch (RetryableFailure f) {
                if (spoke[0]) {
                    // 已经说出去了,重试会产生重复正文 —— 这次就认栽,但不能算岗位的错
                    throw new UnavailableException("已输出部分内容后连接中断(" + f.getMessage()
                            + "),为避免正文重复不再重试", attempt);
                }
                if (attempt >= MAX_ATTEMPTS || System.currentTimeMillis() >= deadline) {
                    tripBreaker(f.getMessage());
                    throw new UnavailableException(f.getMessage() + "(已重试 " + attempt + " 次)", attempt);
                }
                long wait = f.retryAfterMs > 0 ? f.retryAfterMs : backoffMs(attempt);
                if (System.currentTimeMillis() + wait > deadline) {
                    tripBreaker(f.getMessage());
                    throw new UnavailableException(f.getMessage() + "(已重试 " + attempt + " 次,重试预算用尽)", attempt);
                }
                log.warn("模型调用失败,{} 毫秒后第 {} 次重试:{}", wait, attempt, f.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("重试等待被中断");
                }
            }
        }
    }

    /** 退避基数。测试里调到几毫秒,否则一次重试用例要真等四秒。 */
    static volatile long backoffBaseMs = 1000L;

    /** 1 倍 / 3 倍基数递增,带 ±30% 抖动 —— 二十个线程同时被拒,不加抖动就会在同一毫秒再一起撞上去。 */
    static long backoffMs(int attempt) {
        long base = backoffBaseMs * (attempt <= 1 ? 1 : 3);
        double jitter = 0.7 + Math.random() * 0.6;
        return (long) (base * jitter);
    }

    private void tripBreaker(String reason) {
        int fails = consecutiveFailures.incrementAndGet();
        if (fails < BREAKER_THRESHOLD) return;
        long cooldown = Math.min(BREAKER_MAX_MS,
                BREAKER_BASE_MS * (1L << Math.min(3, breakerTrips)));
        breakerUntil = System.currentTimeMillis() + cooldown;
        breakerReason = truncate(reason, 160);
        breakerTrips++;
        consecutiveFailures.set(0);
        log.warn("模型服务商连续 {} 次重试用尽,熔断 {} 秒:{}", BREAKER_THRESHOLD, cooldown / 1000, breakerReason);
    }

    /**
     * @param downgrades 还能降级几次。response_format / max_tokens / stream_options / thinking 都是
     *                   OpenAI 协议的可选扩展,自建网关或老版本服务商可能直接 400。
     *                   撞上了就把这个参数去掉重发,而不是让整条生产线因为"多传了一个字段"停掉。
     */
    private Turn attempt(Provider p, List<ChatMessage> messages, String useModel, Consumer<String> onDelta,
                         Opts opts, int downgrades) throws RetryableFailure {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);
        body.put("stream", true);
        if (opts.jsonMode) body.putObject("response_format").put("type", "json_object");
        if (opts.maxTokens > 0) body.put("max_tokens", opts.maxTokens);
        // DeepSeek 的思考模式默认开着且默认 high:模型先写一大段推理再落笔,这段推理
        // 按输出 token 计价,长任务里能占掉一半以上,还常常把输出上限吃光导致正文为空。
        // 写文案、写方案这类活不需要额外推理,默认关掉,账单立刻掉一个量级。
        if (opts.thinkOff) body.putObject("thinking").put("type", "disabled");
        // 流式默认不回 usage,得主动要。有了真实用量,成本表才是账而不是估算。
        if (opts.usageOpts) body.putObject("stream_options").put("include_usage", true);
        if (opts.tools != null) {
            ArrayNode arr = body.putArray("tools");
            for (ToolSpec t : opts.tools) {
                ObjectNode n = arr.addObject();
                n.put("type", "function");
                ObjectNode fn = n.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                try {
                    fn.set("parameters", mapper.readTree(t.parameters()));
                } catch (IOException e) {
                    // schema 写坏了退化成"无参数",别让整次请求 400 全废
                    fn.putObject("parameters").put("type", "object");
                }
            }
        }
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role);
            // 带 tool_calls 的 assistant 消息 content 允许为空,但字段必须在,
            // 缺字段有些网关会直接判协议错误。
            n.put("content", m.content == null ? "" : m.content);
            if (m.toolCallId != null && !m.toolCallId.isBlank()) n.put("tool_call_id", m.toolCallId);
            if (m.name != null && !m.name.isBlank()) n.put("name", m.name);
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                ArrayNode tcs = n.putArray("tool_calls");
                for (ToolCall tc : m.toolCalls) {
                    ObjectNode o = tcs.addObject();
                    o.put("id", tc.id());
                    o.put("type", "function");
                    ObjectNode f = o.putObject("function");
                    f.put("name", tc.name());
                    f.put("arguments", tc.arguments() == null || tc.arguments().isBlank() ? "{}" : tc.arguments());
                }
            }
        }

        String url = p.baseUrl.replaceAll("/+$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (p.apiKey == null ? "" : p.apiKey))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        lastFinishReason.set("");
        lastUsage.set(new int[2]);
        lastReasoning.set("");
        StringBuilder full = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        // 工具调用在流式里是分片的:第一帧带 id 和 function.name,之后每帧只带 index
        // 和 arguments 的另一截。必须按 index 归并,否则拼出来永远是残缺的 JSON。
        java.util.Map<Integer, String[]> callAcc = new java.util.TreeMap<>();
        // 有的服务商流式请求先用 200 开个头,再把错误塞进 data 里(余额不足、限流、模型下线)。
        // 不认这个的话,表现就是"模型未返回内容",查起来完全找不着方向。
        final String[] upstreamError = new String[1];
        try {
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                String errBody = resp.body().collect(java.util.stream.Collectors.joining("\n"));
                if (downgrades > 0 && resp.statusCode() == 400) {
                    String low = errBody.toLowerCase(java.util.Locale.ROOT);
                    boolean badJson = opts.jsonMode && (low.contains("response_format") || low.contains("json_object")
                            || low.contains("json mode"));
                    boolean badMax = opts.maxTokens > 0 && low.contains("max_tokens");
                    boolean badUsage = opts.usageOpts && (low.contains("stream_options") || low.contains("include_usage"));
                    boolean badThink = opts.thinkOff && (low.contains("thinking") || low.contains("reasoning"));
                    boolean badTools = opts.tools != null && (low.contains("tools") || low.contains("function calling")
                            || low.contains("tool_choice") || low.contains("不支持工具"));
                    if (badJson || badMax || badUsage || badThink || badTools) {
                        // 只去掉被顶回来的那个,别把其他的也一起丢了
                        Opts next = opts.copy();
                        if (badJson) next.jsonMode = false;
                        if (badMax) next.maxTokens = 0;
                        if (badUsage) next.usageOpts = false;
                        if (badThink) next.thinkOff = false;
                        if (badTools) next.tools = null;
                        log.info("服务商不支持某个可选字段,去掉后重发:{}", low.length() > 120 ? low.substring(0, 120) : low);
                        return attempt(p, messages, useModel, onDelta, next, downgrades - 1);
                    }
                }
                if (RETRY_STATUS.contains(resp.statusCode())) {
                    throw new RetryableFailure("API 返回 " + resp.statusCode() + ": " + truncate(errBody, 300),
                            retryAfterMs(resp));
                }
                throw new IllegalStateException("API 返回 " + resp.statusCode() + ": " + truncate(errBody, 500));
            }
            resp.body().forEach(line -> {
                if (!line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonNode root = mapper.readTree(data);
                    JsonNode errNode = root.path("error");
                    if (!errNode.isMissingNode() && !errNode.isNull()) {
                        upstreamError[0] = errNode.path("message").asText(errNode.toString());
                        return;
                    }
                    // usage 单独一帧(choices 为空),放在前面先认掉
                    JsonNode usage = root.path("usage");
                    if (usage.isObject()) {
                        int pin = usage.path("prompt_tokens").asInt(0);
                        int pout = usage.path("completion_tokens").asInt(0);
                        if (pin > 0 || pout > 0) lastUsage.set(new int[]{pin, pout});
                    }
                    JsonNode choice = root.path("choices").path(0);
                    JsonNode finish = choice.path("finish_reason");
                    if (!finish.isMissingNode() && !finish.isNull()) lastFinishReason.set(finish.asText(""));
                    JsonNode delta = choice.path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String piece = delta.asText();
                        full.append(piece);
                        if (onDelta != null) onDelta.accept(piece);
                    }
                    // 推理模型先吐 reasoning_content。只认 content 的话,
                    // 表现就是"跑了两分钟、烧了钱、正文是空的",看起来像模型坏了。
                    JsonNode think = choice.path("delta").path("reasoning_content");
                    if (!think.isMissingNode() && !think.isNull()) reasoning.append(think.asText());
                    // 工具调用分片归并
                    JsonNode tcs = choice.path("delta").path("tool_calls");
                    if (tcs.isArray()) {
                        for (JsonNode tc : tcs) {
                            int idx = tc.path("index").asInt(callAcc.size());
                            String[] slot = callAcc.computeIfAbsent(idx, k -> new String[]{"", "", ""});
                            String id = tc.path("id").asText("");
                            if (!id.isEmpty()) slot[0] = id;
                            String fnName = tc.path("function").path("name").asText("");
                            if (!fnName.isEmpty()) slot[1] = fnName;
                            JsonNode a = tc.path("function").path("arguments");
                            // 规范里 arguments 是字符串片段;有的服务商直接给对象,一并兜住
                            if (a.isTextual()) slot[2] = slot[2] + a.asText();
                            else if (!a.isMissingNode() && !a.isNull()) slot[2] = slot[2] + a.toString();
                        }
                    }
                } catch (IOException ignored) {
                    // 跳过无法解析的行
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断");
        } catch (java.io.UncheckedIOException e) {
            // 读到一半被掐。HttpClient 把流里的 IOException 包成非受检异常,
            // 不单独接住的话它会一路冒到任务层,变成一句
            // "java.io.UncheckedIOException: fixed content-length..." 记在岗位头上。
            throw new RetryableFailure("读取响应流中断: "
                    + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()), 0);
        } catch (IOException e) {
            // connection reset、读超时:上游把我掐了。这类重试有意义
            throw new RetryableFailure("连接大模型 API 失败: " + e.getMessage(), 0);
        }
        lastReasoning.set(truncate(reasoning.toString(), 4000));

        List<ToolCall> calls = new java.util.ArrayList<>();
        for (Map.Entry<Integer, String[]> e : callAcc.entrySet()) {
            String[] v = e.getValue();
            if (v[1] == null || v[1].isBlank()) continue;
            String id = (v[0] == null || v[0].isBlank()) ? "call_" + e.getKey() + "_" + System.nanoTime() : v[0];
            calls.add(new ToolCall(id, v[1], v[2] == null || v[2].isBlank() ? "{}" : v[2]));
        }

        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("model", useModel);
        snapshot.put("wanted", wantedModel.get());
        snapshot.put("downgraded", ModelGuard.downgraded(wantedModel.get(), useModel));
        snapshot.put("promptTokens", lastUsage.get()[0]);
        snapshot.put("completionTokens", lastUsage.get()[1]);
        snapshot.put("thinkingChars", reasoning.length());
        snapshot.put("finishReason", lastFinishReason.get());
        snapshot.put("toolCalls", calls.size());
        snapshot.put("attempts", lastAttempts.get());
        lastCall = snapshot;

        // 只调工具、不写字是合法的:这时候正文为空不代表出错
        if (full.isEmpty() && calls.isEmpty()) {
            if (upstreamError[0] != null) {
                // 有的网关先回 200 再把错误塞进流里。这种连一个字都没吐出来的,
                // 重试是安全的 —— 但如果错的是"模型不存在",重试只是白等
                if (looksTransient(upstreamError[0])) {
                    throw new RetryableFailure("API 错误: " + truncate(upstreamError[0], 300), 0);
                }
                throw new IllegalStateException("API 错误: " + truncate(upstreamError[0], 300));
            }
            if (reasoning.length() > 0) {
                dump("empty-content", "model=" + useModel + " finish=" + lastFinishReason.get()
                        + " usage=" + lastUsage.get()[0] + "/" + lastUsage.get()[1]
                        + "\n--- reasoning(仅思考,正文空) ---\n" + truncate(reasoning.toString(), 2000));
                throw new IllegalStateException("模型只输出了思考过程,正文为空"
                        + ("length".equals(lastFinishReason.get()) ? "(被输出上限截断)" : "")
                        + ",建议提高输出上限或换非推理模型");
            }
            dump("empty-reply", "model=" + useModel + " finish=" + lastFinishReason.get()
                    + " usage=" + lastUsage.get()[0] + "/" + lastUsage.get()[1] + " bytes=0");
            throw new IllegalStateException("模型未返回内容");
        }
        return new Turn(full.toString(), calls, lastFinishReason.get());
    }

    /**
     * 服务商给的 Retry-After:可能是秒数,也可能是 HTTP 日期。限流时它比自己的退避更准 ——
     * 人家说了 12 秒后再来,你 1 秒就撞上去只会把冷却重新计时。
     */
    private static long retryAfterMs(HttpResponse<?> resp) {
        String v = resp.headers().firstValue("Retry-After").orElse("").trim();
        if (v.isEmpty()) return 0;
        try {
            return Math.max(0, Math.min(60_000, Long.parseLong(v) * 1000));
        } catch (NumberFormatException ignored) {
            // 不是秒数就按 HTTP 日期解;解不出来就算了
            try {
                var when = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0, Math.min(60_000, when.toInstant().toEpochMilli() - System.currentTimeMillis()));
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** 上游的错原句看起来是"暂时性"的吗?只认明确的过载/限流/超时措辞,别把"模型不存在"也放进来重试。 */
    static boolean looksTransient(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("too busy") || m.contains("overload") || m.contains("rate limit")
                || m.contains("rate_limit") || m.contains("timeout") || m.contains("timed out")
                || m.contains("temporarily") || m.contains("503") || m.contains("502")
                || m.contains("connection reset") || m.contains("服务繁忙") || m.contains("请稍后");
    }

    /** 把异常现场落到 data/diagnose/,下次不用靠猜。 */
    private void dump(String kind, String content) {
        try {
            Files.createDirectories(diagnoseDir);
            Files.writeString(diagnoseDir.resolve(kind + "-" + System.currentTimeMillis() + ".txt"), content);
        } catch (IOException ignored) {
            // 诊断落盘失败不能影响主流程
        }
    }

    /** 上一次调用是不是被输出上限截断的(finish_reason=length)。 */
    public boolean lastTruncated() {
        return "length".equals(lastFinishReason.get());
    }

    /** 最近一次调用的快照:模型、真实用量、思考字数、是否被降级。给界面看,不给业务用。 */
    public Map<String, Object> lastCallInfo() {
        return lastCall;
    }

    /** 上一次调用实际用的模型名(省钱降级后的真实值)。 */
    public String lastModel() {
        return lastModel.get();
    }

    /** 上一次调用是否被省钱护栏降级过。 */
    public boolean lastDowngraded() {
        return ModelGuard.downgraded(wantedModel.get(), lastModel.get());
    }

    /** 上一次调用服务商回传的输入/输出 token;服务商没给则返回 0。 */
    public int[] lastUsage() {
        return lastUsage.get();
    }

    /**
     * 记账用的 token 数:优先用服务商回传的真实用量,没有就退回估算值。
     * 之前全靠「字符数估 token」,中文和 JSON 的偏差能到一倍以上,成本表因此不可信。
     */
    public long tokensUsed(long fallbackEstimate) {
        int[] u = lastUsage.get();
        long real = (long) u[0] + u[1];
        return real > 0 ? real : Math.max(0, fallbackEstimate);
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    /**
     * 服务端连通自检:真发一条最短请求,验证 baseUrl / 密钥 / 模型名三者确实可用。
     * 只回结论与耗时,密钥和上游错误体都不外传。
     */
    public Map<String, Object> test(Provider p, String model) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        out.put("ok", false);
        if (p == null) {
            out.put("message", "还没有可测的服务商,请先在「设置 → 模型服务」填写");
            return out;
        }
        String useModel = (model != null && !model.isBlank()) ? model : p.model;
        out.put("provider", p.name);
        out.put("model", useModel);
        if (p.baseUrl == null || p.baseUrl.isBlank()) { out.put("message", "该服务商没有填 Base URL"); return out; }
        if (useModel == null || useModel.isBlank()) { out.put("message", "该服务商没有填默认模型名"); return out; }
        try {
            String reply = chatStream(List.of(new ChatMessage("user", "只回复两个字母:OK")), useModel, null, p);
            out.put("ok", true);
            out.put("message", "模型已应答 · " + truncate(reply.trim(), 40));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 上游错误体里可能带请求头回显,一律截断
            out.put("message", truncate(msg, 160));
        }
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
    }

    /**
     * 拉取服务商可用模型列表(OpenAI 兼容的 GET /models),供前端做下拉选择。
     * 手敲模型名很容易写错,而写错的表现是接口 400、生产线整段停摆,排查成本很高。
     * 密钥只在服务端使用,不回传给浏览器。
     */
    public Map<String, Object> listModels(Provider p) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        out.put("ok", false);
        out.put("models", java.util.List.of());
        if (p == null) {
            out.put("message", "还没有服务商,请先在上面填写一个");
            return out;
        }
        if (p.baseUrl == null || p.baseUrl.isBlank()) {
            out.put("message", "该服务商没有填 Base URL");
            return out;
        }
        String url = p.baseUrl.replaceAll("/+$", "") + "/models";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + (p.apiKey == null ? "" : p.apiKey))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                out.put("message", "拉取失败,HTTP " + resp.statusCode() + ":" + truncate(resp.body(), 200));
                out.put("ms", System.currentTimeMillis() - t0);
                return out;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode arr = root.path("data");
            if (!arr.isArray()) arr = root.path("models");
            java.util.List<String> ids = new java.util.ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String id = n.path("id").asText("");
                    if (id.isBlank()) id = n.asText("");
                    if (!id.isBlank()) ids.add(id);
                }
            }
            java.util.Collections.sort(ids);
            out.put("ok", true);
            out.put("models", ids);
            out.put("message", ids.isEmpty() ? "服务商没返回模型列表,可以直接手填模型名" : "可用模型 " + ids.size() + " 个");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            out.put("message", "拉取失败:" + truncate(msg, 160));
        }
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
    }
}
