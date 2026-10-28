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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 成果落盘:把数据库里的成果写成磁盘上真正的文件。
 * 目录由创始人在设置页指定(空 = 程序目录下 exports/)。
 *
 * <p>三种导出粒度:
 * <ul>
 *   <li>{@code md}:一个项目一个目录,README + 计划 + 清单 + 每份成果一个 .md(原有的机器友好形态)</li>
 *   <li>{@code docx}/{@code pdf}:整本项目合成一个文件,给不看目录的人</li>
 *   <li>{@code pptx}:整本项目的汇报稿,一页一个要点</li>
 * </ul>
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final SettingsService settings;
    private final GoalService goals;
    private final GoalRepository goalRepo;
    private final ArtifactRepository artRepo;
    private final DocExportService docs;

    public ExportService(SettingsService settings, GoalService goals,
                         GoalRepository goalRepo, ArtifactRepository artRepo,
                         DocExportService docs) {
        this.settings = settings;
        this.goals = goals;
        this.goalRepo = goalRepo;
        this.artRepo = artRepo;
        this.docs = docs;
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
        m.put("formats", DocExportService.FORMATS);
        m.put("pdfFont", docs.resolveCjkFont());
        m.put("pdfFontReady", docs.cjkFontAvailable());
        return m;
    }

    /** all = md + docx + pdf + pptx 各写一份,不想选就用它;也可以传 "md+docx" 这种组合 */
    private static List<String> expand(String format) {
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT).trim();
        if (f.isEmpty() || "all".equals(f)) return List.of("md", "docx", "pdf", "pptx");
        List<String> out = new ArrayList<>();
        for (String part : f.split("[+,]")) {
            String p = DocExportService.extension(part.trim());
            if (!out.contains(p)) out.add(p);
        }
        return out.isEmpty() ? List.of("md") : out;
    }

    public Map<String, Object> exportGoal(Long goalId) throws IOException {
        return exportGoal(goalId, "all");
    }

    /**
     * 整个项目写到一个目录。
     * @param format md / docx / pdf / pptx / all
     */
    public Map<String, Object> exportGoal(Long goalId, String format) throws IOException {
        Goal g = goalRepo.findById(goalId).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        Path root = settings.exportRoot();
        Path dir = inside(root, folderName(g));
        Files.createDirectories(dir);

        List<String> written = new ArrayList<>();
        List<String> formats = expand(format);

        if (formats.contains("md")) {
            written.addAll(writeFiles(dir, goals.goalFiles(goalId)));
        }
        String combined = null;
        if (formats.contains("docx") || formats.contains("pdf") || formats.contains("pptx")) {
            combined = combinedMarkdown(goalId, g);
        }
        for (String f : formats) {
            if ("md".equals(f)) continue;
            Path target = inside(dir, "pptx".equals(f) ? "项目汇报.pptx" : "项目成果." + f);
            Files.write(target, docs.render(g.getTitle(), combined, f));
            written.add(target.getFileName().toString());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("dir", dir.toString());
        m.put("files", written.size());
        m.put("fileList", written);
        m.put("formats", formats);
        m.put("goalId", goalId);
        m.put("goalTitle", g.getTitle());
        return m;
    }

    public Map<String, Object> exportArtifact(Long artifactId) throws IOException {
        return exportArtifact(artifactId, "all");
    }

    /** 单份成果写进落盘目录;all = 四种格式各一份,方便直接发人。 */
    public Map<String, Object> exportArtifact(Long artifactId, String format) throws IOException {
        Artifact a = artRepo.findById(artifactId).orElseThrow(() -> new IllegalArgumentException("成果物不存在"));
        Path root = settings.exportRoot();
        Path dir = inside(root, "单件成果");
        Files.createDirectories(dir);

        String base = safeName(a.getTitle(), "artifact") + "-v" + a.getVersion();
        String md = artifactMarkdown(a);
        List<String> written = new ArrayList<>();
        for (String f : expand(format)) {
            Path file = inside(dir, base + "." + f);
            Files.write(file, docs.render(a.getTitle(), md, f));
            written.add(file.getFileName().toString());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("dir", dir.toString());
        m.put("file", written.isEmpty() ? null : written.get(0));
        m.put("files", written.size());
        m.put("fileList", written);
        m.put("artifactId", artifactId);
        return m;
    }

    /** 单份成果的字节流,给浏览器直接下载(不落盘)。 */
    public byte[] artifactBytes(Long artifactId, String format) throws IOException {
        Artifact a = artRepo.findById(artifactId).orElseThrow(() -> new IllegalArgumentException("成果物不存在"));
        return docs.render(a.getTitle(), artifactMarkdown(a), format);
    }

    /** 整个项目合成一个文件的字节流。 */
    public byte[] goalBytes(Long goalId, String format) throws IOException {
        Goal g = goalRepo.findById(goalId).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        return docs.render(g.getTitle(), combinedMarkdown(goalId, g), format);
    }

    /**
     * 评审通过后自动落盘。失败绝不能把评审流程带崩,所以只记日志。
     * 自动跑只写 Markdown(快、稳),要 Word/PDF 由创始人在页面上手动点。
     * @return 落地目录;失败返回 null
     */
    public String autoExportGoal(Long goalId) {
        if (!settings.autoExportArtifacts()) return null;
        try {
            Map<String, Object> r = exportGoal(goalId, "md+docx");
            return String.valueOf(r.get("dir"));
        } catch (Exception e) {
            log.warn("成果自动落盘失败 goalId={}: {}", goalId, e.getMessage());
            return null;
        }
    }

    // ==================== 内部 ====================

    /** 整本项目合成一份文档:封面 → 概览 → 计划 → 每份成果 */
    private String combinedMarkdown(Long goalId, Goal g) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(g.getTitle()).append("\n\n");
        sb.append("> 由空白格 AI 公司自动规划、执行、评审并归档。进度 ").append(g.getProgress())
          .append("%,评审均分 ").append(g.getFinalScore()).append("。\n\n");
        List<Artifact> arts = artRepo.findByGoalIdAndStatusOrderByScoreDesc(goalId, Artifact.APPROVED);
        if (arts.isEmpty()) arts = artRepo.findByGoalIdOrderByCreatedAtDesc(goalId);
        sb.append("本册共收录 ").append(arts.size()).append(" 份成果。\n\n");
        int i = 1;
        for (Artifact a : arts) {
            sb.append("## ").append(i++).append(". ").append(a.getTitle()).append("\n\n");
            sb.append("> v").append(a.getVersion()).append(" · 评分 ").append(a.getScore())
              .append(" · ").append(a.getStatus()).append("\n\n");
            sb.append(nz(a.getContent())).append("\n\n");
        }
        return sb.toString();
    }

    private List<String> writeFiles(Path dir, Map<String, String> files) throws IOException {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path target = inside(dir, e.getKey());
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            names.add(e.getKey());
        }
        return names;
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

    String artifactMarkdown(Artifact a) {
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

    /** 下载时的 Content-Type;除这四种之外一律按二进制流 */
    public static String contentType(String format) {
        return switch (DocExportService.extension(format)) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf" -> "application/pdf";
            default -> "text/markdown; charset=UTF-8";
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
