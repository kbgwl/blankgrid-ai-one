package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Artifact;
import com.blankgrid.aione.repo.ArtifactRepository;
import com.blankgrid.aione.service.ExportService;
import com.blankgrid.aione.service.GoalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 成果库:所有通过/待评审的公司成果都可检索、查看与下载。 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactRepository artRepo;
    private final GoalService goals;
    private final ExportService exports;

    public ArtifactController(ArtifactRepository artRepo, GoalService goals, ExportService exports) {
        this.artRepo = artRepo;
        this.goals = goals;
        this.exports = exports;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long goalId,
                                          @RequestParam(required = false) Long deliverableId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q) {
        List<Artifact> list;
        if (deliverableId != null) {
            // 一条交付物的全部历史版本:返工过的会有 v1/v2/v3,
            // 只有放在一起看才知道「这次改的是评审说的问题,还是换了个方向」。
            list = artRepo.findByDeliverableIdOrderByVersionDesc(deliverableId);
        } else if (goalId != null && status != null && !status.isBlank()) {
            list = artRepo.findByGoalIdAndStatusOrderByCreatedAtDesc(goalId, status);
        } else if (goalId != null) {
            list = artRepo.findByGoalIdOrderByCreatedAtDesc(goalId);
        } else if (status != null && !status.isBlank()) {
            list = artRepo.findByStatusOrderByCreatedAtDesc(status);
        } else {
            list = artRepo.findTop200ByOrderByCreatedAtDesc();
        }
        String keyword = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Artifact a : list) {
            if (!keyword.isBlank()) {
                String hay = (nz(a.getTitle()) + " " + nz(a.getType()) + " " + nz(a.getContent())).toLowerCase(Locale.ROOT);
                if (!hay.contains(keyword)) continue;
            }
            out.add(meta(a));
        }
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Artifact a = goals.artifact(id);
        Map<String, Object> m = meta(a);
        if (a.getAuthor() != null) {
            m.put("authorId", a.getAuthor().getId());
            m.put("authorTitle", a.getAuthor().getTitle());
            m.put("department", a.getAuthor().getDepartment() == null
                    ? null : a.getAuthor().getDepartment().getName());
        }
        m.put("goalTitle", a.getGoal() == null ? null : a.getGoal().getTitle());
        return m;
    }

    /** 单份成果直接写进设置页指定的落盘目录,不再走浏览器下载。 */
    @PostMapping("/{id}/export-to-dir")
    public Map<String, Object> exportToDir(@PathVariable Long id,
                                           @RequestParam(required = false) String format) {
        try {
            return exports.exportArtifact(id, format);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "写盘失败:" + e.getMessage());
        }
    }

    public static class EditBody {
        public String title;
        public String content;
    }

    /** 人工修订正文:模型写的差一口气时,创始人直接改,不用重跑一轮。 */
    @PutMapping("/{id}")
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody EditBody body) {
        Artifact a = goals.editArtifact(id, body.title, body.content);
        return meta(a);
    }

    /** 人工定稿:定为最终版,并把对应交付物标为完成,下游等待自动解除。 */
    @PostMapping("/{id}/adopt")
    public Map<String, Object> adopt(@PathVariable Long id) {
        Artifact a = goals.adoptArtifact(id);
        Map<String, Object> m = meta(a);
        m.put("ok", true);
        return m;
    }

    /**
     * 下载单份成果:默认 Markdown,?format=docx|pptx|pdf 直接出成品文件。
     * 浏览器点一下就拿到能发给人的文档,不用先装 Office。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id,
                                           @RequestParam(required = false) String format) {
        Artifact a = goals.artifact(id);
        String title = nz(a.getTitle()).isBlank() ? "artifact" : a.getTitle();
        String ext = com.blankgrid.aione.service.DocExportService.extension(format);
        byte[] body;
        try {
            body = exports.artifactBytes(id, ext);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
        String name = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "-") + "-v" + a.getVersion() + "." + ext;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(com.blankgrid.aione.service.ExportService.contentType(ext)))
                .body(body);
    }

    private Map<String, Object> meta(Artifact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("type", a.getType());
        m.put("version", a.getVersion());
        m.put("status", a.getStatus());
        m.put("score", a.getScore());
        m.put("reviewNotes", a.getReviewNotes());
        m.put("manualEdit", a.isManualEdit());
        m.put("author", a.getAuthor() == null ? null : a.getAuthor().getName());
        m.put("goalId", a.getGoalId());
        m.put("deliverableId", a.getDeliverableId());
        m.put("createdAt", a.getCreatedAt());
        m.put("approvedAt", a.getApprovedAt());
        m.put("content", a.getContent());
        return m;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
