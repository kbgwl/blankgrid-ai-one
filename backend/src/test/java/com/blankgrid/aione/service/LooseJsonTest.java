package com.blankgrid.aione.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拆解失败的现场还原:真跑目标 #5 时,CEO 的回复是"写到一半被截断的 JSON",
 * 老代码取第一个 { 到最后一个 } 一截,必然残缺。这些用例就是那次事故的标本。
 */
class LooseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String raw) {
        return LooseJson.parse(mapper, raw);
    }

    @Test
    @DisplayName("标准 JSON 原样可解析,且正文里的「」不会被改写")
    void strictJson() {
        String raw = "{\"summary\":\"按「产品 → 文案」推进\",\"workstreams\":[{\"title\":\"文案\"}]}";
        JsonNode n = parse(raw);
        assertNotNull(n);
        assertEquals("按「产品 → 文案」推进", n.path("summary").asText());
        assertEquals("文案", n.path("workstreams").path(0).path("title").asText());
    }

    @Test
    @DisplayName("包在 ```json 围栏里,或前后加了一句客套话")
    void fencedAndChatty() {
        String fenced = "```json\n{\"summary\":\"ok\"}\n```";
        assertEquals("ok", parse(fenced).path("summary").asText());

        String chatty = "好的,以下是拆解结果:\n{\"summary\":\"ok\"}\n希望对你有帮助。";
        assertEquals("ok", parse(chatty).path("summary").asText());
    }

    @Test
    @DisplayName("被输出上限截断:已写完的条目要保住,不能整段丢弃")
    void truncatedKeepsFinishedItems() {
        // 第二个工作流写到 brief 中间就被切断了 —— 就是实跑时发生的事
        String raw = "{\"summary\":\"四条线并行\",\"needsInput\":[\"产品事实\"],\"workstreams\":["
                + "{\"departmentId\":1,\"leadId\":11,\"title\":\"产品页结构\",\"brief\":\"梳理信息架构\"},"
                + "{\"departmentId\":2,\"leadId\":12,\"title\":\"市场文案\",\"brief\":\"写出首屏卖点与";
        assertTrue(LooseJson.looksTruncated(raw));

        JsonNode n = parse(raw);
        assertNotNull(n, "截断的 JSON 必须能救回来");
        assertEquals("四条线并行", n.path("summary").asText());
        assertEquals(2, n.path("workstreams").size());
        assertEquals("产品页结构", n.path("workstreams").path(0).path("title").asText());
        // 被截断的那条:标题在、brief 补了引号收尾,内容不算完整但结构合法
        assertEquals("市场文案", n.path("workstreams").path(1).path("title").asText());
        assertTrue(n.path("workstreams").path(1).path("brief").asText().startsWith("写出首屏卖点"));
    }

    @Test
    @DisplayName("缺右括号、多尾逗号也能补成合法结构")
    void unbalancedAndTrailingComma() {
        String raw = "{\"workstreams\":[{\"title\":\"a\"},{\"title\":\"b\"},]}";
        JsonNode n = parse(raw);
        assertNotNull(n);
        assertEquals(2, n.path("workstreams").size());
    }

    @Test
    @DisplayName("全角标点 + 「」当引号:模型偶尔会这么写")
    void fullWidthQuotes() {
        String raw = "{「summary」：「做官网」,「workstreams」:[{「title」：「首页文案」}]}";
        JsonNode n = parse(raw);
        assertNotNull(n, "全角引号写法也要能救");
        assertEquals("做官网", n.path("summary").asText());
        assertEquals("首页文案", n.path("workstreams").path(0).path("title").asText());
    }

    @Test
    @DisplayName("字符串里带裸换行(模型直接回车换行)不会把 JSON 撑破")
    void rawNewlineInsideString() {
        String raw = "{\"summary\":\"第一行\n第二行\"}";
        JsonNode n = parse(raw);
        assertNotNull(n);
        assertTrue(n.path("summary").asText().contains("第一行"));
    }

    @Test
    @DisplayName("彻底不是 JSON 时返回 null,让调用方去重试,而不是抛异常把线程打死")
    void notJsonAtAll() {
        assertNull(parse("我建议先做产品页,再做文案。"));
        assertNull(parse(""));
        assertNull(parse(null));
    }
}
