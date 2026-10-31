package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.ItineraryItem;
import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.ItineraryItemRepository;
import com.blankgrid.aione.repo.MeetingRepository;
import com.blankgrid.aione.repo.MeetingSegmentRepository;
import com.blankgrid.aione.ws.EventPusher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会议:一边开,一边长出纪要、原话和行程。
 *
 * <p>三件事分开做、分开存,是因为它们的可信度不一样:
 * <ul>
 *   <li><b>原话</b>是事实,由片段拼装,不经过模型。它可以被追责。</li>
 *   <li><b>纪要</b>是判断,由模型归纳,可能错、可能漏。所以它必须能一键跳回原话。</li>
 *   <li><b>行程</b>是承诺,从纪要里抽出来,带原话依据;时间不实的地方要标明。</li>
 * </ul>
 * 把它们揉成一份"会议报告",出问题时就没法回答"这句到底是谁说的"。
 */
@Service
public class MeetingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingService.class);

    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter CN_DT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");

    /** 一次喂给模型的实录上限。再长就先分段压要点 */
    private static final int TRANSCRIPT_BUDGET = 14000;
    private static final int CHUNK = 10000;

    private final MeetingRepository meetings;
    private final MeetingSegmentRepository segments;
    private final ItineraryItemRepository itinerary;
    private final EmployeeRepository employees;
    private final TaskService tasks;
    private final LlmService llm;
    private final DocExportService docs;
    private final EventPusher pusher;

    private final SecureRandom rnd = new SecureRandom();

    public MeetingService(MeetingRepository meetings, MeetingSegmentRepository segments,
                          ItineraryItemRepository itinerary, EmployeeRepository employees,
                          TaskService tasks, LlmService llm, DocExportService docs, EventPusher pusher) {
        this.meetings = meetings;
        this.segments = segments;
        this.itinerary = itinerary;
        this.employees = employees;
        this.tasks = tasks;
        this.llm = llm;
        this.docs = docs;
        this.pusher = pusher;
    }

    // ==================== 生命周期 ====================

    public static class CreateBody {
        public String title;
        public String agenda;
        public String location;
        public String participants;
        public String scheduledAt;
        public String owner;
    }

    public Meeting create(CreateBody b) {
        if (b == null || b.title == null || b.title.isBlank()) {
            throw new IllegalArgumentException("会议标题不能为空");
        }
        Meeting m = new Meeting();
        m.setTitle(b.title.trim());
        m.setAgenda(b.agenda == null ? "" : b.agenda.trim());
        m.setLocation(b.location == null ? "" : b.location.trim());
        m.setParticipants(normalizeParticipants(b.participants));
        m.setOwner(b.owner == null ? "" : b.owner.trim());
        if (b.scheduledAt != null && !b.scheduledAt.isBlank()) {
            m.setScheduledAt(parseInstant(b.scheduledAt));
        }
        m.setIngestToken(newToken());
        m.setStatus(Meeting.DRAFT);
        m.setSummaryStatus(Meeting.SUMMARY_NONE);
        m = meetings.save(m);
        pusher.push("meeting-state", Map.of("meetingId", m.getId(), "status", m.getStatus()));
        return m;
    }

    public Meeting get(Long id) {
        return meetings.findById(id).orElseThrow(() -> new IllegalArgumentException("会议不存在: " + id));
    }

    /**
     * 删掉一场会。说得清的是"误建的空会",所以连带片段与行程一起清 ——
     * 留着片段而会议没了,时间轴上的东西就成了没人认领的孤儿。
     */
    @Transactional
    public void delete(Long id) {
        Meeting m = get(id);
        for (ItineraryItem it : itinerary.findByMeetingIdOrderByStartAtAscIdAsc(id)) {
            itinerary.delete(it);
        }
        segments.deleteByMeetingId(id);
        meetings.delete(m);
        pusher.push("meeting-state", Map.of("meetingId", id, "status", "DELETED"));
    }

    public Meeting start(Long id) {
        Meeting m = get(id);
        if (m.isLive()) return m;
        if (m.isEnded()) throw new IllegalArgumentException("会议已结束,不能重新开始 —— 要接着聊请新建一场");
        m.setStatus(Meeting.LIVE);
        m.setResumedAt(Instant.now());
        if (m.getStartedAt() == null) m.setStartedAt(Instant.now());
        m = meetings.save(m);
        pushState(m);
        return m;
    }

    public Meeting pause(Long id) {
        Meeting m = get(id);
        if (!m.isLive()) return m;
        m.setActiveMs(m.getActiveMs() + Math.max(0, Instant.now().toEpochMilli() - m.getResumedAt().toEpochMilli()));
        m.setResumedAt(null);
        m.setStatus(Meeting.PAUSED);
        m = meetings.save(m);
        appendSystem(m, "会议暂停");
        pushState(m);
        return m;
    }

    public Meeting resume(Long id) {
        Meeting m = get(id);
        if (m.isEnded()) throw new IllegalArgumentException("会议已结束,不能继续");
        if (m.isLive()) return m;
        m.setStatus(Meeting.LIVE);
        m.setResumedAt(Instant.now());
        if (m.getStartedAt() == null) m.setStartedAt(Instant.now());
        m = meetings.save(m);
        appendSystem(m, "会议继续");
        pushState(m);
        return m;
    }

    public Meeting end(Long id) {
        Meeting m = get(id);
        if (m.isEnded()) return m;
        if (m.isLive() && m.getResumedAt() != null) {
            m.setActiveMs(m.getActiveMs() + Math.max(0, Instant.now().toEpochMilli() - m.getResumedAt().toEpochMilli()));
        }
        m.setResumedAt(null);
        m.setStatus(Meeting.ENDED);
        m.setEndedAt(Instant.now());
        m = meetings.save(m);
        appendSystem(m, "会议结束");
        pushState(m);
        return m;
    }

    private void appendSystem(Meeting m, String text) {
        MeetingSegment last = segments.findTopByMeetingIdOrderBySeqDesc(m.getId());
        int seq = last == null ? 1 : last.getSeq() + 1;
        // 标记的时间点要落在时间轴末尾:导入的稿子有三分钟内容、时钟却只走了一秒,
        // 按时钟写就会把「会议结束」插在 00:00:00,读起来像会议倒着开
        long at = Math.max(m.elapsedSeconds() * 1000, last == null ? 0 : last.getAtMs());
        MeetingSegment s = new MeetingSegment(m, seq, "系统", text, at, MeetingSegment.SYSTEM);
        segments.save(s);
    }

    private void pushState(Meeting m) {
        pusher.push("meeting-state", Map.of(
                "meetingId", m.getId(), "status", m.getStatus(),
                "elapsedSeconds", m.elapsedSeconds()));
    }

    // ==================== 片段 ====================

    public static class SegmentBody {
        public String speaker;
        public String text;
        /** 相对会议开始的毫秒;不传就按当前有效时长 */
        public Long atMs;
        public String kind;
    }

    /**
     * 追加一条发言。
     *
     * <p>去重规则只挡"同一个人连着说了两遍一模一样的话",不挡"两个人说了同样的话" ——
     * 后者在会议里是会发生的(「同意」「对」),拦掉就把真实的表态弄丢了。
     */
    @Transactional
    public Map<String, Object> append(Long meetingId, String speaker, String text, Long atMs, String kind) {
        Meeting m = get(meetingId);
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("内容不能为空");
        if (clean.length() > 8000) clean = clean.substring(0, 8000);
        boolean wasDraft = Meeting.DRAFT.equals(m.getStatus());
        if (wasDraft) m = start(meetingId);

        MeetingSegment last = segments.findTopByMeetingIdOrderBySeqDesc(meetingId);
        if (last != null && clean.equals(last.getText())
                && nz(speaker).equals(nz(last.getSpeaker()))
                && Math.abs((atMs == null ? m.elapsedSeconds() * 1000 : atMs) - last.getAtMs()) < 60_000) {
            Map<String, Object> dup = baseCounts(m);
            dup.put("duplicate", true);
            return dup;
        }

        int seq = last == null ? 1 : last.getSeq() + 1;
        long off = atMs != null && atMs >= 0 ? atMs : m.elapsedSeconds() * 1000;
        MeetingSegment s = new MeetingSegment(m, seq, speaker, clean, off, kind == null ? MeetingSegment.SPEECH : kind);
        s = segments.save(s);
        if (m.isEnded() && !Meeting.SUMMARY_NONE.equals(m.getSummaryStatus())) {
            m.setSummaryStatus(Meeting.SUMMARY_STALE);
            meetings.save(m);
        }

        List<Map<String, Object>> hits = MeetingSignals.collect(List.of(s));
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("meetingId", m.getId());
        evt.put("segment", segmentMap(s));
        evt.put("counts", baseCounts(m));
        evt.put("signals", hits);
        pusher.push("meeting-segment", evt);

        Map<String, Object> out = baseCounts(m);
        out.put("duplicate", false);
        out.put("segment", segmentMap(s));
        out.put("signals", hits);
        if (m.isEnded()) out.put("stale", "会议已结束,还是记下来了 —— 记得重新生成纪要与行程");
        return out;
    }

    private Map<String, Object> baseCounts(Meeting m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meetingId", m.getId());
        out.put("status", m.getStatus());
        out.put("elapsedSeconds", m.elapsedSeconds());
        out.put("segments", segments.countByMeetingId(m.getId()));
        return out;
    }

    /**
     * 批量导入。一段转写稿粘进来,按行拆成片段。
     *
     * <p>能认三种行: {@code [00:01:02] 张三: 内容}、{@code 00:01:02 张三：内容}、
     * {@code 张三：内容}。认不出说话人就整行当内容,不猜 —— 猜错人的代价比"未标注"大。
     */
    @Transactional
    public Map<String, Object> importTranscript(Long meetingId, String transcript, String defaultSpeaker) {
        if (transcript == null || transcript.isBlank()) throw new IllegalArgumentException("转写稿是空的");
        Meeting m = get(meetingId);
        if (Meeting.DRAFT.equals(m.getStatus())) m = start(meetingId);
        MeetingSegment last = segments.findTopByMeetingIdOrderBySeqDesc(meetingId);
        // 没有时间戳的行按"一句话约几秒"往后推,给出一条能看的顺序;
        // 全部压在同一秒里,原话实录就成了一坨,也没法回头定位
        long cursor = last == null ? 0 : last.getAtMs() + 5000;
        int added = 0, dup = 0;
        for (MeetingTranscript.Line line : MeetingTranscript.parseLines(transcript)) {
            long at = line.atMs();
            if (at < 0) {
                at = cursor;
                cursor += 3000 + Math.min(12000, line.text().length() * 180L);
            }
            Map<String, Object> r = append(meetingId, line.speaker().isBlank() ? nz(defaultSpeaker) : line.speaker(),
                    line.text(), at, MeetingSegment.SPEECH);
            if (Boolean.TRUE.equals(r.get("duplicate"))) dup++; else added++;
        }
        meetings.findById(meetingId).ifPresent(x -> {
            x.setSource(Meeting.SRC_IMPORT);
            meetings.save(x);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("duplicate", dup);
        out.put("segments", segments.countByMeetingId(meetingId));
        return out;
    }

    /** 外部 ASR 推送:只认这一场的 ingestToken,不认主口令 */
    @Transactional
    public int ingest(String token, List<SegmentBody> body) {
        Meeting m = meetings.findByIngestToken(token)
                .orElseThrow(() -> new IllegalArgumentException("推送口令不对"));
        if (body == null || body.isEmpty()) return 0;
        if (Meeting.ENDED.equals(m.getStatus())) {
            throw new IllegalArgumentException("会议已结束,不再接收推送");
        }
        if (Meeting.DRAFT.equals(m.getStatus())) start(m.getId());
        int n = 0;
        for (SegmentBody s : body) {
            if (s == null || s.text == null || s.text.isBlank()) continue;
            append(m.getId(), s.speaker, s.text, s.atMs, s.kind);
            n++;
        }
        meetings.findById(m.getId()).ifPresent(x -> {
            if (!Meeting.SRC_PUSH.equals(x.getSource())) { x.setSource(Meeting.SRC_PUSH); meetings.save(x); }
        });
        return n;
    }

    // ==================== 视图 ====================

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Meeting m : meetings.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("title", m.getTitle());
            row.put("status", m.getStatus());
            row.put("source", m.getSource());
            row.put("location", m.getLocation());
            row.put("participants", m.getParticipants());
            row.put("scheduledAt", m.getScheduledAt());
            row.put("startedAt", m.getStartedAt());
            row.put("endedAt", m.getEndedAt());
            MeetingSegment lastSeg = segments.findTopByMeetingIdOrderByAtMsDesc(m.getId());
            row.put("elapsedSeconds", Math.max(m.elapsedSeconds(),
                    lastSeg == null ? 0 : lastSeg.getAtMs() / 1000));
            row.put("segments", segments.countByMeetingId(m.getId()));
            row.put("summaryStatus", m.getSummaryStatus());
            row.put("summaryAt", m.getSummaryAt());
            row.put("itineraryCount", itinerary.findByMeetingIdOrderByStartAtAscIdAsc(m.getId()).size());
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> detail(Long id) {
        Meeting m = get(id);
        List<MeetingSegment> segs = segments.findByMeetingIdOrderBySeqAsc(id);
        List<ItineraryItem> items = itinerary.findByMeetingIdOrderByStartAtAscIdAsc(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("title", m.getTitle());
        out.put("agenda", m.getAgenda());
        out.put("location", m.getLocation());
        out.put("participants", m.getParticipants());
        out.put("owner", m.getOwner());
        out.put("status", m.getStatus());
        out.put("source", m.getSource());
        out.put("scheduledAt", m.getScheduledAt());
        out.put("startedAt", m.getStartedAt());
        out.put("endedAt", m.getEndedAt());
        long sec = MeetingTranscript.effectiveSeconds(m, segs);
        out.put("elapsedSeconds", sec);
        out.put("durationText", MeetingTranscript.humanDuration(sec));
        out.put("summaryMd", m.getSummaryMd());
        out.put("summaryStatus", m.getSummaryStatus());
        out.put("summaryAt", m.getSummaryAt());
        out.put("summaryModel", m.getSummaryModel());
        out.put("summaryNote", m.getSummaryNote());
        out.put("ingestToken", m.getIngestToken());
        out.put("createdAt", m.getCreatedAt());

        out.put("segments", segs.stream().map(this::segmentMap).toList());
        out.put("speakerStats", MeetingSignals.speakerStats(segs));
        out.put("signals", MeetingSignals.collect(segs));
        out.put("insights", MeetingSignals.insights(m, segs, Instant.now()));
        out.put("itinerary", items);
        return out;
    }

    private Map<String, Object> segmentMap(MeetingSegment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("seq", s.getSeq());
        m.put("speaker", s.getSpeaker());
        m.put("text", s.getText());
        m.put("atMs", s.getAtMs());
        m.put("stamp", MeetingTranscript.stamp(s.getAtMs()));
        m.put("at", s.getAt());
        m.put("kind", s.getKind());
        m.put("marked", s.isMarked());
        return m;
    }

    public MeetingSegment toggleMark(Long segmentId) {
        MeetingSegment s = segments.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("片段不存在: " + segmentId));
        s.setMarked(!s.isMarked());
        return segments.save(s);
    }

    public String verbatim(Long id) {
        Meeting m = get(id);
        return MeetingTranscript.verbatim(m, segments.findByMeetingIdOrderBySeqAsc(id));
    }

    // ==================== 总结版 ====================

    private static final String SUMMARY_SYSTEM = """
            你是公司创始人的会议记录员。你交的不是"会议过程",而是"开完会他必须知道的事"。
            硬规则:
            1. 只用实录里出现的信息。没提到的写「实录未提及」,不许推测、不许补常识、不许润色成"达成了广泛共识"。
            2. 数字、金额、日期、人名原样保留,不换算、不四舍五入、不"约"。
            3. 每条待办要落到人;听不出责任人写「责任人待确认」,不要瞎分给在场的人。
            4. 没有结论就写"没有结论",不要为了好看编一个。
            5. 输出 Markdown,小标题严格照下面给的写,不要增删、不要改字。
            """;

    private static final String SUMMARY_SECTIONS = """
            请严格按这些二级标题输出(一级标题写会议主题):
            # 会议主题
            ## 一句话结论
            ## 议题与结论
            ## 已定的事
            ## 待办(负责人 / 时间)
            ## 分歧与风险
            ## 需要老板拍板
            ## 实录里的疑问(没结论的)
            """;

    /**
     * 生成总结版。
     *
     * <p>模型不可用时不抛异常 —— 会已经开完了,创始人此刻要的是一份能看的东西,
     * 不是一句"服务不可用"。降级成规则速记,并在正文第一行说明原因。
     */
    @Transactional
    public Map<String, Object> summarize(Long id, boolean force) {
        Meeting m = get(id);
        List<MeetingSegment> segs = segments.findByMeetingIdOrderBySeqAsc(id).stream()
                .filter(s -> !MeetingSegment.SYSTEM.equals(s.getKind())).toList();
        if (segs.isEmpty()) throw new IllegalArgumentException("这场会还没有任何发言,先把转写稿导进来或开着录音");
        if (!force && Meeting.SUMMARY_READY.equals(m.getSummaryStatus()) && m.getSummaryMd() != null) {
            return Map.of("ok", true, "skipped", true, "summaryStatus", m.getSummaryStatus());
        }
        String transcript = MeetingTranscript.plain(segs);
        String model = "";
        try {
            String body;
            if (transcript.length() <= TRANSCRIPT_BUDGET) {
                body = transcript;
            } else {
                // 长会先分段压要点,再合成 —— 直接塞会超上限,模型只看开头结尾
                List<String> blocks = MeetingTranscript.chunks(segs, CHUNK);
                StringBuilder notes = new StringBuilder();
                for (int i = 0; i < blocks.size(); i++) {
                    String part = llm.chatStream(List.of(
                            new LlmService.ChatMessage("system",
                                    "把这段会议实录压成要点。只保留:做过的决定、谁认领了什么活、出现的数字/日期/人名、"
                                            + "没有结论的争论。不要润色,不要加评论,300 字以内。"),
                            new LlmService.ChatMessage("user",
                                    "第 " + (i + 1) + "/" + blocks.size() + " 段:\n" + blocks.get(i))),
                            null, null, null, false, 1200);
                    notes.append("【第").append(i + 1).append("段要点】\n").append(nz(part).trim()).append("\n\n");
                }
                body = "以下是同一场会议分段整理出的要点(按时间顺序):\n\n" + notes
                        + "\n需要补细节时,可参照实录开头与结尾:\n"
                        + headTail(transcript, 3000, 3000);
            }
            String user = metaBlock(m, segs) + "\n" + SUMMARY_SECTIONS + "\n实录:\n" + body;
            String md = llm.chatStream(List.of(
                    new LlmService.ChatMessage("system", SUMMARY_SYSTEM),
                    new LlmService.ChatMessage("user", user)), null, null, null, false, 4000);
            model = nz(llm.lastModel());
            md = cleanSummary(md, m.getTitle());
            m.setSummaryMd(md);
            m.setSummaryStatus(Meeting.SUMMARY_READY);
            m.setSummaryNote("");
            m.setSummaryAt(Instant.now());
            m.setSummaryModel(model);
            meetings.save(m);
            pusher.push("meeting-summary", Map.of("meetingId", id, "summaryStatus", Meeting.SUMMARY_READY));
            return Map.of("ok", true, "summaryStatus", Meeting.SUMMARY_READY, "summaryMd", md, "model", model);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("会议 {} 纪要生成失败,降级为规则速记:{}", id, reason);
            String md = MeetingTranscript.fallbackSummary(m, segs, reason);
            m.setSummaryMd(md);
            m.setSummaryStatus(Meeting.SUMMARY_PARTIAL);
            m.setSummaryNote(reason);
            m.setSummaryAt(Instant.now());
            meetings.save(m);
            pusher.push("meeting-summary", Map.of("meetingId", id, "summaryStatus", Meeting.SUMMARY_PARTIAL));
            return Map.of("ok", true, "summaryStatus", Meeting.SUMMARY_PARTIAL,
                    "summaryMd", md, "note", reason);
        }
    }

    /** 模型爱把整篇包在围栏里,或者开头来一句"好的,以下是纪要" */
    static String cleanSummary(String raw, String title) {
        String s = nz(raw).trim();
        // 围栏常常不在开头(前面还有一句客套),所以按行删,而不是只删首尾
        s = s.replaceAll("(?m)^\\s*```[a-zA-Z]*\\s*$", "").trim();
        // 去掉第一行"好的/以下是"这类客套
        String[] lines = s.split("\n", 2);
        if (lines.length == 2 && !lines[0].startsWith("#")
                && (lines[0].contains("以下是") || lines[0].startsWith("好的") || lines[0].endsWith(":"))) {
            s = lines[1].trim();
        }
        // 注意是"一级标题":以 ## 开头的算二级,仍然要补一个会议标题,
        // 否则导出的 Word 里整篇没有标题,目录也是空的
        if (!s.matches("(?s)^#(?!#).*")) {
            s = "# " + nz(title) + "\n\n" + s;
        }
        return s;
    }

    private static String metaBlock(Meeting m, List<MeetingSegment> segs) {
        StringBuilder sb = new StringBuilder("会议信息:\n");
        sb.append("- 主题:").append(nz(m.getTitle())).append("\n");
        if (MeetingTranscript.notBlank(m.getLocation())) sb.append("- 地点:").append(m.getLocation()).append("\n");
        if (MeetingTranscript.notBlank(m.getParticipants())) sb.append("- 参会人:").append(m.getParticipants()).append("\n");
        if (m.getStartedAt() != null) {
            sb.append("- 时间:").append(CN_DT.withZone(ZoneId.systemDefault()).format(m.getStartedAt())).append("\n");
        }
        sb.append("- 有效时长:").append(MeetingTranscript.effectiveSeconds(m, segs) / 60).append(" 分钟\n");
        if (MeetingTranscript.notBlank(m.getAgenda())) {
            sb.append("- 会议前定的议题:\n").append(m.getAgenda()).append("\n");
            sb.append("(对照议题看哪些聊到了、哪些没聊到,在「议题与结论」里点出来)\n");
        }
        return sb.toString();
    }

    private static String headTail(String s, int head, int tail) {
        if (s.length() <= head + tail) return s;
        return s.substring(0, head) + "\n…(中间略)…\n" + s.substring(s.length() - tail);
    }

    // ==================== 行程 ====================

    private static final String PLAN_SYSTEM = """
            你从会议实录里抽出"接下来要发生的事",排成行程。
            只输出 JSON,不要解释。格式:
            {"items":[{"title":"...","startAt":"2026-11-03T14:00","endAt":"2026-11-03T15:00",
            "location":"...","participants":"张三、李四","kind":"MEETING","prepare":"...","sourceQuote":"..."}]}
            规则:
            1. kind 只能是 MEETING(见面/开会)、CALL(电话/线上沟通)、TRAVEL(出行)、
               PREP(会前准备)、DEADLINE(截止)、OTHER。
            2. startAt/endAt 用本地时间 yyyy-MM-ddTHH:mm,不要带时区。相对时间(明天下午三点、下周三)
               以给出的会议日期为基准换算。
            3. 原话只有日期没有钟点的,startAt 只写到日(yyyy-MM-dd),不要编一个时间。
            4. 完全没有时间的也要提,startAt 留空字符串。
            5. sourceQuote 必须是实录里的原句,不超过 40 字,不要改写。
            6. prepare 写"会前要准备什么",能从实录里看出来才写,看不出就留空。
            7. 没有可安排的事就返回 {"items":[]},不要为了凑数造事。
            8. 同一件事只出一条。原话里既有"我来约"又有"11 月 3 日下午两点对一下",那是同一场会,
               要合成一条并带上时间,不要拆成"约人"和"开会"两条。
            """;

    @Transactional
    public Map<String, Object> planItinerary(Long id, boolean force) {
        Meeting m = get(id);
        List<MeetingSegment> segs = segments.findByMeetingIdOrderBySeqAsc(id).stream()
                .filter(s -> !MeetingSegment.SYSTEM.equals(s.getKind())).toList();
        if (segs.isEmpty()) throw new IllegalArgumentException("这场会还没有任何发言,提取不出行程");

        List<ItineraryItem> existing = itinerary.findByMeetingIdOrderByStartAtAscIdAsc(id);
        boolean hasUntouched = existing.stream().anyMatch(x -> ItineraryItem.PLANNED.equals(x.getStatus()));
        if (!force && !existing.isEmpty() && !hasUntouched) {
            return Map.of("ok", true, "skipped", true, "count", existing.size());
        }

        String transcript = MeetingTranscript.plain(segs);
        String context;
        if (Meeting.SUMMARY_READY.equals(m.getSummaryStatus()) && m.getSummaryMd() != null) {
            context = "会议纪要:\n" + m.getSummaryMd() + "\n\n实录结尾(用于取原句):\n"
                    + headTail(transcript, 0, Math.min(8000, transcript.length()));
        } else {
            context = "实录:\n" + (transcript.length() <= 16000 ? transcript
                    : headTail(transcript, 8000, 8000));
        }

        ZoneId zone = ZoneId.systemDefault();
        Instant anchor = MeetingTranscript.anchor(m);
        String base = CN_DATE.withZone(zone).format(anchor);
        String user = "会议日期:" + base + "(" + CN_DT.withZone(zone).format(anchor) + ")\n"
                + "参会人:" + nz(m.getParticipants()) + "\n\n" + context;

        String json;
        try {
            json = llm.chatStream(List.of(
                    new LlmService.ChatMessage("system", PLAN_SYSTEM),
                    new LlmService.ChatMessage("user", user)), null, null, null, true, 3000);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("会议 {} 行程提取失败:{}", id, reason);
            return Map.of("ok", false, "error", "行程没提取出来:" + reason, "count", existing.size());
        }

        ItineraryPlanner.Parsed parsed = ItineraryPlanner.parse(json, anchor, zone);
        // 只清"没人动过"的旧条目:已完成的和已派活的留着,否则重新规划一次就丢账
        for (ItineraryItem old : existing) {
            if (ItineraryItem.PLANNED.equals(old.getStatus()) && old.getTaskId() == null) {
                itinerary.delete(old);
            }
        }
        List<ItineraryItem> kept = new ArrayList<>();
        for (ItineraryItem it : parsed.items()) {
            it.setMeeting(m);
            kept.add(itinerary.save(it));
        }
        List<ItineraryItem> all = itinerary.findByMeetingIdOrderByStartAtAscIdAsc(id);
        pusher.push("meeting-itinerary", Map.of("meetingId", id, "count", all.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("added", kept.size());
        out.put("dropped", parsed.dropped());
        out.put("count", all.size());
        out.put("items", all);
        if (kept.isEmpty()) out.put("note", "这场会里没有出现可以排进日程的事 —— 会议可能只聊了现状");
        return out;
    }

    public List<ItineraryItem> itineraryOf(Long meetingId) {
        return itinerary.findByMeetingIdOrderByStartAtAscIdAsc(meetingId);
    }

    public ItineraryItem setItemStatus(Long itemId, String status) {
        ItineraryItem it = itinerary.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("行程不存在: " + itemId));
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!List.of(ItineraryItem.PLANNED, ItineraryItem.DONE, ItineraryItem.CANCELLED).contains(s)) {
            throw new IllegalArgumentException("不认的状态:" + status);
        }
        it.setStatus(s);
        return itinerary.save(it);
    }

    /**
     * 把一条行程派给岗位。
     *
     * <p>派的是"准备",不是"去开会" —— AI 不能替人开会,但可以在会前把材料准备好。
     * 所以指令里写清要产出什么,并要求它引用原话依据。
     */
    public Map<String, Object> assignTask(Long itemId, Long employeeId, String extraInstruction) {
        ItineraryItem it = itinerary.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("行程不存在: " + itemId));
        if (it.getTaskId() != null) {
            throw new IllegalArgumentException("这条行程已经派过了,任务 #" + it.getTaskId());
        }
        Employee emp = employees.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在: " + employeeId));
        Meeting m = it.getMeeting();
        StringBuilder ins = new StringBuilder();
        ins.append("【会前准备】").append(it.getTitle()).append("\n");
        if (it.getStartAt() != null) {
            ins.append("时间:").append(CN_DT.withZone(ZoneId.systemDefault()).format(it.getStartAt())).append("\n");
        } else {
            ins.append("时间:尚未确定\n");
        }
        if (MeetingTranscript.notBlank(it.getLocation())) ins.append("地点:").append(it.getLocation()).append("\n");
        if (MeetingTranscript.notBlank(it.getParticipants())) ins.append("参与人:").append(it.getParticipants()).append("\n");
        if (MeetingTranscript.notBlank(it.getPrepare())) ins.append("已知要准备:").append(it.getPrepare()).append("\n");
        if (MeetingTranscript.notBlank(it.getSourceQuote())) {
            ins.append("会议原话依据:「").append(it.getSourceQuote()).append("」\n");
        }
        if (m != null && MeetingTranscript.notBlank(m.getTitle())) {
            ins.append("来自会议:").append(m.getTitle()).append("\n");
        }
        if (extraInstruction != null && !extraInstruction.isBlank()) {
            ins.append("\n额外交代:").append(extraInstruction.trim()).append("\n");
        }
        ins.append("\n要求:交一份可直接用的准备物(提纲/清单/材料),不要只回一句「我会准备好」;"
                + "凡涉及外部事实先查证,查不到写「未查到」。");
        var task = tasks.submit(emp.getId(), ins.toString(), null);
        it.setTaskId(task.getId());
        itinerary.save(it);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("taskId", task.getId());
        out.put("employee", emp.getName());
        return out;
    }

    // ==================== 导出 ====================

    /** ical 里时间一律用 UTC,不猜客户端时区 */
    public String ics(Long meetingId) {
        Meeting m = get(meetingId);
        List<ItineraryItem> items = itinerary.findByMeetingIdOrderByStartAtAscIdAsc(meetingId);
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
          .append("PRODID:-//BlankGrid AI One//Meeting Itinerary//CN\r\n")
          .append("CALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\n")
          .append("X-WR-CALNAME:").append(icsEsc(nz(m.getTitle()) + " · 行程")).append("\r\n");
        DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
        DateTimeFormatter day = DateTimeFormatter.ofPattern("yyyyMMdd");
        Instant now = Instant.now();
        for (ItineraryItem it : items) {
            if (ItineraryItem.CANCELLED.equals(it.getStatus())) continue;
            sb.append("BEGIN:VEVENT\r\n")
              .append("UID:aione-itinerary-").append(it.getId()).append("@blankgrid\r\n")
              .append("DTSTAMP:").append(utc.format(now)).append("\r\n");
            if (it.getStartAt() != null) {
                sb.append("DTSTART:").append(utc.format(it.getStartAt())).append("\r\n");
                Instant end = it.getEndAt() != null ? it.getEndAt() : it.getStartAt().plusSeconds(3600);
                sb.append("DTEND:").append(utc.format(end)).append("\r\n");
            } else {
                // 时间待定:排成会议当天的全天事件,标题上标出来,别让人以为定好了
                Instant anchor = MeetingTranscript.anchor(m);
                sb.append("DTSTART;VALUE=DATE:").append(day.withZone(ZoneId.systemDefault()).format(anchor)).append("\r\n");
                sb.append("DTEND;VALUE=DATE:")
                  .append(day.withZone(ZoneId.systemDefault()).format(anchor.plusSeconds(86400))).append("\r\n");
            }
            String summary = (it.isTimeUnknown() && it.getStartAt() != null ? "[时间待定] " : "")
                    + (it.getStartAt() == null ? "[待定] " : "") + nz(it.getTitle());
            sb.append("SUMMARY:").append(icsEsc(summary)).append("\r\n");
            if (MeetingTranscript.notBlank(it.getLocation())) {
                sb.append("LOCATION:").append(icsEsc(it.getLocation())).append("\r\n");
            }
            StringBuilder desc = new StringBuilder();
            if (MeetingTranscript.notBlank(it.getParticipants())) desc.append("参与:").append(it.getParticipants()).append("\n");
            if (MeetingTranscript.notBlank(it.getPrepare())) desc.append("准备:").append(it.getPrepare()).append("\n");
            if (MeetingTranscript.notBlank(it.getSourceQuote())) desc.append("原话:").append(it.getSourceQuote()).append("\n");
            if (desc.length() > 0) sb.append("DESCRIPTION:").append(icsEsc(desc.toString())).append("\r\n");
            sb.append("STATUS:").append(ItineraryItem.DONE.equals(it.getStatus()) ? "CONFIRMED" : "TENTATIVE").append("\r\n");
            sb.append("BEGIN:VALARM\r\nTRIGGER:-PT30M\r\nACTION:DISPLAY\r\n")
              .append("DESCRIPTION:").append(icsEsc(nz(it.getTitle()))).append("\r\nEND:VALARM\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return foldAll(sb.toString());
    }

    /** RFC5545 转义:反斜杠、分号、逗号、换行。折行在整行拼好之后统一做 */
    static String icsEsc(String s) {
        return nz(s).replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }

    /** 单行不超过 75 字节,续行以空格开头 —— 按 UTF-8 字节数折,不按字符数:中文一个字三字节 */
    static String foldAll(String ics) {
        String[] lines = ics.split("\r\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(foldLine(lines[i]));
            if (i < lines.length - 1) out.append("\r\n");
        }
        return out.toString();
    }

    static String foldLine(String s) {
        if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75) return s;
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            int b = ch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + b > 75 && bytes > 0) {
                out.append("\r\n ");
                bytes = 1;
            }
            out.append(ch);
            bytes += b;
        }
        return out.toString();
    }

    public record Download(String filename, String contentType, byte[] bytes) {}

    /**
     * 导出三种成品中的一种。
     *
     * <p>format 支持 md / docx / pdf —— 复用成果库那套纯 Java 导出,
     * 不要求本机装 Office。
     */
    public Download download(Long id, String kind, String format) {
        Meeting m = get(id);
        String k = kind == null ? "summary" : kind.trim().toLowerCase();
        String f = format == null || format.isBlank() ? "md" : format.trim().toLowerCase();
        String title;
        String md;
        switch (k) {
            case "verbatim" -> {
                title = m.getTitle() + "-原话实录";
                md = verbatim(id);
            }
            case "itinerary" -> {
                title = m.getTitle() + "-行程";
                md = ItineraryPlanner.toMarkdown(m.getTitle(), itineraryOf(id), ZoneId.systemDefault());
            }
            case "summary" -> {
                title = m.getTitle() + "-会议纪要";
                if (m.getSummaryMd() == null || m.getSummaryMd().isBlank()) {
                    throw new IllegalArgumentException("还没有纪要 —— 先点「生成总结版」");
                }
                md = m.getSummaryMd();
            }
            default -> throw new IllegalArgumentException("不认的导出类型:" + kind);
        }
        String name = safeName(title) + "." + ("md".equals(f) ? "md" : f);
        if ("md".equals(f)) {
            return new Download(name, "text/markdown; charset=UTF-8", md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!DocExportService.supports(f)) throw new IllegalArgumentException("不支持的格式:" + format);
        try {
            byte[] bytes = docs.render(title, md, f);
            String mime = "docx".equals(f)
                    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    : "application/pdf";
            return new Download(name, mime, bytes);
        } catch (Exception e) {
            throw new IllegalStateException("导出失败:" + e.getMessage());
        }
    }

    private static String safeName(String s) {
        return nz(s).replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
    }

    // ==================== 小工具 ====================

    private String newToken() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String normalizeParticipants(String s) {
        if (s == null) return "";
        return s.replace('，', ',').replace('、', ',').replaceAll("\\s*,\\s*", ",")
                .replaceAll("^,|,$", "");
    }

    static Instant parseInstant(String s) {
        String v = s.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignore) { /* 下面继续 */ }
        try {
            return java.time.OffsetDateTime.parse(v).toInstant();
        } catch (Exception ignore) { /* 下面继续 */ }
        LocalDate d = null;
        try {
            d = LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()));
            return d.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignore) { /* 下面继续 */ }
        try {
            return java.time.LocalDateTime.parse(v.replace(' ', 'T'),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式看不懂:" + s);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
