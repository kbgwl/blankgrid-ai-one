package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Artifact;
import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.repo.ArtifactRepository;
import com.blankgrid.aione.repo.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成果落盘:把数据库里的成果写成磁盘上真正的文件。
 * 目录由创始人在设置页指定(空 = 程序目录下 exports/);项目落盘与 zip 导出一份内容。
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final SettingsService settings;
    private final GoalService goals;
    private final GoalRepository goalRepo;
    private final ArtifactRepository artRepo;

    public ExportService(SettingsService settings, GoalService goals,
                         GoalRepository goalRepo, ArtifactRepository artRepo) {
        this.settings = settings;
        this.goals = goals;
        this.goalRepo = goalRepo;
        this.artRepo = artRepo;
    }

    /** 设置页用:当前落盘目录是否可用,避免「写完才发现没权限」。 */
    public Map<String, Object> status() {
        Path root = settings.exportRoot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", root.toString());
        m.put("configured", settings.get().exportDir);
        m.put("exists", Files.isDirectory(root));
        m.put("writable", canWrite(root));
        m.put("autoExport", settings.autoExportArtifacts());
        return m;
    }

    /** 整个项目写到一个目录:README 索引 + 计划 + 清单 + 每份成果一个 md。 */
    public Map<String, Object> exportGoal(Long goalId) throws IOException {
        Goal g = goalRepo.findById(goalId).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        Map<String, String> files = goals.goalFiles(goalId);
        Path root = settings.exportRoot();
        Path dir = inside(root, folderName(g));
        writeFiles(dir, files);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("dir", dir.toString());
        m.put("files", files.size());
        m.put("goalId", goalId);
        m.put("goalTitle", g.getTitle());
        return m;
    }

    /** 单份成果写成一个 md 文件,带评审意见与评分,方便直接交给下游。 */
    public Map<String, Object> exportArtifact(Long artifactId) throws IOException {
        Artifact a = artRepo.findById(artifactId).orElseThrow(() -> new IllegalArgumentException("成果物不存在"));
        Path root = settings.exportRoot();
        Path dir = inside(root, "单件成果");
        String name = safeName(a.getTitle(), "artifact") + "-v" + a.getVersion() + ".md";
        Path file = inside(dir, name);
        Files.createDirectories(dir);
        Files.writeString(file, artifactMarkdown(a), StandardCharsets.UTF_8);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("dir", dir.toString());
        m.put("file", file.toString());
        m.put("files", 1);
        m.put("artifactId", artifactId);
        return m;
    }

    /**
     * 评审通过后自动落盘。失败绝不能把评审流程带崩,所以只记日志。
     * @return 落地目录;失败返回 null
     */
    public String autoExportGoal(Long goalId) {
        if (!settings.autoExportArtifacts()) return null;
        try {
            Map<String, Object> r = exportGoal(goalId);
            return String.valueOf(r.get("dir"));
        } catch (Exception e) {
            log.warn("成果自动落盘失败 goalId={}: {}", goalId, e.getMessage());
            return null;
        }
    }

    // ==================== 内部 ====================

    private void writeFiles(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path target = inside(dir, e.getKey());
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** 目标目录名:编号 + 标题,重复跑覆盖同一目录而不是堆一堆副本。 */
    private String folderName(Goal g) {
        String id = String.format("%03d", g.getId());
        return id + "-" + safeName(g.getTitle(), "goal");
    }

    /** 防御路径穿越:任何拼出来的路径都必须落在给定根目录之内。 */
    private Path inside(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("导出路径越界:" + relative);
        }
        return target;
    }

    private boolean canWrite(Path root) {
        try {
            Files.createDirectories(root);
            return Files.isWritable(root);
        } catch (Exception e) {
            return false;
        }
    }

    private String artifactMarkdown(Artifact a) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(nz(a.getTitle())).append("\n\n");
        sb.append("> v").append(a.getVersion()).append(" · 评分 ").append(a.getScore())
          .append(" · ").append(a.getStatus());
        if (a.getAuthor() != null) sb.append(" · ").append(a.getAuthor().getName());
        sb.append("\n\n");
        if (a.getReviewNotes() != null && !a.getReviewNotes().isBlank()) {
            sb.append("## 评审意见\n\n").append(a.getReviewNotes()).append("\n\n");
        }
        sb.append("---\n\n").append(nz(a.getContent()));
        return sb.toString();
    }

    static String safeName(String s, String fallback) {
        String n = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "-").trim();
        if (n.isBlank()) return fallback;
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
