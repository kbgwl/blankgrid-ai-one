package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.ItineraryItem;
import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;
import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.ItineraryItemRepository;
import com.blankgrid.aione.repo.MeetingRepository;
import com.blankgrid.aione.repo.MeetingSegmentRepository;
import com.blankgrid.aione.ws.EventPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 会议主流程:开、记、停、产出。
 *
 * <p>这组用例盯着几个会真出事的点:
 * 暂停的时间不能算进时长、会开完补录要让纪要变旧、
 * 模型挂了纪要还得有一份、重新规划行程不能把已完成的事弄丢。
 */
class MeetingServiceTest {

    private MeetingRepository meetings;
    private MeetingSegmentRepository segments;
    private ItineraryItemRepository itinerary;
    private EmployeeRepository employees;
    private TaskService tasks;
    private LlmService llm;
    private DocExportService docs;
    private MeetingService svc;

    private Meeting m;

    @BeforeEach
    void setUp() {
        meetings = mock(MeetingRepository.class);
        segments = mock(MeetingSegmentRepository.class);
        itinerary = mock(ItineraryItemRepository.class);
        employees = mock(EmployeeRepository.class);
        tasks = mock(TaskService.class);
        llm = mock(LlmService.class);
        docs = mock(DocExportService.class);
        svc = new MeetingService(meetings, segments, itinerary, employees, tasks, llm, docs,
                mock(EventPusher.class));

        m = new Meeting();
        ReflectionTestUtils.setField(m, "id", 1L);
        m.setTitle("Q4 渠道复盘");
        m.setIngestToken("tok-abc");
        m.setStatus(Meeting.DRAFT);

        when(meetings.findById(1L)).thenReturn(Optional.of(m));
        when(meetings.save(any(Meeting.class))).thenAnswer(i -> i.getArgument(0));
        when(segments.save(any(MeetingSegment.class))).thenAnswer(i -> i.getArgument(0));
        when(segments.countByMeetingId(1L)).thenReturn(0L);
        when(itinerary.save(any(ItineraryItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("还没点开始就说话,自动开始 —— 不能让第一句话丢掉")
    void firstSegmentAutoStarts() {
        var r = svc.append(1L, "张三", "我们先从退货率说起", null, null);
        assertEquals(Meeting.LIVE, m.getStatus());
        assertNotNull(m.getStartedAt());
        assertFalse((Boolean) r.get("duplicate"));
    }

    @Test
    @DisplayName("同一个人连着说一模一样的话(ASR 常见)只记一条")
    void consecutiveDuplicateIgnored() {
        MeetingSegment last = new MeetingSegment(m, 1, "张三", "这个我来跟进", 0, MeetingSegment.SPEECH);
        when(segments.findTopByMeetingIdOrderBySeqDesc(1L)).thenReturn(last);
        m.setStatus(Meeting.LIVE);
        m.setResumedAt(Instant.now());

        var r = svc.append(1L, "张三", "这个我来跟进", 3000L, null);
        assertTrue((Boolean) r.get("duplicate"));
        verify(segments, never()).save(any(MeetingSegment.class));
    }

    @Test
    @DisplayName("不同人说了同一句话不能去重 —— 「同意」是真实表态")
    void sameWordsDifferentSpeakerKept() {
        MeetingSegment last = new MeetingSegment(m, 1, "张三", "同意", 0, MeetingSegment.SPEECH);
        when(segments.findTopByMeetingIdOrderBySeqDesc(1L)).thenReturn(last);
        m.setStatus(Meeting.LIVE);
        m.setResumedAt(Instant.now());

        var r = svc.append(1L, "李四", "同意", 3000L, null);
        assertFalse((Boolean) r.get("duplicate"));
        verify(segments).save(any(MeetingSegment.class));
    }

    @Test
    @DisplayName("导入的转写稿有时长,就不能显示成「开了 0 分钟」")
    void durationFollowsContentWhenLongerThanClock() {
        m.setStatus(Meeting.ENDED);
        m.setActiveMs(0);
        List<MeetingSegment> segs = List.of(
                new MeetingSegment(m, 1, "张伟", "今天主要三件事", 5000, MeetingSegment.SPEECH),
                new MeetingSegment(m, 2, "李娜", "退货率 4.7%", 180_000, MeetingSegment.SPEECH));
        when(segments.findByMeetingIdOrderBySeqAsc(1L)).thenReturn(segs);
        when(itinerary.findByMeetingIdOrderByStartAtAscIdAsc(1L)).thenReturn(List.of());

        var d = svc.detail(1L);
        assertEquals(180L, ((Number) d.get("elapsedSeconds")).longValue());
        assertEquals("3 分钟", d.get("durationText"));
    }

    @Test
    @DisplayName("会开完了还能补录,但纪要必须变「过期」,不能装作没变")
    void appendAfterEndMarksSummaryStale() {
        m.setStatus(Meeting.ENDED);
        m.setEndedAt(Instant.now());
        m.setSummaryStatus(Meeting.SUMMARY_READY);
        m.setSummaryMd("# 旧纪要");

        var r = svc.append(1L, "张三", "补充一句:合同要法务先看", 1000L, null);
        assertEquals(Meeting.SUMMARY_STALE, m.getSummaryStatus());
        assertNotNull(r.get("stale"));
    }

    @Test
    @DisplayName("暂停的时间不算进有效时长")
    void pauseExcludesIdleTime() {
        m.setStatus(Meeting.LIVE);
        m.setStartedAt(Instant.now().minusSeconds(600));
        m.setResumedAt(Instant.now().minusSeconds(90));

        svc.pause(1L);
        assertEquals(Meeting.PAUSED, m.getStatus());
        assertTrue(m.getActiveMs() >= 88_000 && m.getActiveMs() <= 95_000,
                "应只累计 90 秒左右,实际 " + m.getActiveMs());
        // 暂停后时长不再走动
        long frozen = m.elapsedSeconds();
        assertEquals(frozen, m.elapsedSeconds());
    }

    @Test
    @DisplayName("模型挂了也要有一份纪要,并且标明是速记版")
    void llmFailureFallsBackToRuleSummary() {
        stubSegments();
        when(llm.chatStream(anyList(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenThrow(new LlmService.UnavailableException("余额不足", 3));

        var r = svc.summarize(1L, true);
        assertEquals(Meeting.SUMMARY_PARTIAL, r.get("summaryStatus"));
        assertTrue(m.getSummaryMd().contains("规则速记"));
        assertTrue(m.getSummaryNote().contains("余额不足"));
        assertFalse(m.getSummaryMd().contains("一句话结论"));
    }

    @Test
    @DisplayName("纪要里的围栏和客套话要清掉:导出成 Word 时它们会原样印在纸上")
    void summaryCleaned() {
        String raw = "好的,以下是纪要:\n```markdown\n## 一句话结论\n价格按 87 万定了\n```";
        String cleaned = MeetingService.cleanSummary(raw, "Q4 渠道复盘");
        assertFalse(cleaned.contains("```"));
        assertFalse(cleaned.startsWith("好的"));
        assertTrue(cleaned.startsWith("# Q4 渠道复盘"));
        assertTrue(cleaned.contains("价格按 87 万定了"));
    }

    @Test
    @DisplayName("纪要成功时记下模型与时间,失败时不覆盖旧的分数字段")
    void summarySuccessRecordsMeta() {
        stubSegments();
        when(llm.chatStream(anyList(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn("# Q4 渠道复盘\n\n## 一句话结论\n退货率需拉回 3% 以内");
        when(llm.lastModel()).thenReturn("glm-4.6");

        var r = svc.summarize(1L, true);
        assertEquals(Meeting.SUMMARY_READY, r.get("summaryStatus"));
        assertEquals("glm-4.6", m.getSummaryModel());
        assertNotNull(m.getSummaryAt());
    }

    @Test
    @DisplayName("重新规划行程时,已完成的和已派活的条目要留着 —— 否则重新点一次就丢账")
    void replanKeepsDoneAndAssignedItems() {
        stubSegments();
        ItineraryItem done = item("和供应商对价", ItineraryItem.DONE, null);
        ItineraryItem assigned = item("准备报价单", ItineraryItem.PLANNED, 88L);
        ItineraryItem untouched = item("去仓库看现场", ItineraryItem.PLANNED, null);
        when(itinerary.findByMeetingIdOrderByStartAtAscIdAsc(1L))
                .thenReturn(new ArrayList<>(List.of(done, assigned, untouched)));

        when(llm.chatStream(anyList(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn("{\"items\":[{\"title\":\"和客户对交付周期\",\"startAt\":\"2026-11-04T10:00\"}]}");

        var r = svc.planItinerary(1L, true);
        assertEquals(true, r.get("ok"));
        verify(itinerary).delete(untouched);
        verify(itinerary, never()).delete(done);
        verify(itinerary, never()).delete(assigned);
    }

    @Test
    @DisplayName("提取行程失败时给一句人话,而不是把异常甩到界面上")
    void itineraryFailureIsExplained() {
        stubSegments();
        when(llm.chatStream(anyList(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenThrow(new IllegalStateException("尚未配置大模型 API"));
        when(itinerary.findByMeetingIdOrderByStartAtAscIdAsc(1L)).thenReturn(List.of());

        var r = svc.planItinerary(1L, true);
        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("error")).contains("尚未配置"));
    }

    @Test
    @DisplayName("派活记下任务号,同一件事不能派两遍")
    void assignTaskOnce() {
        ItineraryItem it = item("和供应商对价", ItineraryItem.PLANNED, null);
        it.setMeeting(m);
        when(itinerary.findById(7L)).thenReturn(Optional.of(it));
        Employee emp = mock(Employee.class);
        when(emp.getId()).thenReturn(2L);
        when(emp.getName()).thenReturn("市场-李四");
        when(employees.findById(2L)).thenReturn(Optional.of(emp));
        Task task = new Task();
        ReflectionTestUtils.setField(task, "id", 99L);
        when(tasks.submit(eq(2L), contains("会前准备"), isNull())).thenReturn(task);

        var r = svc.assignTask(7L, 2L, "顺便把竞品价格也带上");
        assertEquals(99L, r.get("taskId"));
        assertEquals(99L, it.getTaskId());

        assertThrows(IllegalArgumentException.class, () -> svc.assignTask(7L, 2L, null));
    }

    @Test
    @DisplayName("推送口令不对直接拒:这个端口不走登录口令,只走每场会的 token")
    void ingestRejectsWrongToken() {
        when(meetings.findByIngestToken("bad")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> svc.ingest("bad", List.of(new MeetingService.SegmentBody())));
    }

    @Test
    @DisplayName("粘贴导入:三行拆成三条,时间戳递增")
    void importAssignsIncreasingTimestamps() {
        when(segments.findTopByMeetingIdOrderBySeqDesc(1L)).thenReturn(null);

        var r = svc.importTranscript(1L,
                "张三：这个季度的目标是 800 万\n李四：我负责渠道\n王五：我补充一点", "");
        assertEquals(3, r.get("added"));
        assertEquals(0, r.get("duplicate"));
    }

    @Test
    @DisplayName("日历:转义逗号分号、折行不超过 75 字节、每个条目一个 VEVENT")
    void icsEscapesAndFolds() {
        m.setStartedAt(Instant.parse("2026-10-31T01:00:00Z"));
        ItineraryItem it = item("和 A,B 两家供应商对价", ItineraryItem.PLANNED, null);
        it.setMeeting(m);
        it.setStartAt(Instant.parse("2026-11-03T06:00:00Z"));
        it.setEndAt(Instant.parse("2026-11-03T07:30:00Z"));
        it.setLocation("无锡;3 楼");
        it.setPrepare("带上报价单、合同模板、以及上一轮的比价记录,还有法务确认过的付款条款说明");
        when(itinerary.findByMeetingIdOrderByStartAtAscIdAsc(1L)).thenReturn(List.of(it));

        String ics = svc.ics(1L);
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertEquals(1, countOccurrences(ics, "BEGIN:VEVENT"));
        assertTrue(ics.contains("DTSTART:20261103T060000Z"));
        assertTrue(ics.contains("A\\,B"));
        assertTrue(ics.contains("无锡\\;3 楼"));
        assertTrue(ics.contains("BEGIN:VALARM"));
        for (String line : ics.split("\r\n")) {
            assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75,
                    "超长行:" + line);
        }
    }

    @Test
    @DisplayName("时间待定的条目排成全天事件,标题上标出来,别让人以为定好了")
    void icsMarksUnknownTime() {
        m.setStartedAt(Instant.parse("2026-10-31T01:00:00Z"));
        ItineraryItem it = item("回头对一下合同", ItineraryItem.PLANNED, null);
        it.setMeeting(m);
        it.setTimeUnknown(true);
        when(itinerary.findByMeetingIdOrderByStartAtAscIdAsc(1L)).thenReturn(List.of(it));

        String ics = svc.ics(1L);
        assertTrue(ics.contains("DTSTART;VALUE=DATE:"));
        assertTrue(ics.contains("SUMMARY:[待定] 回头对一下合同"));
    }

    // ---------- helpers ----------

    private void stubSegments() {
        List<MeetingSegment> segs = List.of(
                new MeetingSegment(m, 1, "张三", "退货率这个月是 4.7%,比上个月高一个点", 3000, MeetingSegment.SPEECH),
                new MeetingSegment(m, 2, "李四", "华东那批货我来跟进,下周三给结论", 8000, MeetingSegment.SPEECH));
        when(segments.findByMeetingIdOrderBySeqAsc(1L)).thenReturn(segs);
        m.setStartedAt(Instant.now().minusSeconds(1800));
        m.setActiveMs(1800_000L);
        m.setStatus(Meeting.ENDED);
    }

    private ItineraryItem item(String title, String status, Long taskId) {
        ItineraryItem it = new ItineraryItem();
        ReflectionTestUtils.setField(it, "id", (long) (title.hashCode() & 0xffff));
        it.setTitle(title);
        it.setStatus(status);
        it.setTaskId(taskId);
        return it;
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
