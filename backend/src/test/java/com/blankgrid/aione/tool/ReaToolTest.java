package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REA 接入的两处硬骨头:命令切分与工作区边界。
 *
 * <p>边界这一条最要紧:模型给的 target 是它自己填的字符串。
 * 如果不校验就交给 ProcessBuilder,一个 {@code ../../..} 就能让它去翻整台机器。
 */
class ReaToolTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("命令切分:保留带引号的空格路径")
    void splitCommand() {
        assertEquals(List.of("npx", "-y", "rea-agents@latest"),
                ReaTool.splitCommand("npx -y rea-agents@latest"));
        assertEquals(List.of("C:\\Program Files\\rea\\rea.exe", "doctor"),
                ReaTool.splitCommand("\"C:\\Program Files\\rea\\rea.exe\" doctor"));
        assertEquals(List.of("rea"), ReaTool.splitCommand("  rea  "), "多余空格不该产生空参数");
        assertTrue(ReaTool.splitCommand("").isEmpty());
        assertTrue(ReaTool.splitCommand(null).isEmpty());
    }

    @Test
    @DisplayName("没配工作区就一律拒绝:默认全盘可读不是一个可以接受的默认值")
    void refusesWithoutWorkspace() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace(tempDir.toString(), ""));
        assertTrue(e.getMessage().contains("工作区"));
    }

    @Test
    @DisplayName("工作区内的目标放行,绝对路径与相对路径都行")
    void allowsInsideWorkspace() throws Exception {
        Path app = Files.createDirectories(tempDir.resolve("apps/demo"));
        Path byAbs = ReaTool.resolveInsideWorkspace(app.toString(), tempDir.toString());
        assertTrue(byAbs.startsWith(tempDir.toRealPath()));

        Path byRel = ReaTool.resolveInsideWorkspace("apps/demo", tempDir.toString());
        assertEquals(byAbs, byRel, "相对路径按工作区展开,结果应与绝对路径一致");
    }

    @Test
    @DisplayName("越界目标被拒:../ 绕不出去")
    void refusesOutsideWorkspace() throws Exception {
        Path inside = Files.createDirectories(tempDir.resolve("inner"));
        Path outside = Files.createDirectories(tempDir.resolve("outer"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace(outside.toString(), inside.toString()));
        assertTrue(e.getMessage().contains("不在 REA 工作区"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace("../outer", inside.toString()));
        assertTrue(e2.getMessage().contains("不在 REA 工作区"), "相对路径里的 .. 也要拦住");
    }

    @Test
    @DisplayName("node 目录:填目录原样用,填成可执行文件路径就取父目录")
    void nodeDirNormalization() {
        assertEquals("", ReaTool.nodeDirOf(""));
        assertEquals("", ReaTool.nodeDirOf(null));

        Path dir = tempDir.resolve("node22");
        assertEquals(dir.toAbsolutePath().toString(), ReaTool.nodeDirOf(dir.toString()));

        // 人很容易把完整 exe 路径贴进来,不该为此让人回去改配置
        Path exe = tempDir.resolve("node.exe");
        try {
            java.nio.file.Files.writeString(exe, "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(tempDir.toAbsolutePath().toString(), ReaTool.nodeDirOf(exe.toString()));
    }

    @Test
    @DisplayName("指定的 node 目录必须前置到子进程 PATH,否则 REA 看到的永远是系统那版")
    void nodeDirGoesFirstInPath() {
        ProcessBuilder pb = new ProcessBuilder("cmd");
        String key = null;
        for (String k : pb.environment().keySet()) {
            if ("path".equalsIgnoreCase(k)) { key = k; break; }
        }
        if (key == null) key = "PATH";
        pb.environment().put(key, "C:\\system\\bin");

        ReaTool.prependPath(pb, "D:\\tools\\node");

        String after = pb.environment().get(key);
        assertTrue(after.startsWith("D:\\tools\\node"), after);
        assertTrue(after.endsWith("C:\\system\\bin"), "系统原有的 PATH 不能丢:" + after);
    }

    @Test
    @DisplayName("没配 node 目录时不动 PATH")
    void emptyNodeDirLeavesPathAlone() {
        ProcessBuilder pb = new ProcessBuilder("cmd");
        String key = null;
        for (String k : pb.environment().keySet()) {
            if ("path".equalsIgnoreCase(k)) { key = k; break; }
        }
        if (key == null) key = "PATH";
        pb.environment().put(key, "C:\\system\\bin");

        ReaTool.prependPath(pb, "");

        assertEquals("C:\\system\\bin", pb.environment().get(key));
    }

    @Test
    @DisplayName("工作区不存在时直接报错,不用等外部进程起来才发现")
    void refusesMissingWorkspace() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReaTool.resolveInsideWorkspace("x", tempDir.resolve("nope").toString()));
        assertTrue(e.getMessage().contains("不存在"));
    }

    // ==================== 结果解析 ====================

    private ReaTool tool() {
        SettingsService settings = new SettingsService(tempDir.toString());
        return new ReaTool(settings, tempDir.toString());
    }

    @Test
    @DisplayName("REA 的输出是 JSONL:进度行要丢掉,最后那行 Evidence 才是结论")
    void parsesJsonlEvidence() {
        // 真实输出是一行一个 JSON(JSONL);下面每行都必须完整,和 CLI 的实际形状一致
        String raw = """
                {"rea_progress":{"phase":"analyze_javascript_application","message":"Inventorying and parsing application artifacts","sequence":1}}
                {"rea_progress":{"phase":"analyze_javascript_application","completed":1,"total":1,"message":"Application graph and Electron boundaries reconstructed","terminal":true,"sequence":2}}
                {"evidence_id":"ev_1c3b","confidence":"derived","authority":"shipped-artifact","normalized_result":{"statistics":{"relevant_files":5,"parsed_javascript_files":3,"visited_ast_nodes":243,"findings":18},"summary":{"browser_windows":1,"preload_entrypoints":1,"context_bridge_apis":1,"exposed_api_members":1,"native_addon_bindings":0,"ipc":{"operations":2,"literal_channels":1,"main_handlers":1,"renderer_transmissions":1,"paired_renderer_transmissions":1,"dynamic_channel_operations":0}},"graph":{"nodes":[{"observations":[{"label":"electron.ipcRenderer.invoke","properties":{"channel":"search-products"}}]}]},"route_inventory":{"sources":[{"path":"src/main.js"},{"path":"src/preload.js"}]}},"limitations":["CommonJS binding relationships were recovered from inert syntax."]}
                """;

        String out = tool().summarizeJson(raw, "E:/data/rea/x.json", 1234);

        assertTrue(out.contains("ev_1c3b"), "要带 evidence_id,结论可回溯:" + out);
        assertTrue(out.contains("窗口 1"), "Electron 结构要能一眼看到:" + out);
        assertTrue(out.contains("IPC"), out);
        assertTrue(out.contains("search-products"), "通道名是最能回答'怎么实现'的证据:" + out);
        assertTrue(out.contains("src/main.js"), out);
        assertTrue(out.contains("CommonJS"), "要带上结论的边界,不能把推断当事实:" + out);
        assertTrue(out.contains("最后一步"), out);
        assertFalse(out.contains("rea_progress"), "进度行不能泄漏给模型");
    }

    @Test
    @DisplayName("只有进度没有 Evidence:说清楚,别假装分析成功")
    void handlesMissingEvidence() {
        String raw = """
                {"rea_progress":{"phase":"parse","message":"Parsing src/main.js","sequence":1}}
                """;
        String out = tool().summarizeJson(raw, "", 100);
        assertTrue(out.contains("没有找到 Evidence"), out);
        assertTrue(out.contains("Parsing src/main.js"), out);
    }

    @Test
    @DisplayName("doctor 的报告识别:环境不全时也是 exit 1,但报告本身是有效结论")
    void recognisesDoctorReport() {
        String report = """
                healthy: false
                environment_healthy: false
                scope_checks[2]:
                  - name: node
                    ok: false
                """;
        assertTrue(ReaTool.looksLikeDoctorReport(report));
        assertFalse(ReaTool.looksLikeDoctorReport("npm ERR! network timeout"));
    }
}
