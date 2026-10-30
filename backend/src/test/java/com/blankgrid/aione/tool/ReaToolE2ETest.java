package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真跑一次 REA 分析(需要本机有 node + 网络 + 已缓存 rea-agents 包)。
 *
 * <p>默认不跑:`mvn test` 不该因为外网慢或者某台机器没装 node 变红。
 * 需要验证时手动开:
 * <pre>
 *   mvn -o test -Dtest=ReaToolE2ETest -DreaE2E=true
 * </pre>
 *
 * <p>它证明的是「参数解析 → 工作区校验 → 起进程(cmd /c npx)→ 读 JSONL → 压摘要」
 * 这条完整链路,而不只是单元里那几个分支。
 */
@EnabledIfSystemProperty(named = "reaE2E", matches = "true")
class ReaToolE2ETest {

    private static final Path WORKDIR = Path.of("E:/reverse");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("analyze_js 端到端:分析 demo-app,摘要里应有 IPC 通道")
    void analyzeDemoApp() throws Exception {
        SettingsService settings = new SettingsService(tempDir.toString());
        SettingsService.Settings in = new SettingsService.Settings();
        in.rea = new SettingsService.Rea();
        in.rea.enabled = true;
        in.rea.workdir = WORKDIR.toString();
        settings.save(in);

        ReaTool tool = new ReaTool(settings, tempDir.toString());
        assertTrue(tool.available(), "配好工作区后工具应可用");

        String out = tool.execute("{\"action\":\"analyze_js\",\"target\":\"demo-app\"}", ToolContext.empty());

        System.out.println("---- REA 摘要 ----\n" + out + "\n------------------");
        assertTrue(out.contains("IPC") || out.contains("Electron"), out);
        assertTrue(out.contains("search-products"), "contextBridge 的通道应被抽出来:\n" + out);
        assertTrue(out.length() < 6000, "摘要要能塞回上下文,不能是几百 KB:" + out.length());
    }

    @Test
    @DisplayName("越界目标:工作区外的绝对路径必须被拒,且不起进程")
    void rejectsOutsideTarget() throws Exception {
        SettingsService settings = new SettingsService(tempDir.toString());
        SettingsService.Settings in = new SettingsService.Settings();
        in.rea = new SettingsService.Rea();
        in.rea.enabled = true;
        // 工作区设成一个空目录,却让模型去分析另一个目录
        Path empty = Files.createDirectories(tempDir.resolve("workspace"));
        in.rea.workdir = empty.toString();
        settings.save(in);

        ReaTool tool = new ReaTool(settings, tempDir.toString());
        String out = tool.execute("{\"action\":\"analyze_js\",\"target\":\"" + WORKDIR.resolve("demo-app") + "\"}",
                ToolContext.empty());
        assertTrue(out.contains("不在 REA 工作区"), out);
    }
}
