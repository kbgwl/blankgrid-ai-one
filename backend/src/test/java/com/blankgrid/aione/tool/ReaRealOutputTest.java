package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 拿真实 REA 输出回放一次摘要解析。
 *
 * <p>为什么值得单独放一个用例:REA 的 CLI 输出是 34 行 JSONL、410KB,
 * 其中 33 行是进度、最后一行才是 Evidence。用构造数据测,只能证明"我以为的结构";
 * 用真文件测,才能证明"它实际吐出来的结构"。
 *
 * <p>样本不在仓库里(410KB,而且依赖本机跑过一次分析):文件不存在就跳过,
 * CI 与本机都能跑,不会因为缺文件变红。
 */
class ReaRealOutputTest {

    /** 本地真实样本:E:/reverse/sample-rea.jsonl,由 analyze-javascript-application 产出 */
    private static final Path SAMPLE = Path.of("E:/reverse/sample-rea.jsonl");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("真实 JSONL:进度丢掉,Evidence 抽出 IPC 拓扑与通道名")
    void replaysRealOutput() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "本机没有真实样本,跳过(不影响 CI)");
        String raw = Files.readString(SAMPLE, StandardCharsets.UTF_8);

        SettingsService settings = new SettingsService(tempDir.toString());
        ReaTool tool = new ReaTool(settings, tempDir.toString());
        String out = tool.summarizeJson(raw, "", 0);

        assertTrue(out.length() < 4000, "塞回模型的必须是摘要,不能是 410KB:" + out.length());
        assertTrue(out.contains("窗口 1"), "从真实 summary 里应读到 1 个窗口:\n" + out);
        assertTrue(out.contains("IPC"), out);
        assertTrue(out.contains("search-products"), "demo-app 的 contextBridge 通道应被抽出来:\n" + out);
        assertTrue(out.contains("src/main.js"), "已分析文件清单应出现:\n" + out);
        assertTrue(out.contains("evidence_id=ev_"), out);
        assertTrue(!out.contains("rea_progress"), "进度行不该出现在给模型的文本里");
    }
}
