package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.ItineraryItem;
import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;
import com.blankgrid.aione.service.MeetingService;
import com.blankgrid.aione.service.MeetingSignals;
import com.blankgrid.aione.service.MeetingTranscript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会议:实时记录 → 总结版 / 原话版 → 行程。
 *
 * <p>路由分三组:
 * <ul>
 *   <li>会议本身(建、开、停、结束、详情)</li>
 *   <li>转写片段(单条、批量导入、外部 ASR 推送)</li>
 *   <li>成品(纪要、原话、行程、日历、导出)</li>
 * </ul>
 * 中间那条 {@code /ingest/{token}} 用的是每场会自己的推送口令 ——
 * 外部转写服务不该拿到控制台的登录口令。
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final MeetingService meetings;

    public MeetingController(MeetingService meetings) {
        this.meetings = meetings;
    }

    // ==================== 会议 ====================

    @GetMapping
    public List<Map<String, Object>> list() {
        return meetings.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody MeetingService.CreateBody body) {
        Meeting m = meetings.create(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("title", m.getTitle());
        out.put("status", m.getStatus());
        out.put("ingestToken", m.getIngestToken());
        out.put("pushUrl", "/api/meetings/ingest/" + m.getIngestToken());
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return meetings.detail(id);
    }

    /** 会前改期、加人、改议题:只提交变动的字段 */
    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody MeetingService.UpdateBody body) {
        meetings.update(id, body);
        return meetings.detail(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        meetings.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable Long id) {
        return state(meetings.start(id));
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable Long id) {
        return state(meetings.pause(id));
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable Long id) {
        return state(meetings.resume(id));
    }

    @PostMapping("/{id}/end")
    public Map<String, Object> end(@PathVariable Long id) {
        return state(meetings.end(id));
    }

    private Map<String, Object> state(Meeting m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("status", m.getStatus());
        out.put("elapsedSeconds", m.elapsedSeconds());
        out.put("durationText", MeetingTranscript.humanDuration(m.elapsedSeconds()));
        return out;
    }

    // ==================== 片段 ====================

    @PostMapping("/{id}/segments")
    public Map<String, Object> append(@PathVariable Long id, @RequestBody MeetingService.SegmentBody body) {
        return meetings.append(id, body.speaker, body.text, body.atMs,
                body.kind == null ? MeetingSegment.SPEECH : body.kind);
    }

    public static class ImportBody {
        public String transcript;
        public String defaultSpeaker;
    }

    @PostMapping("/{id}/import")
    public Map<String, Object> importTranscript(@PathVariable Long id, @RequestBody ImportBody body) {
        return meetings.importTranscript(id, body.transcript, body.defaultSpeaker);
    }

    /** 外部 ASR 推送。不校验登录口令,只校验这一场的 ingestToken。 */
    @PostMapping("/ingest/{token}")
    public Map<String, Object> ingest(@PathVariable String token,
                                      @RequestBody List<MeetingService.SegmentBody> body) {
        int n = meetings.ingest(token, body);
        return Map.of("ok", true, "accepted", n);
    }

    @PostMapping("/segments/{segmentId}/mark")
    public Map<String, Object> mark(@PathVariable Long segmentId) {
        MeetingSegment s = meetings.toggleMark(segmentId);
        return Map.of("ok", true, "marked", s.isMarked());
    }

    // ==================== 成品 ====================

    @GetMapping("/{id}/verbatim")
    public Map<String, Object> verbatim(@PathVariable Long id) {
        return Map.of("md", meetings.verbatim(id));
    }

    public static class ForceBody {
        public boolean force;
    }

    @PostMapping("/{id}/summarize")
    public Map<String, Object> summarize(@PathVariable Long id,
                                         @RequestBody(required = false) ForceBody body) {
        return meetings.summarize(id, body != null && body.force);
    }

    @PostMapping("/{id}/itinerary")
    public Map<String, Object> itinerary(@PathVariable Long id,
                                         @RequestBody(required = false) ForceBody body) {
        return meetings.planItinerary(id, body != null && body.force);
    }

    @GetMapping("/{id}/itinerary")
    public List<ItineraryItem> itineraryOf(@PathVariable Long id) {
        return meetings.itineraryOf(id);
    }

    @GetMapping("/{id}/signals")
    public Map<String, Object> signals(@PathVariable Long id) {
        Map<String, Object> d = meetings.detail(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signals", d.get("signals"));
        out.put("insights", d.get("insights"));
        out.put("speakerStats", d.get("speakerStats"));
        out.put("elapsedSeconds", d.get("elapsedSeconds"));
        return out;
    }

    /** 日历订阅:手机上直接导入,不依赖这个页面开着 */
    @GetMapping("/{id}/itinerary.ics")
    public ResponseEntity<byte[]> ics(@PathVariable Long id) {
        String ics = meetings.ics(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/calendar; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"itinerary.ics\"")
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导出成品。
     *
     * @param kind   summary / verbatim / itinerary
     * @param format md / docx / pdf
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id,
                                           @RequestParam(defaultValue = "summary") String kind,
                                           @RequestParam(defaultValue = "md") String format) {
        MeetingService.Download d = meetings.download(id, kind, format);
        String encoded = URLEncoder.encode(d.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, d.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + d.filename().replaceAll("[^\\x20-\\x7e]", "_")
                                + "\"; filename*=UTF-8''" + encoded)
                .body(d.bytes());
    }

    // ==================== 行程操作 ====================

    public static class StatusBody {
        public String status;
    }

    @PostMapping("/itinerary/{itemId}/status")
    public Map<String, Object> setStatus(@PathVariable Long itemId, @RequestBody StatusBody body) {
        ItineraryItem it = meetings.setItemStatus(itemId, body.status);
        return Map.of("ok", true, "status", it.getStatus());
    }

    public static class AssignBody {
        public Long employeeId;
        public String instruction;
    }

    @PostMapping("/itinerary/{itemId}/task")
    public Map<String, Object> assign(@PathVariable Long itemId, @RequestBody AssignBody body) {
        return meetings.assignTask(itemId, body.employeeId, body.instruction);
    }

    /** 实时信号的字典,前端用来配色/中文化,不用自己维护一份 */
    @GetMapping("/signal-kinds")
    public List<Map<String, String>> signalKinds() {
        return List.of(
                Map.of("kind", MeetingSignals.DECISION, "label", "决议"),
                Map.of("kind", MeetingSignals.ACTION, "label", "待办"),
                Map.of("kind", MeetingSignals.RISK, "label", "风险"),
                Map.of("kind", MeetingSignals.OPEN, "label", "悬而未决"),
                Map.of("kind", MeetingSignals.TIME, "label", "时间"),
                Map.of("kind", MeetingSignals.NUMBER, "label", "数字"));
    }
}
