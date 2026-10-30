package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REA —— Reverse Engineer Anything(github.com/morluto/rea)。
 *
 * <p>REA 本身不反编译:它是一套把 Ghidra / JADX / 包解析这类引擎包成「调查流程」的
 * MCP 服务 + CLI,让 Agent 能一步步取证,结论带证据链。这里走它的 CLI ——
 * 一条命令拿一份 JSON,不用在本项目里实现 MCP 客户端。
 *
 * <p>为什么值得接:公司里"看懂一个没有源码的东西"是高频需求 ——
 * 竞品客户端怎么实现、某个接口的请求结构是什么、一段混淆代码到底在干什么。
 * 过去这类活得派一个人啃几天,现在可以变成决策链里的一次工具调用。
 *
 * <p>安全边界写在配置里:只能分析 {@code rea.workdir} 下的目标。
 * 不设这个边界,模型拿到一个绝对路径就能翻整台机器,这是不能开的口子。
 */
@Component
public class ReaTool implements AiTool {

    private static final Logger log = LoggerFactory.getLogger(ReaTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String ACTION_DOCTOR = "doctor";
    private static final String ACTION_ANALYZE_JS = "analyze_js";

    private final SettingsService settings;
    private final Path outputDir;

    public ReaTool(SettingsService settings, @Value("${aione.data-dir:./data}") String dataDir) {
        this.settings = settings;
        this.outputDir = Path.of(dataDir, "rea");
    }

    @Override
    public String name() {
        return "rea_reverse";
    }

    @Override
    public String category() {
        return "reverse";
    }

    @Override
    public String description() {
        return "逆向分析一个没有源码的软件/安装包/前端包,搞清楚它内部是怎么实现的。"
                + "action=doctor 先自检工具链(没装好、缺 Java/Ghidra 会在这里报出来),"
                + "action=analyze_js 分析 Electron/JS 应用目录或 asar 包,返回模块结构、依赖关系、"
                + "接口调用等带证据的结论。"
                + "典型用法:想抄某个客户端的功能、想弄清一个压缩混淆的前端包在干什么、"
                + "想确认某个 App 调的是哪个接口。";
    }

    @Override
    public String paramsSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["doctor", "analyze_js"],
                      "description": "doctor=自检 REA 工具链;analyze_js=分析 Electron/JS 应用目录或 .asar 包"
                    },
                    "target": {
                      "type": "string",
                      "description": "要分析的目标绝对路径(目录、asar 包或单个 js 文件)。必须在配置的工作区目录下。doctor 不需要"
                    }
                  },
                  "required": ["action"]
                }""";
    }

    @Override
    public String execute(String argsJson, ToolContext ctx) throws Exception {
        SettingsService.Rea conf = settings.rea();
        String action = ACTION_DOCTOR;
        String target = "";
        JsonNode n = parseArgs(argsJson);
        if (n == null) {
            return "参数不是合法 JSON。正确形式:{\"action\":\"doctor\"};"
                    + "Windows 路径写成正斜杠 E:/apps/demo,或把反斜杠转义成 \\\\。";
        }
        String a = n.path("action").asText("");
        if (!a.isBlank()) action = a.trim();
        target = n.path("target").asText("").trim();
        // 路径里的 \r / \t 被 JSON 当成转义吃掉时,还原成它们本来该是的分隔符
        if (target.chars().anyMatch(c -> c < 0x20)) target = restorePathEscapes(target);

        List<String> cmd = new ArrayList<>(splitCommand(conf.command));
        if (cmd.isEmpty()) return "REA 的启动命令没配置(设置 → 逆向分析)。";

        switch (action) {
            case ACTION_DOCTOR -> cmd.add("doctor");
            case ACTION_ANALYZE_JS -> {
                if (target.isBlank()) return "analyze_js 需要 target:要分析的目录/asar 包绝对路径。";
                Path resolved;
                try {
                    resolved = resolveInsideWorkspace(target, conf.workdir);
                } catch (IllegalArgumentException e) {
                    return e.getMessage();
                }
                cmd.add("analyze-javascript-application");
                cmd.add(resolved.toString());
                cmd.add("--json");
            }
            default -> {
                return "未知 action:" + action + "。可用:doctor / analyze_js";
            }
        }

        long t0 = System.currentTimeMillis();
        Result r = run(cmd, conf.timeoutSeconds);
        long cost = System.currentTimeMillis() - t0;

        // 分析产出往往是几十上百 KB 的 JSON:原样塞回给模型会把上下文吃光。
        // 落盘留全文,回给模型的是压缩摘要 —— 需要细节时人还能翻到原始文件。
        if (ACTION_ANALYZE_JS.equals(action) && r.exit == 0) {
            String saved = dump(r.stdout);
            return summarizeJson(r.stdout, saved, cost);
        }

        String body = r.stdout.isBlank() ? r.stderr : r.stdout;
        if (r.exit != 0 && !r.stderr.isBlank() && !body.contains(r.stderr)) {
            body = body + "\n" + r.stderr;
        }

        // doctor 的 exit 1 不是"命令失败",是"环境不全健康"——它照样输出了完整诊断。
        // 当成失败处理,模型就只能看到一句"执行失败",而真正有价值的正是缺哪一项、怎么补。
        if (ACTION_DOCTOR.equals(action) && looksLikeDoctorReport(body)) {
            return "REA 工具链自检(" + cost + "ms," + (r.exit == 0 ? "全部正常" : "存在待补项") + "):\n"
                    + clip(body, 3500)
                    + "\n只做 JS/Electron 分析的话 node 一项过了就够;原生二进制才需要 ghidra / ida / hopper。";
        }

        if (r.exit != 0) {
            return "REA 执行失败(exit " + r.exit + "," + cost + "ms):\n" + clip(body, 3000)
                    + hintFor(body);
        }
        return "REA " + action + " 完成(" + cost + "ms):\n" + clip(body, 4000);
    }

    /**
     * 解析模型给的参数。三级兜底,因为模型写 JSON 的手艺不稳定:
     * 原始 → 修 Windows 路径反斜杠 → LooseJson。
     *
     * <p>「E:\reverse\apps\demo」这种值是真实会出现的:模型知道是 Windows 路径,
     * 但不知道 JSON 里反斜杠要转义。直接判非法,等于把一次分析机会白白丢掉。
     */
    private JsonNode parseArgs(String argsJson) {
        String json = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        try {
            return mapper.readTree(json);
        } catch (Exception ignored) {
            // 往下走
        }
        try {
            return mapper.readTree(escapeStrayBackslashes(json));
        } catch (Exception ignored) {
            // 往下走
        }
        return com.blankgrid.aione.service.LooseJson.parse(mapper, json);
    }

    /**
     * 把 JSON 里"不是合法转义"的反斜杠补成双反斜杠。
     * 合法的只有 " \ / b f n r t u,其余一律按路径分隔符处理。
     */
    static String escapeStrayBackslashes(String json) {
        if (json == null) return "";
        StringBuilder sb = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
            boolean legal = next == '"' || next == '\\' || next == '/' || next == 'b'
                    || next == 'f' || next == 'n' || next == 'r' || next == 't' || next == 'u';
            if (legal) sb.append('\\');
            else sb.append("\\\\");
        }
        return sb.toString();
    }

    /**
     * 把被 JSON 解析吃成控制字符的路径分隔符还原回 "反斜杠+字母"。
     *
     * <p>为什么需要:E:\reverse 里的 \r 是合法 JSON 转义,解析出来是真的回车,
     * 语法层面无法救。结果就是一个带控制字符的路径,后面必然"路径不存在"。
     * 与其报一句让人摸不着头脑的错误,不如把它还原成用户/模型本来想写的路径。
     */
    static String restorePathEscapes(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\r", "\\r").replace("\n", "\\n")
                .replace("\t", "\\t").replace("\b", "\\b").replace("\f", "\\f");
    }

    /** doctor 的报告长这样:YAML/JSON,含 healthy 或 scope_checks。 */
    static boolean looksLikeDoctorReport(String text) {
        if (text == null) return false;
        String low = text.toLowerCase(java.util.Locale.ROOT);
        return low.contains("scope_checks") || low.contains("environment_healthy")
                || (low.contains("healthy") && low.contains("node"));
    }

    /**
     * 分析结果的压缩。
     *
     * <p>REA 的 CLI 输出不是单个 JSON,而是 <b>JSONL</b>:几十行 rea_progress 进度事件,
     * 最后一行才是 Evidence 对象(evidence_id / normalized_result / limitations)。
     * 按整段 JSON 解析必然失败,退化成把一堆进度行塞给模型 —— 那等于没分析。
     *
     * <p>真正有用的是 normalized_result 里的 summary:窗口数、preload 入口、contextBridge
     * 暴露了什么、IPC 有几条通道、主进程 handler 几个。这就是"这个软件怎么实现的"的答案骨架,
     * 带 evidence_id 可以回溯,原文另存一份给人复核。
     */
    String summarizeJson(String raw, String savedPath, long cost) {
        JsonNode evidence = null;
        String lastProgress = "";
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("{")) continue;
            try {
                JsonNode node = mapper.readTree(t);
                if (node.has("rea_progress")) {
                    String msg = node.path("rea_progress").path("message").asText("");
                    if (!msg.isBlank()) lastProgress = msg;
                    continue;
                }
                if (node.has("evidence_id")) evidence = node;
            } catch (Exception ignored) {
                // 进度行偶尔被截断,跳过
            }
        }

        // 兜底:有些版本会把 Evidence 作为一个完整 JSON 输出(而不是 JSONL 的最后一行)
        if (evidence == null) {
            try {
                JsonNode whole = mapper.readTree(raw.trim());
                if (whole.has("evidence_id")) evidence = whole;
                else if (whole.has("normalized_result")) evidence = whole;
            } catch (Exception ignored) {
                // 不是单对象,保持原样走下面的降级分支
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("REA 分析完成(").append(cost).append("ms)。");        if (!savedPath.isEmpty()) sb.append("完整证据已存:").append(savedPath);
        sb.append("\n");
        if (!lastProgress.isBlank()) sb.append("最后一步:").append(lastProgress).append("\n");

        if (evidence == null) {
            sb.append("输出里没有找到 Evidence 对象,下面是原始尾部:\n").append(clip(raw, 3000));
            return sb.toString();
        }

        sb.append("evidence_id=").append(evidence.path("evidence_id").asText(""))
          .append(" · 置信度 ").append(evidence.path("confidence").asText("-"))
          .append(" · 来源 ").append(evidence.path("authority").asText("-")).append("\n");

        JsonNode nr = evidence.path("normalized_result");
        JsonNode stats = nr.path("statistics");
        if (stats.isObject()) {
            sb.append("【概况】相关文件 ").append(stats.path("relevant_files").asInt(0))
              .append(" 个 · 解析 JS ").append(stats.path("parsed_javascript_files").asInt(0))
              .append(" 个 · AST 节点 ").append(stats.path("visited_ast_nodes").asInt(0))
              .append(" · 发现 ").append(stats.path("findings").asInt(0)).append(" 项\n");
        }

        JsonNode sum = nr.path("summary");
        if (sum.isObject()) {
            sb.append("【Electron 结构】窗口 ").append(sum.path("browser_windows").asInt(0))
              .append(" · preload 入口 ").append(sum.path("preload_entrypoints").asInt(0))
              .append(" · contextBridge API ").append(sum.path("context_bridge_apis").asInt(0))
              .append(" · 对外成员 ").append(sum.path("exposed_api_members").asInt(0))
              .append(" · 原生插件 ").append(sum.path("native_addon_bindings").asInt(0)).append("\n");
            JsonNode ipc = sum.path("ipc");
            if (ipc.isObject()) {
                sb.append("【IPC】操作 ").append(ipc.path("operations").asInt(0))
                  .append(" · 字面量通道 ").append(ipc.path("literal_channels").asInt(0))
                  .append(" · 主进程 handler ").append(ipc.path("main_handlers").asInt(0))
                  .append(" · 渲染发送 ").append(ipc.path("renderer_transmissions").asInt(0))
                  .append(" · 已配对 ").append(ipc.path("paired_renderer_transmissions").asInt(0))
                  .append(" · 动态通道 ").append(ipc.path("dynamic_channel_operations").asInt(0)).append("\n");
            }
        }

        // 通道名和调用点是最能直接回答"这个功能怎么实现的"的证据
        StringBuilder chans = new StringBuilder();
        for (JsonNode node : nr.path("graph").path("nodes")) {
            for (JsonNode ob : node.path("observations")) {
                JsonNode props = ob.path("properties");
                String ch = props.path("channel").asText("");
                if (ch.isBlank() || chans.indexOf(ch) >= 0) continue;
                if (chans.length() > 0) chans.append("、");
                chans.append(ch).append("(").append(ob.path("label").asText("")).append(")");
            }
        }
        if (chans.length() > 0) sb.append("【观察到的 IPC 通道】").append(clip(chans.toString(), 600)).append("\n");

        JsonNode srcs = nr.path("route_inventory").path("sources");
        if (srcs.isArray() && srcs.size() > 0) {
            sb.append("【已分析文件】");
            int i = 0;
            for (JsonNode s : srcs) {
                if (i++ >= 12) { sb.append(" …"); break; }
                String p = s.path("path").asText("");
                if (!p.isBlank()) sb.append(i > 1 ? "、" : "").append(p);
            }
            sb.append("\n");
        }

        JsonNode lim = evidence.path("limitations");
        if (lim.isArray() && lim.size() > 0) {
            sb.append("【结论的边界】").append(clip(lim.path(0).asText(""), 300)).append("\n");
        }
        sb.append("需要更细的节点/关系请说明具体想知道什么(哪条链路、哪个函数)。");
        return sb.toString();
    }

    /** 命令按空格切分,支持双引号包住带空格的路径。 */
    static List<String> splitCommand(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (char c : command.toCharArray()) {
            if (c == '"') { quote = !quote; continue; }
            if (Character.isWhitespace(c) && !quote) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * 目标必须在工作区之内。用 realPath 比对,防 ../ 和软链接绕出去。
     * 工作区没配就直接拒绝 —— "默认全盘可读"不是一个可以接受的默认值。
     */
    static Path resolveInsideWorkspace(String target, String workdir) {
        if (workdir == null || workdir.isBlank()) {
            throw new IllegalArgumentException("还没配置 REA 工作区(设置 → 逆向分析)。"
                    + "为安全起见,不配置工作区就不允许分析任何路径。");
        }
        Path root;
        try {
            root = Path.of(workdir).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                throw new IllegalArgumentException("REA 工作区不存在:" + root);
            }
            root = root.toRealPath();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("REA 工作区不可用:" + workdir + "(" + e.getMessage() + ")");
        }

        Path p = Path.of(target);
        if (!p.isAbsolute()) p = root.resolve(target);
        p = p.toAbsolutePath().normalize();
        Path real;
        try {
            real = Files.exists(p) ? p.toRealPath() : p;
        } catch (Exception e) {
            real = p;
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("目标不在 REA 工作区内,已拒绝:" + target
                    + "。只能分析 " + root + " 下的内容,把目标放进去再试。");
        }
        return real;
    }

    private record Result(int exit, String stdout, String stderr) {}

    /**
     * 把配置项归一成目录。
     *
     * <p>人很容易把 node.exe 的完整路径填进来,而不是目录 —— 那就取它的父目录,
     * 不必为这点小事让人回去改配置。
     */
    static String nodeDirOf(String configured) {
        if (configured == null || configured.isBlank()) return "";
        try {
            java.nio.file.Path p = java.nio.file.Path.of(configured.trim());
            if (java.nio.file.Files.isRegularFile(p)) p = p.getParent();
            return p == null ? "" : p.toAbsolutePath().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 把目录塞到子进程 PATH 最前面。Windows 的键名是 "Path",大小写不敏感但 Map 敏感。 */
    static void prependPath(ProcessBuilder pb, String dir) {
        if (dir == null || dir.isBlank()) return;
        java.util.Map<String, String> env = pb.environment();
        String key = null;
        for (String k : env.keySet()) {
            if ("path".equalsIgnoreCase(k)) { key = k; break; }
        }
        String use = key == null ? "PATH" : key;
        String cur = env.get(use);
        env.put(use, (cur == null || cur.isEmpty()) ? dir : dir + java.io.File.pathSeparator + cur);
    }

    private Result run(List<String> cmd, int timeoutSeconds) {
        List<String> actual = new ArrayList<>(cmd);
        // Windows 上 npx 是 .cmd,ProcessBuilder 不能直接起,得借 cmd /c
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            actual.add(0, "/c");
            actual.add(0, "cmd");
        }
        ProcessBuilder pb = new ProcessBuilder(actual);
        pb.redirectErrorStream(false);
        pb.environment().put("NO_COLOR", "1");
        // REA 靠 `node -v` 检测运行时,看的是子进程 PATH。不把指定 node 目录取前置,
        // 它永远报系统那版 —— 而系统那版往往正是"版本不够"的那一版。
        prependPath(pb, nodeDirOf(settings.rea().nodePath));
        Process proc = null;
        try {
            proc = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Process p = proc;
            Thread tOut = new Thread(() -> drain(p.getInputStream(), out, 200_000));
            Thread tErr = new Thread(() -> drain(p.getErrorStream(), err, 20_000));
            tOut.setDaemon(true);
            tErr.setDaemon(true);
            tOut.start();
            tErr.start();
            boolean done = proc.waitFor(Math.max(30, timeoutSeconds), TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                tOut.join(1000);
                tErr.join(1000);
                return new Result(-1, out.toString(), "超时 " + timeoutSeconds + " 秒未完成,已终止。"
                        + "首次运行 npx 需要下载 rea-agents,可能比较慢;可以先把包装好再跑。");
            }
            tOut.join(2000);
            tErr.join(2000);
            return new Result(proc.exitValue(), out.toString().trim(), err.toString().trim());
        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            log.warn("REA 启动失败:{}", e.getMessage());
            return new Result(-1, "", "启动命令失败:" + e.getMessage() + "(命令:" + String.join(" ", actual) + ")");
        }
    }

    private static void drain(java.io.InputStream in, StringBuilder sb, int limit) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < limit) sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
            // 进程被杀时读流会抛,忽略
        }
    }

    /** 原始输出落盘,方便人复核证据链。 */
    private String dump(String content) {
        try {
            Files.createDirectories(outputDir);
            Path f = outputDir.resolve("rea-" + System.currentTimeMillis() + ".json");
            Files.writeString(f, content == null ? "" : content, StandardCharsets.UTF_8);
            return f.toAbsolutePath().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 把常见失败翻译成"下一步该做什么",而不是让模型对着报错瞎猜。 */
    private static String hintFor(String text) {
        String low = text == null ? "" : text.toLowerCase();
        if (low.contains("enoent") || low.contains("not found") || low.contains("不是内部或外部命令")) {
            return "\n提示:npx/rea 命令没找到。需要本机装 Node.js(npx 随 Node 安装),"
                    + "或在设置里把启动命令改成已安装的 rea 可执行文件路径。";
        }
        if (low.contains("eai_again") || low.contains("network") || low.contains("etimedout")) {
            return "\n提示:拉取 rea-agents 包时网络不通。可以先在本机跑一次 npx rea-agents@latest doctor 把包缓存下来。";
        }
        if (low.contains("java") && (low.contains("not found") || low.contains("未找到"))) {
            return "\n提示:REA 的原生分析后端需要 Java 运行时,先装 JDK 21 再跑 doctor。";
        }
        return "\n提示:先跑 action=doctor 看工具链缺什么。";
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Override
    public boolean available() {
        SettingsService.Rea r = settings.rea();
        return r.ready() && !splitCommand(r.command).isEmpty();
    }

    @Override
    public String unavailableReason() {
        SettingsService.Rea r = settings.rea();
        if (!r.enabled) return "还没启用 REA 逆向分析。到「设置 → 逆向分析」打开开关,填好工作区目录即可。";
        if (r.workdir == null || r.workdir.isBlank()) {
            return "REA 已启用但没配工作区目录。工作区是安全边界:只能分析这个目录下的目标,"
                    + "不配置就不允许分析任何路径。";
        }
        if (splitCommand(r.command).isEmpty()) return "REA 启动命令为空,填不回来就恢复默认 npx -y rea-agents@latest。";
        return "";
    }

    /**
     * 决策层可用:看懂别人的软件是典型的"取证"动作,不改任何状态。
     * 但默认策略设成 confirm —— 它会启动外部进程、可能联网下载包,
     * 让创始人第一次点个头,比事后解释强。设置页可以一键改成全自动。
     */
    @Override
    public boolean decisionSafe() {
        return true;
    }

    @Override
    public String defaultPolicy() {
        return "confirm";
    }

    @Override
    public int timeoutSeconds() {
        int t = settings.rea().timeoutSeconds;
        // 外层 ToolRegistry 的超时需要比内层进程宽一点,否则我们自己还没收尾就被掐了
        return Math.max(60, t + 15);
    }

    /** 给设置页做连通自检用:直接跑一次 doctor。 */
    public String selfTest() {
        SettingsService.Rea conf = settings.rea();
        if (!available()) return "还没配好:" + unavailableReason();
        List<String> cmd = new ArrayList<>(splitCommand(conf.command));
        cmd.add("doctor");
        Result r = run(cmd, Math.min(120, conf.timeoutSeconds));
        String body = r.stdout.isBlank() ? r.stderr : r.stdout;
        if (looksLikeDoctorReport(body)) {
            // 命令跑通了:健康与否是报告内容,不是失败
            String head = r.exit == 0 ? "工具链正常" : "自检完成,有缺项需要补";
            return head + ":\n" + clip(body, 2000);
        }
        if (r.exit == 0) return "工具链正常:\n" + clip(body, 1500);
        return "自检失败(exit " + r.exit + "):\n" + clip(body, 1500) + hintFor(body);
    }

    /** 给设置页展示:当前生效的命令与工作区 */
    public Map<String, Object> status() {
        SettingsService.Rea r = settings.rea();
        return Map.of(
                "enabled", r.enabled,
                "command", r.command == null ? "" : r.command,
                "workdir", r.workdir == null ? "" : r.workdir,
                "ready", available(),
                "reason", unavailableReason()
        );
    }
}
