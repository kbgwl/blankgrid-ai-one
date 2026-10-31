package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实时信号提取的判定规则。
 *
 * <p>这一层开会时一直在跑,判错一次就是一次误报 —— 界面上会冒出
 * "这是待办"但根本不是的话。所以每条规则都拿真实会话说的话钉住。
 */
class MeetingSignalsTest {

    private MeetingSegment seg(int seq, String speaker, String text, long atMs) {
        MeetingSegment s = new MeetingSegment(new Meeting(), seq, speaker, text, atMs, MeetingSegment.SPEECH);
        return s;
    }

    @Test
    @DisplayName("「定了」优先于「安排」:同一句里既有决定又有动作,它先是决议")
    void decisionBeatsAction() {
        var hits = MeetingSignals.scan("价格就按 87 万定下来,后面安排法务出合同");
        assertEquals(1, hits.size());
        assertEquals(MeetingSignals.DECISION, hits.get(0).kind());
    }

    @Test
    @DisplayName("「不同意」不能被算成同意 —— 这是最要命的一种误判")
    void disagreementIsNotDecision() {
        var hits = MeetingSignals.scan("我不同意下周就上线");
        assertTrue(hits.stream().noneMatch(h -> MeetingSignals.DECISION.equals(h.kind())));
    }

    @Test
    @DisplayName("带单位数量才算数字:光有「三个」不算,「30 万」算")
    void numberNeedsUnit() {
        var money = MeetingSignals.scan("这次预算大概 30 万");
        assertEquals(MeetingSignals.NUMBER, money.get(0).kind());

        var when = MeetingSignals.scan("下周三之前给答复");
        assertEquals(MeetingSignals.TIME, when.get(0).kind());

        // 「三个」没有量词单位,不该被当成关键数字
        assertTrue(MeetingSignals.scan("我讲三个点").isEmpty());
    }

    @Test
    @DisplayName("问句进「悬而未决」,而不是被当成待办派出去")
    void questionIsOpen() {
        var hits = MeetingSignals.scan("这个需求到底要不要做");
        assertEquals(MeetingSignals.OPEN, hits.get(0).kind());
    }

    @Test
    @DisplayName("同一句话重复出现只记一条:批量导入的稿子常有重复行")
    void duplicateQuoteCountedOnce() {
        List<MeetingSegment> segs = List.of(
                seg(1, "张三", "我来整理客户名单", 1000),
                seg(2, "李四", "我来整理客户名单", 2000));
        var hits = MeetingSignals.collect(segs);
        assertEquals(1, hits.size());
        assertEquals("张三", hits.get(0).get("speaker"));
    }

    @Test
    @DisplayName("太短的应答不提信号:「嗯」「对」不是待办")
    void shortAckIgnored() {
        assertTrue(MeetingSignals.scan("对").isEmpty());
        assertTrue(MeetingSignals.scan("好,可以").isEmpty());
    }

    @Test
    @DisplayName("系统插入的暂停标记不进发言统计")
    void systemSegmentsExcludedFromStats() {
        List<MeetingSegment> segs = List.of(
                seg(1, "张三", "我先说一下背景情况", 1000),
                new MeetingSegment(new Meeting(), 2, "系统", "会议暂停", 2000, MeetingSegment.SYSTEM),
                seg(3, "李四", "背景我这边补充两点", 3000));
        var stats = MeetingSignals.speakerStats(segs);
        assertEquals(2, stats.size());
        assertTrue(stats.stream().noneMatch(s -> "系统".equals(s.speaker())));
    }

    @Test
    @DisplayName("一言堂:一个人说了六成以上,提示主持人")
    void dominantSpeakerHint() {
        Meeting m = new Meeting();
        m.setStatus(Meeting.LIVE);
        m.setResumedAt(Instant.now());
        String long1 = "我先把这次合作的整体背景和三个阶段的目标讲一遍,包括我们能做到什么程度、"
                + "哪些环节需要外部配合、预算大概落在什么区间、以及如果按最保守的方式推进会有什么后果,"
                + "这些都说清楚之后我们再讨论具体分工和时间表,不然讨论起来容易跑偏,"
                + "上一次就是因为背景没对齐,开了两个小时等于白开,最后还要重新约时间。";
        List<MeetingSegment> segs = List.of(
                seg(1, "张三", long1, 1000),
                seg(2, "张三", long1, 2000),
                seg(3, "张三", long1, 3000),
                seg(4, "张三", long1, 4000),
                seg(5, "李四", "明白", 5000),
                seg(6, "王五", "收到", 6000));
        var tips = MeetingSignals.insights(m, segs, Instant.now());
        assertTrue(tips.stream().anyMatch(t -> t.contains("张三") && t.contains("%")), tips.toString());
    }

    @Test
    @DisplayName("待办多而决议为零:提醒「这些活最后谁认」")
    void actionsWithoutDecisions() {
        Meeting m = new Meeting();
        m.setStatus(Meeting.PAUSED);
        List<MeetingSegment> segs = List.of(
                seg(1, "张三", "我来整理一份客户名单", 1000),
                seg(2, "李四", "我这边负责对接物流", 2000),
                seg(3, "王五", "我负责下周去仓库看现场", 3000));
        var tips = MeetingSignals.insights(m, segs, Instant.now());
        assertTrue(tips.stream().anyMatch(t -> t.contains("一条决议都没有")), tips.toString());
    }

    @Test
    @DisplayName("冷场五分钟以上才提示,刚安静两分钟不用打扰")
    void silenceThreshold() {
        Meeting m = new Meeting();
        m.setStatus(Meeting.LIVE);
        m.setResumedAt(Instant.now());
        MeetingSegment last = seg(1, "张三", "我先看一下材料", 0);
        last.setAt(Instant.now().minusSeconds(120));
        assertTrue(MeetingSignals.insights(m, List.of(last), Instant.now()).stream()
                .noneMatch(t -> t.contains("没人出声")));

        last.setAt(Instant.now().minusSeconds(420));
        assertTrue(MeetingSignals.insights(m, List.of(last), Instant.now()).stream()
                .anyMatch(t -> t.contains("没人出声")));
    }
}
