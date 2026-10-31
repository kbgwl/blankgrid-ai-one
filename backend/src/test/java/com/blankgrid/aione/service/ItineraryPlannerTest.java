package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.ItineraryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 行程解析:模型给的时间不能直接信。
 *
 * <p>这组用例的假设是"模型一定会给出几种坏时间" —— 只给日期、
 * 给去年的日期、给排到会议之前的日期、给一个不存在的日期。
 * 界面上显示一个自信的错时间,比显示"待定"危险得多。
 */
class ItineraryPlannerTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** 会议发生在 2026-10-31 */
    private static final Instant ANCHOR = LocalDateTime.of(2026, 10, 31, 10, 0).atZone(ZONE).toInstant();

    private List<ItineraryItem> parse(String json) {
        return ItineraryPlanner.parse(json, ANCHOR, ZONE).items();
    }

    @Test
    @DisplayName("完整时间的条目原样排进去")
    void parsesFullDateTime() {
        var items = parse("""
                {"items":[{"title":"和供应商对价","startAt":"2026-11-03T14:00","endAt":"2026-11-03T15:30",
                "location":"公司 2 楼","participants":["张三","李四"],"kind":"MEETING",
                "prepare":"带上报价单","sourceQuote":"下周二下午两点对一下价格"}]}
                """);
        assertEquals(1, items.size());
        ItineraryItem it = items.get(0);
        assertEquals("和供应商对价", it.getTitle());
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 0).atZone(ZONE).toInstant(), it.getStartAt());
        assertEquals("张三、李四", it.getParticipants());
        assertFalse(it.isTimeUnknown());
        assertEquals(ItineraryItem.PLANNED, it.getStatus());
    }

    @Test
    @DisplayName("只给日期:排成 09:00 但标「时间待定」,不能让人以为约在早上九点")
    void dateOnlyIsMarkedUnknown() {
        var items = parse("""
                {"items":[{"title":"去仓库看现场","startAt":"2026-11-05","kind":"TRAVEL"}]}
                """);
        assertEquals(1, items.size());
        assertTrue(items.get(0).isTimeUnknown());
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0).atZone(ZONE).toInstant(), items.get(0).getStartAt());
    }

    @Test
    @DisplayName("完全没有时间:不编时间,排在最后")
    void noTimeStaysEmpty() {
        var items = parse("""
                {"items":[{"title":"回头对一下合同条款","startAt":""},
                {"title":"明天上午 10 点开评审","startAt":"2026-11-01T10:00"}]}
                """);
        assertEquals(2, items.size());
        // 没时间的排在最后,而不是被当成"今天"
        ItineraryItem undated = items.stream().filter(i -> i.getTitle().equals("回头对一下合同条款")).findFirst().orElseThrow();
        ItineraryItem dated = items.stream().filter(i -> i.getTitle().contains("评审")).findFirst().orElseThrow();
        assertNull(undated.getStartAt());
        assertTrue(undated.isTimeUnknown());
        assertNotNull(dated.getStartAt());
        assertEquals("回头对一下合同条款", items.get(1).getTitle());
    }

    @Test
    @DisplayName("只给月日:年份跟会议同年;跨年才加一年")
    void monthDayUsesMeetingYear() {
        LocalDateTime d = ItineraryPlanner.parseTime("11月3日", LocalDate.of(2026, 10, 31));
        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0), d);

        // 会议在 12 月 20 日,说「1 月 5 日」肯定是明年
        LocalDateTime next = ItineraryPlanner.parseTime("1月5日", LocalDate.of(2026, 12, 20));
        assertEquals(LocalDateTime.of(2027, 1, 5, 9, 0), next);
    }

    @Test
    @DisplayName("结束时间早于开始时间:按类型补默认时长,不能排出负时长的日程")
    void endBeforeStartIsFixed() {
        var items = parse("""
                {"items":[{"title":"电话会","startAt":"2026-11-02T15:00","endAt":"2026-11-02T14:00","kind":"CALL"}]}
                """);
        assertEquals(1, items.size());
        assertTrue(items.get(0).getEndAt().isAfter(items.get(0).getStartAt()));
    }

    @Test
    @DisplayName("重复条目只留一条:模型常把同一件事说两遍")
    void duplicatesDropped() {
        var items = parse("""
                {"items":[{"title":"和供应商对价","startAt":"2026-11-03T14:00"},
                {"title":"和供应商对价","startAt":"2026-11-03T14:00"}]}
                """);
        assertEquals(1, items.size());
    }

    @Test
    @DisplayName("坏 JSON、空数组、没有 items 键都不抛异常")
    void badJsonIsTolerated() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("{\"items\":[]}").isEmpty());
        assertTrue(parse("{}").isEmpty());
        assertTrue(parse("抱歉,我无法提取").isEmpty());
    }

    @Test
    @DisplayName("被围栏包住或前后有解说的 JSON 也能解析")
    void fencedJsonRecovered() {
        String raw = "好的,这是行程:\n```json\n{\"items\":[{\"title\":\"准备月报\",\"startAt\":\"2026-11-01\"}]}\n```";
        assertEquals(1, parse(raw).size());
    }

    @Test
    @DisplayName("没给 kind 时按标题猜:「出差」是出行,不是开会")
    void kindInferredFromTitle() {
        var items = parse("""
                {"items":[{"title":"去上海出差见客户","startAt":"2026-11-06"},
                {"title":"整理竞品材料","startAt":"2026-11-07"}]}
                """);
        assertEquals(ItineraryItem.TRAVEL, items.get(0).getKind());
        assertEquals(ItineraryItem.PREP, items.get(1).getKind());
    }

    @Test
    @DisplayName("条目数有上限:模型一次吐两百条待办,界面会炸")
    void cappedAtForty() {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 120; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"事项").append(i).append("\",\"startAt\":\"2026-11-0").append(i % 9 + 1)
              .append("T09:00\"}");
        }
        sb.append("]}");
        var parsed = ItineraryPlanner.parse(sb.toString(), ANCHOR, ZONE);
        assertTrue(parsed.items().size() <= 40);
        assertTrue(parsed.dropped() > 0);
    }
}
