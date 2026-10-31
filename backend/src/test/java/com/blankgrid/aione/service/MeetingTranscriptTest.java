package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 原话实录与转写稿解析。
 *
 * <p>原话版是这套功能里唯一"不能错"的东西:它可能被拿去对质、拿去追责。
 * 所以这里钉的不是"写得好看",而是"一个字都没被改过"。
 */
class MeetingTranscriptTest {

    private Meeting meeting() {
        Meeting m = new Meeting();
        m.setTitle("Q4 渠道复盘");
        m.setLocation("无锡 3 楼会议室");
        m.setParticipants("张三,李四");
        m.setStartedAt(Instant.parse("2026-10-31T01:00:00Z"));
        m.setEndedAt(Instant.parse("2026-10-31T02:13:00Z"));
        m.setActiveMs(73 * 60_000L);
        m.setStatus(Meeting.ENDED);
        m.setSource(Meeting.SRC_BROWSER);
        return m;
    }

    @Test
    @DisplayName("超过一小时的会不能显示成 83:45")
    void stampOverOneHour() {
        assertEquals("00:00:03", MeetingTranscript.stamp(3000));
        assertEquals("01:23:45", MeetingTranscript.stamp(5025000));
        assertEquals("00:00:00", MeetingTranscript.stamp(-5));
    }

    @Test
    @DisplayName("原话实录保留每条原始措辞,说话人和时间戳都在")
    void verbatimKeepsEveryWord() {
        List<MeetingSegment> segs = List.of(
                new MeetingSegment(meeting(), 1, "张三", "这个月的退货率是 4.7%,比上个月高了一个点", 3000, MeetingSegment.SPEECH),
                new MeetingSegment(meeting(), 2, "李四", "嗯,主要是华东那批货", 65_000, MeetingSegment.SPEECH));
        String md = MeetingTranscript.verbatim(meeting(), segs);
        assertTrue(md.contains("原话实录"));
        assertTrue(md.contains("退货率是 4.7%"));
        assertTrue(md.contains("张三"));
        assertTrue(md.contains("1 小时 13 分钟") || md.contains("有效 73 分钟"));
        assertTrue(md.startsWith("# Q4 渠道复盘"));
        // 只整理分段,不改写:原文里的口语「嗯」必须还在
        assertTrue(md.contains("嗯,主要是华东那批货"));
    }

    @Test
    @DisplayName("没人标的发言标「未标注」,不能和上一个人接在一起")
    void unknownSpeakerIsExplicit() {
        List<MeetingSegment> segs = List.of(
                new MeetingSegment(meeting(), 1, "", "这句话不知道是谁说的", 1000, MeetingSegment.SPEECH));
        String md = MeetingTranscript.verbatim(meeting(), segs);
        assertTrue(md.contains("未标注"));
    }

    @Test
    @DisplayName("人工速记要标出来 —— 它不是原话")
    void noteIsMarked() {
        MeetingSegment note = new MeetingSegment(meeting(), 1, "我", "记:老板说要先看数据", 1000, MeetingSegment.NOTE);
        assertTrue(MeetingTranscript.verbatim(meeting(), List.of(note)).contains("(速记)"));
    }

    @Test
    @DisplayName("能认三种转写稿格式,认不出说话人就整行当内容")
    void parseThreeFormats() {
        String raw = String.join("\n",
                "[00:01:02] 张三: 这个季度的目标是 800 万",
                "00:02:15 李四：我这边负责渠道",
                "王五：我补充一点",
                "关于预算：我需要再确认一下",
                "",
                "这句没有前缀");
        List<MeetingTranscript.Line> lines = MeetingTranscript.parseLines(raw);
        assertEquals(5, lines.size());

        assertEquals("张三", lines.get(0).speaker());
        assertEquals("这个季度的目标是 800 万", lines.get(0).text());
        assertEquals(62_000, lines.get(0).atMs());

        assertEquals("李四", lines.get(1).speaker());
        assertEquals(135_000, lines.get(1).atMs());

        assertEquals("王五", lines.get(2).speaker());
        assertEquals(-1, lines.get(2).atMs());

        // 「关于预算」不是人名:整行当内容,宁可不标
        assertEquals("", lines.get(3).speaker());
        assertEquals("关于预算：我需要再确认一下", lines.get(3).text());

        assertEquals("", lines.get(4).speaker());
    }

    @Test
    @DisplayName("分块不丢句子:每块拼回去等于原文")
    void chunksLoseNothing() {
        List<MeetingSegment> segs = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            segs.add(new MeetingSegment(meeting(), i, "张三", "第 " + i + " 句发言,内容长度差不多是这样子", i * 1000L, MeetingSegment.SPEECH));
        }
        List<String> chunks = MeetingTranscript.chunks(segs, 300);
        assertTrue(chunks.size() > 1);
        String joined = String.join("", chunks);
        assertEquals(MeetingTranscript.plain(segs), joined);
        chunks.forEach(c -> assertTrue(c.length() <= 600, "单块超长"));
    }

    @Test
    @DisplayName("模型挂了的兜底稿:必须自报是速记版,并给出原因")
    void fallbackDeclaresItself() {
        List<MeetingSegment> segs = List.of(
                new MeetingSegment(meeting(), 1, "张三", "价格就按 87 万定了,后续我来跟进合同", 1000, MeetingSegment.SPEECH),
                new MeetingSegment(meeting(), 2, "李四", "我担心交付周期来不及,下周三前要给客户答复", 2000, MeetingSegment.SPEECH));
        String md = MeetingTranscript.fallbackSummary(meeting(), segs, "余额不足");
        assertTrue(md.contains("规则速记"));
        assertTrue(md.contains("余额不足"));
        assertTrue(md.contains("已定的事"));
        assertTrue(md.contains("像待办的话"));
        assertTrue(md.contains("87 万"));
        // 兜底稿不许冒充"完整纪要"
        assertFalse(md.contains("一句话结论"));
    }
}
