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
        public ChatMessage(String role, String content) { this.role = role; this.content = content; }
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
        Provider p = forced != null ? forced : settings.primaryProvider();
        if (p == null || p.baseUrl == null || p.baseUrl.isBlank()) {
            throw new IllegalStateException("尚未配置大模型 API,请在「设置」中填写服务商信息");
        }
        String want = (model != null && !model.isBlank()) ? model : p.model;
        // 省钱护栏:不管这个模型名是从员工档案、任务记录还是决策槽位来的,
        // 只要它属于贵的那一档,就在这里换成省钱模型。唯一出口,漏不掉。
        String useModel = settings.resolveModel(want);
        if (useModel == null || useModel.isBlank()) {
            throw new IllegalStateException("尚未配置默认模型,请在「设置」中填写");
        }
        wantedModel.set(want == null ? "" : want);
        lastModel.set(useModel);
        if (!ModelGuard.norm(want).isEmpty() && ModelGuard.downgraded(want, useModel)) {
            log.info("省钱模式:模型 {} → {}", want, useModel);
        }
        return send(p, messages, useModel, onDelta, jsonMode, maxTokens, true, settings.thinkingOff(), 2);
    }

    /**
     * @param downgrades 还能降级几次。response_format / max_tokens / stream_options 是 OpenAI 协议的可选扩展,
     *                   自建网关或老版本服务商可能直接 400。撞上了就把这个参数去掉重发,
     *                   而不是让整条生产线因为"多传了一个字段"停掉。
     */
    private String send(Provider p, List<ChatMessage> messages, String useModel, Consumer<String> onDelta,
                        boolean jsonMode, int maxTokens, boolean usageOpts, boolean thinkOff, int downgrades) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);
        body.put("stream", true);
        if (jsonMode) body.putObject("response_format").put("type", "json_object");
        if (maxTokens > 0) body.put("max_tokens", maxTokens);
        // DeepSeek 的思考模式默认开着且默认 high:模型先写一大段推理再落笔,这段推理
        // 按输出 token 计价,长任务里能占掉一半以上,还常常把输出上限吃光导致正文为空。
        // 写文案、写方案这类活不需要额外推理,默认关掉,账单立刻掉一个量级。
        if (thinkOff) body.putObject("thinking").put("type", "disabled");
        // 流式默认不回 usage,得主动要。有了真实用量,成本表才是账而不是估算。
        if (usageOpts) body.putObject("stream_options").put("include_usage", true);
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role);
            n.put("content", m.content);
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
                    boolean badJson = jsonMode && (low.contains("response_format") || low.contains("json_object")
                            || low.contains("json mode"));
                    boolean badMax = maxTokens > 0 && low.contains("max_tokens");
                    boolean badUsage = usageOpts && (low.contains("stream_options") || low.contains("include_usage"));
                    boolean badThink = thinkOff && (low.contains("thinking") || low.contains("reasoning"));
                    if (badJson || badMax || badUsage || badThink) {
                        // 只去掉被顶回来的那个,别把其他的也一起丢了
                        return send(p, messages, useModel, onDelta, jsonMode && !badJson, badMax ? 0 : maxTokens,
                                usageOpts && !badUsage, thinkOff && !badThink, downgrades - 1);
                    }
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
                } catch (IOException ignored) {
                    // 跳过无法解析的行
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断");
        } catch (IOException e) {
            throw new IllegalStateException("连接大模型 API 失败: " + e.getMessage(), e);
        }
        lastReasoning.set(truncate(reasoning.toString(), 4000));
        lastCall = Map.of(
                "model", useModel,
                "wanted", wantedModel.get(),
                "downgraded", ModelGuard.downgraded(wantedModel.get(), useModel),
                "promptTokens", lastUsage.get()[0],
                "completionTokens", lastUsage.get()[1],
                "thinkingChars", reasoning.length(),
                "finishReason", lastFinishReason.get());
        if (full.isEmpty()) {
            if (upstreamError[0] != null) throw new IllegalStateException("API 错误: " + truncate(upstreamError[0], 300));
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
        return full.toString();
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
