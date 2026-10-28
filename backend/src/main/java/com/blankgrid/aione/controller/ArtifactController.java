package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Artifact;
import com.blankgrid.aione.repo.ArtifactRepository;
import com.blankgrid.aione.service.GoalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public ArtifactController(ArtifactRepository artRepo, GoalService goals) {
        this.artRepo = artRepo;
        this.goals = goals;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long goalId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q) {
        List<Artifact> list;
        if (goalId != null && status != null && !status.isBlank()) {
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

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Artifact a = goals.artifact(id);
        String title = nz(a.getTitle()).isBlank() ? "artifact" : a.getTitle();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 版本 v").append(a.getVersion()).append(" · 评分 ").append(a.getScore())
          .append(" · ").append(a.getStatus()).append("\n\n");
        if (a.getReviewNotes() != null && !a.getReviewNotes().isBlank()) {
            sb.append("## 评审意见\n\n").append(a.getReviewNotes()).append("\n\n");
        }
        sb.append("---\n\n").append(nz(a.getContent()));
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String name = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "-") + "-v" + a.getVersion() + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
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
