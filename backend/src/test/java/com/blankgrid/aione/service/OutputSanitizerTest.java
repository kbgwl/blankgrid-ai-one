package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交付物净化的边界。
 *
 * <p>这一层最容易犯的错是"清得太干净":把交付物里的合法代码也擦了。
 * 所以每个用例都带一条反面:该删的删掉,不该动的一个字都不许动。
 */
class OutputSanitizerTest {

    @Test
    @DisplayName("续写协议的 [[DONE]] 不能印在交付物里")
    void stripsDoneMarker() {
        String out = OutputSanitizer.clean("正文写完了。\n[[DONE]]");
        assertFalse(out.contains("[[DONE]]"), out);
        assertTrue(out.contains("正文写完了。"), out);
    }

    @Test
    @DisplayName("推理标签漏进正文:整行只有标签就整行删,不留空行")
    void stripsLeakedTagsOnOwnLine() {
        String raw = "第一部分内容。\n</summary>\n第二部分内容。";
        String out = OutputSanitizer.clean(raw);
        assertFalse(out.contains("summary"), out);
        assertTrue(out.contains("第一部分内容。") && out.contains("第二部分内容。"), out);
        assertFalse(out.contains("\n\n\n"), "不该留下连续多行空白:" + out);
    }

    @Test
    @DisplayName("标签混在句子里:只删标签,句子保留")
    void stripsInlineTagOnly() {
        String out = OutputSanitizer.clean("这是结论</summary>,下面展开。");
        assertEquals("这是结论,下面展开。", out);
    }

    @Test
    @DisplayName("代码块里的 <summary> 是合法 HTML,一个字符都不能动")
    void keepsTagsInsideFence() {
        String raw = """
                ```html
                <details><summary>点我看详情</summary>
                内容
                </details>
                ```
                """;
        String out = OutputSanitizer.clean(raw);
        assertTrue(out.contains("<summary>点我看详情</summary>"), out);
        assertTrue(out.startsWith("```html"), out);
    }

    @Test
    @DisplayName("整份是 HTML 交付物时,标签就是正文,不能清")
    void keepsHtmlDeliverable() {
        String raw = "<details><summary>标题</summary>\n正文\n</details>";
        assertEquals(raw, OutputSanitizer.clean(raw));
    }

    @Test
    @DisplayName("整份被一对 ``` 包住:解包,不然导出后满屏等宽字体")
    void unwrapsWholeFence() {
        String raw = "```markdown\n# 标题\n\n正文\n```";
        String out = OutputSanitizer.clean(raw);
        assertTrue(out.startsWith("# 标题"), out);
        assertFalse(out.contains("```"), out);
    }

    @Test
    @DisplayName("正文里本来就有代码块:不能误判成外包一层")
    void keepsMultipleFences() {
        String raw = "```java\nint a = 1;\n```\n\n说明\n\n```bash\nls\n```";
        String out = OutputSanitizer.clean(raw);
        assertEquals(raw, out);
    }

    @Test
    @DisplayName("开头「（接上文）」这类接话残留要剥掉")
    void stripsLeadingNoise() {
        assertEquals("剩下的内容", OutputSanitizer.clean("（接上文）剩下的内容"));
        assertEquals("剩下的内容", OutputSanitizer.clean("续上：剩下的内容"));
        // 正文中间出现是正常叙述,不能动
        assertTrue(OutputSanitizer.clean("他说（接上文）那句话").contains("（接上文）"));
    }

    @Test
    @DisplayName("连续空行压缩成一行")
    void collapsesBlankLines() {
        String out = OutputSanitizer.clean("一\n\n\n\n\n二");
        assertEquals("一\n\n二", out);
    }

    @Test
    @DisplayName("null / 空串安全返回,不抛异常")
    void handlesNull() {
        assertEquals("", OutputSanitizer.clean(null));
        assertEquals("", OutputSanitizer.clean(""));
    }

    @Test
    @DisplayName("干净的正文原样返回,不做无谓改动")
    void keepsCleanText() {
        String raw = "# 标题\n\n- 条目一\n- 条目二\n\n```bash\nls -la\n```\n";
        assertEquals(raw.strip(), OutputSanitizer.clean(raw));
    }
}
