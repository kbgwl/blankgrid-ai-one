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
        try {
            JsonNode n = mapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            String a = n.path("action").asText("");
            if (!a.isBlank()) action = a.trim();
            target = n.path("target").asText("").trim();
        } catch (Exception e) {
            return "参数不是合法 JSON:" + e.getMessage() + "。正确形式:{\"action\":\"doctor\"}";
        }

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
        if (r.exit != 0) {
            return "REA 执行失败(exit " + r.exit + "," + cost + "ms):\n" + clip(body, 3000)
                    + hintFor(body);
        }
        return "REA " + action + " 完成(" + cost + "ms):\n" + clip(body, 4000);
    }

    /** 分析结果的压缩:能解析成 JSON 就抽结构,解析不了就截断原文。 */
    private String summarizeJson(String raw, String savedPath, long cost) {
        StringBuilder sb = new StringBuilder();
        sb.append("REA 分析完成(").append(cost).append("ms)。");
        if (!savedPath.isEmpty()) sb.append("完整结果已存:").append(savedPath).append("\n");
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode modules = root.path("modules");
            if (modules.isArray()) {
                sb.append("模块 ").append(modules.size()).append(" 个:");
                int i = 0;
                for (JsonNode m : modules) {
                    if (i++ >= 40) { sb.append(" …"); break; }
                    String name = m.path("path").asText(m.path("name").asText(""));
                    if (!name.isBlank()) sb.append("\n- ").append(clip(name, 120));
                }
                sb.append("\n");
            }
            JsonNode imports = root.path("imports");
            if (imports.isArray() && imports.size() > 0) {
                sb.append("依赖 ").append(imports.size()).append(" 项:");
                int i = 0;
                for (JsonNode m : imports) {
                    if (i++ >= 30) break;
                    sb.append(" ").append(clip(m.asText(""), 40));
                }
                sb.append("\n");
            }
            JsonNode boundaries = root.path("electronBoundaries");
            if (boundaries.isArray() && boundaries.size() > 0) {
                sb.append("Electron 边界 ").append(boundaries.size()).append(" 处。\n");
            }
            sb.append(clip(raw, 3000));
        } catch (Exception e) {
            sb.append(clip(raw, 4000));
        }
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
