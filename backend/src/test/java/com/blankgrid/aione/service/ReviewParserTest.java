package com.blankgrid.aione.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评审输出解析:先分清"成果不行"和"评审没说清楚"。
 *
 * <p>线上真出过的事:两个交付物执行了 4 轮和 7 轮,分数全是 0,备注只有一句
 * 「评审未通过(0 分),返工轮次已用尽」—— 打开日志才发现评审任务返回的是空字符串。
 * 空输出被当成 0 分,每一轮都白白扣掉一次返工机会。
 */
class ReviewParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("标准 JSON:分数、结论、必改项都取到")
    void parsesNormalReview() {
        var r = ReviewParser.parse(mapper, """
                {"score":86,"pass":true,"summary":"可以上线","notes":"两处小修",
                 "mustFix":["统一按钮文案","补一张对比表"],"strengths":["结构清楚"],"needsInput":[]}
                """);
        assertTrue(r.usable());
        assertEquals(86, r.score());
        assertTrue(r.pass());
        assertEquals("可以上线", r.summary());
        assertTrue(r.notes().contains("两处小修"));
        assertTrue(r.notes().contains("统一按钮文案"));
        assertTrue(r.notes().contains("补一张对比表"));
    }

    @Test
    @DisplayName("空输出不能算 0 分 —— 它根本不是一次评审")
    void emptyOutputIsNotZeroScore() {
        var r = ReviewParser.parse(mapper, "   ");
        assertFalse(r.usable());
        assertEquals(0, r.score());
        assertTrue(r.summary().contains("没有返回任何内容"));
    }

    @Test
    @DisplayName("通篇客套没有结构:也不当结论用")
    void proseIsNotUsable() {
        var r = ReviewParser.parse(mapper, "这份成果整体不错,建议通过,细节可以再打磨一下。");
        assertFalse(r.usable());
    }

    @Test
    @DisplayName("是 JSON 但没有 score:同样不算评审结论")
    void jsonWithoutScoreIsNotUsable() {
        var r = ReviewParser.parse(mapper, "{\"summary\":\"看起来还行\"}");
        assertFalse(r.usable());
    }

    @Test
    @DisplayName("格式坏了但能从文本里抠出分数:算能用,原文不丢")
    void brokenJsonWithScoreStillUsable() {
        var r = ReviewParser.parse(mapper, "评审结果:{\"score\": 42, \"pass\": false, \"notes\": \"缺数据\",");
        assertTrue(r.usable());
        assertEquals(42, r.score());
        assertFalse(r.pass());
        assertTrue(r.notes().contains("\"score\": 42"));
    }

    @Test
    @DisplayName("围栏包住的 JSON 也能解")
    void fencedJson() {
        var r = ReviewParser.parse(mapper, "```json\n{\"score\":75,\"pass\":true,\"summary\":\"过\"}\n```");
        assertTrue(r.usable());
        assertEquals(75, r.score());
        assertTrue(r.pass());
    }

    @Test
    @DisplayName("分数越界要夹住:模型写出 120 分不能直接进账")
    void scoreClamped() {
        assertEquals(100, ReviewParser.parse(mapper, "{\"score\":120,\"pass\":true}").score());
        assertEquals(0, ReviewParser.parse(mapper, "{\"score\":-5,\"pass\":true}").score());
    }

    @Test
    @DisplayName("needsInput 原样带出来:评审说「我判不了,因为缺事实」是要人补料")
    void needsInputPreserved() {
        var r = ReviewParser.parse(mapper,
                "{\"score\":60,\"pass\":false,\"needsInput\":[\"真实客户数\",\"年费价格\"]}");
        assertTrue(r.usable());
        assertEquals(2, r.needsInput().size());
        assertEquals("真实客户数", r.needsInput().get(0).asText());
    }
}
