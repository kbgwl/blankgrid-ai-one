package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.*;
import com.blankgrid.aione.repo.*;
import com.blankgrid.aione.ws.EventPusher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 公司目标的读写、进度核算、详情聚合与项目导出。 */
@Service
public class GoalService {

    private final GoalRepository goalRepo;
    private final WorkstreamRepository wsRepo;
    private final DeliverableRepository delRepo;
    private final ArtifactRepository artRepo;
    private final ActivityRepository activityRepo;
    private final TokenLogRepository tokenRepo;
    private final EventPusher pusher;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;

    public GoalService(GoalRepository goalRepo, WorkstreamRepository wsRepo,
                       DeliverableRepository delRepo, ArtifactRepository artRepo,
                       ActivityRepository activityRepo, TokenLogRepository tokenRepo,
                       EventPusher pusher, ApplicationEventPublisher publisher,
                       ObjectMapper mapper) {
        this.goalRepo = goalRepo;
        this.wsRepo = wsRepo;
        this.delRepo = delRepo;
        this.artRepo = artRepo;
        this.activityRepo = activityRepo;
        this.tokenRepo = tokenRepo;
        this.pusher = pusher;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    /** 创始人下达目标:公司立即开始拆解,无需再点任何按钮。 */
    @Transactional
    public Goal create(Goal input, boolean autoStart) {
        Goal g = new Goal();
        g.setTitle(clip(input.getTitle(), 300));
        g.setBrief(clip(input.getBrief(), 8000));
        g.setSuccessCriteria(clip(input.getSuccessCriteria(), 8000));
        g.setQualityBar(clip(input.getQualityBar(), 3000));
        g.setDeadline(input.getDeadline());
        g.setBudgetTokens(Math.max(0, input.getBudgetTokens()));
        g.setStatus(Goal.PLANNING);
        g = goalRepo.save(g);

        Activity a = new Activity(Activity.REPORT, "创始人下达公司目标:「" + g.getTitle() + "」。CEO 开始拆解。", null);
        a.setGoalId(g.getId());
        activityRepo.save(a);
        push(g);
        if (autoStart) publisher.publishEvent(new GoalCreatedEvent(g.getId()));
        return g;
    }

    @Transactional
    public Map<String, Object> start(Long id) {
        Goal g = goalRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        if (Goal.DONE.equals(g.getStatus())) throw new IllegalArgumentException("目标已完成");
        long wsCount = wsRepo.countByGoalId(id);
        g.setStatus(wsCount > 0 ? Goal.ACTIVE : Goal.PLANNING);
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);
        if (wsCount == 0) publisher.publishEvent(new GoalCreatedEvent(id));
        push(g);
        return Map.of("ok", true, "status", g.getStatus());
    }

    @Transactional
    public Goal pause(Long id) {
        Goal g = goalRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        if (!Goal.DONE.equals(g.getStatus())) {
            g.setStatus(Goal.PAUSED);
            g.setUpdatedAt(Instant.now());
            goalRepo.save(g);
            push(g);
        }
        return g;
    }

    @Transactional
    public Goal resume(Long id) {
        Goal g = goalRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        if (Goal.PAUSED.equals(g.getStatus()) || Goal.FAILED.equals(g.getStatus())) {
            g.setStatus(wsRepo.countByGoalId(id) > 0 ? Goal.ACTIVE : Goal.PLANNING);
            g.setUpdatedAt(Instant.now());
            goalRepo.save(g);
            if (wsRepo.countByGoalId(id) == 0) publisher.publishEvent(new GoalCreatedEvent(id));
            push(g);
        }
        return g;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Goal g : goalRepo.findAllByOrderByCreatedAtDesc()) out.add(summary(g));
        return out;
    }

    public Map<String, Object> summary(Goal g) {
        long total = delRepo.countByGoalId(g.getId());
        long done = delRepo.countByGoalIdAndStatus(g.getId(), Deliverable.DONE);
        long review = delRepo.countByGoalIdAndStatus(g.getId(), Deliverable.REVIEW);
        long blocked = delRepo.countByGoalIdAndStatus(g.getId(), Deliverable.BLOCKED);
        long artifacts = artRepo.countByGoalId(g.getId());
        long ws = wsRepo.countByGoalId(g.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goal", g);
        m.put("workstreams", ws);
        m.put("deliverables", total);
        m.put("done", done);
        m.put("reviewing", review);
        m.put("blocked", blocked);
        m.put("artifacts", artifacts);
        m.put("tokens", tokenRepo.tokensOfGoal(g.getId()));
        List<Map<String, Object>> depts = new ArrayList<>();
        for (Workstream stream : wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(g.getId())) {
            if (stream.getDepartment() == null) continue;
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", stream.getDepartment().getId());
            dm.put("name", stream.getDepartment().getName());
            dm.put("colorKey", stream.getDepartment().getColorKey());
            dm.put("lead", stream.getLead() == null ? null : stream.getLead().getName());
            depts.add(dm);
        }
        m.put("departments", depts);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Goal g = goalRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        List<Map<String, Object>> wss = new ArrayList<>();
        for (Workstream ws : wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(id)) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("workstream", ws);
            List<Deliverable> ds = delRepo.findByWorkstreamIdOrderBySortOrderAscIdAsc(ws.getId());
            wm.put("deliverables", ds);
            wm.put("done", ds.stream().filter(d -> Deliverable.DONE.equals(d.getStatus())).count());
            wm.put("total", ds.size());
            wss.add(wm);
        }
        List<Map<String, Object>> arts = new ArrayList<>();
        for (Artifact a : artRepo.findByGoalIdOrderByCreatedAtDesc(id)) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a.getId());
            am.put("title", a.getTitle());
            am.put("type", a.getType());
            am.put("version", a.getVersion());
            am.put("status", a.getStatus());
            am.put("score", a.getScore());
            am.put("reviewNotes", a.getReviewNotes());
            am.put("author", a.getAuthor() == null ? null : a.getAuthor().getName());
            am.put("deliverableId", a.getDeliverableId());
            am.put("createdAt", a.getCreatedAt());
            am.put("approvedAt", a.getApprovedAt());
            am.put("preview", clip(a.getContent(), 500));
            arts.add(am);
        }
        Map<String, Object> m = new LinkedHashMap<>(summary(g));
        m.put("workstreamList", wss);
        m.put("artifactList", arts);
        m.put("activities", activityRepo.findByGoalIdOrderByCreatedAtDescIdDesc(id));
        return m;
    }

    @Transactional
    public Map<String, Object> refreshProgress(Long goalId) {
        Goal g = goalRepo.findById(goalId).orElse(null);
        if (g == null) return Map.of();
        List<Deliverable> ds = delRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId);
        long done = ds.stream().filter(d -> Deliverable.DONE.equals(d.getStatus())).count();
        int progress = ds.isEmpty() ? (wsRepo.countByGoalId(goalId) > 0 ? 8 : 0)
                : (int) Math.round(done * 100.0 / ds.size());
        g.setProgress(Math.min(100, progress));
        List<Artifact> approved = artRepo.findByGoalIdAndStatusOrderByScoreDesc(goalId, Artifact.APPROVED);
        int avg = approved.isEmpty() ? 0
                : (int) Math.round(approved.stream().mapToInt(Artifact::getScore).average().orElse(0));
        g.setFinalScore(avg);
        g.setSpentTokens(tokenRepo.tokensOfGoal(goalId));
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);

        for (Workstream ws : wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId)) {
            List<Deliverable> wds = delRepo.findByWorkstreamIdOrderBySortOrderAscIdAsc(ws.getId());
            long wdone = wds.stream().filter(d -> Deliverable.DONE.equals(d.getStatus())).count();
            ws.setProgress(wds.isEmpty() ? 0 : (int) Math.round(wdone * 100.0 / wds.size()));
            if (!wds.isEmpty() && wdone == wds.size() && !Workstream.PAUSED.equals(ws.getStatus())) {
                ws.setStatus(Workstream.DONE);
            } else if (!wds.isEmpty() && !Workstream.DONE.equals(ws.getStatus()) && !Workstream.PAUSED.equals(ws.getStatus())) {
                ws.setStatus(Workstream.ACTIVE);
            }
            ws.setUpdatedAt(Instant.now());
            wsRepo.save(ws);
        }
        push(g);
        return summary(g);
    }

    @Transactional(readOnly = true)
    public Artifact artifact(Long id) {
        return artRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("成果物不存在"));
    }

    /** 创始人复核后接受:全部交付完成的目标强制归档。 */
    @Transactional
    public Goal accept(Long id) {
        Goal g = goalRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        long total = delRepo.countByGoalId(id);
        long done = delRepo.countByGoalIdAndStatus(id, Deliverable.DONE);
        if (total > 0 && done < total) {
            throw new IllegalArgumentException("还有 " + (total - done) + " 项交付未通过,不能归档");
        }
        g.setStatus(Goal.DONE);
        g.setProgress(100);
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);
        Goal finalG = g;
        Activity a = new Activity(Activity.REPORT, "创始人复核通过,目标「" + g.getTitle() + "」已归档。", null);
        a.setGoalId(id);
        activityRepo.save(a);
        push(finalG);
        return finalG;
    }

    /** 重试单个受阻交付物。 */
    @Transactional
    public Deliverable retryDeliverable(Long goalId, Long deliverableId) {
        Deliverable d = delRepo.findById(deliverableId)
                .orElseThrow(() -> new IllegalArgumentException("交付物不存在"));
        if (d.getGoal() == null || !goalId.equals(d.getGoal().getId())) {
            throw new IllegalArgumentException("交付物不属于该目标");
        }
        d.setStatus(Deliverable.READY);
        d.setAttempts(0);
        d.setReviewAttempts(0);
        d.setCurrentTaskId(null);
        d.setUpdatedAt(Instant.now());
        delRepo.save(d);
        resumeGoalFor(goalId, "交付物「" + d.getTitle() + "」已重置,重新进入执行队列。");
        return d;
    }

    /** 重试受阻工作流:重置其受阻交付物并让引擎继续推进。 */
    @Transactional
    public Map<String, Object> retryWorkstream(Long goalId, Long wsId) {
        Workstream ws = wsRepo.findById(wsId).orElseThrow(() -> new IllegalArgumentException("工作流不存在"));
        if (ws.getGoal() == null || !goalId.equals(ws.getGoal().getId())) {
            throw new IllegalArgumentException("工作流不属于该目标");
        }
        int reset = 0;
        for (Deliverable d : delRepo.findByWorkstreamIdOrderBySortOrderAscIdAsc(wsId)) {
            if (Deliverable.BLOCKED.equals(d.getStatus())) {
                d.setStatus(Deliverable.READY);
                d.setAttempts(0);
                d.setReviewAttempts(0);
                d.setCurrentTaskId(null);
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
                reset++;
            }
        }
        ws.setStatus(delRepo.countByWorkstreamId(wsId) == 0 ? Workstream.PLANNING : Workstream.ACTIVE);
        ws.setUpdatedAt(Instant.now());
        wsRepo.save(ws);
        resumeGoalFor(goalId, "工作流「" + ws.getTitle() + "」已恢复,重置 " + reset + " 项交付物。");
        return Map.of("ok", true, "reset", reset, "workstreamStatus", ws.getStatus());
    }

    private void resumeGoalFor(Long goalId, String note) {
        Goal g = goalRepo.findById(goalId).orElse(null);
        if (g == null) return;
        if (Goal.DONE.equals(g.getStatus())) {
            // 已完成的目标被创始人要求重做:重新打开,让引擎继续推进
            g.setStatus(wsRepo.countByGoalId(goalId) > 0 ? Goal.ACTIVE : Goal.PLANNING);
            g.setProgress(Math.min(g.getProgress(), 99));
            g.setCeoSummary("创始人重新打开目标:" + note);
            g.setUpdatedAt(Instant.now());
            goalRepo.save(g);
        } else if (Goal.REVIEW.equals(g.getStatus()) || Goal.FAILED.equals(g.getStatus())
                || Goal.PAUSED.equals(g.getStatus())) {
            g.setStatus(wsRepo.countByGoalId(goalId) > 0 ? Goal.ACTIVE : Goal.PLANNING);
            g.setUpdatedAt(Instant.now());
            goalRepo.save(g);
        }
        Activity a = new Activity(Activity.REPORT, note, null);
        a.setGoalId(goalId);
        activityRepo.save(a);
        push(g);
    }

    /** 导出整个项目:目标说明、计划、全部通过评审的成果、机器可读清单。 */
    @Transactional(readOnly = true)
    public byte[] exportZip(Long goalId) {
        Goal g = goalRepo.findById(goalId).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        List<Workstream> wss = wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId);
        List<Artifact> arts = artRepo.findByGoalIdAndStatusOrderByScoreDesc(goalId, Artifact.APPROVED);
        if (arts.isEmpty()) arts = artRepo.findByGoalIdOrderByCreatedAtDesc(goalId);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            put(zip, "README.md", projectReadme(g, wss, arts));
            put(zip, "plan.md", projectPlan(g, wss));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("goal", g);
            manifest.put("workstreams", wss);
            manifest.put("artifacts", arts);
            put(zip, "manifest.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            int i = 1;
            for (Artifact a : arts) {
                String name = String.format("artifacts/%02d-%s-v%d.md", i++, safeName(a.getTitle()), a.getVersion());
                put(zip, name, a.getContent() == null ? "" : a.getContent());
            }
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    private String projectReadme(Goal g, List<Workstream> wss, List<Artifact> arts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(g.getTitle()).append("\n\n");
        sb.append("> 由空白格 AI 公司自动规划、执行、评审并归档。\n\n");
        sb.append("## 创始人目标\n\n").append(nz(g.getBrief())).append("\n\n");
        sb.append("## 成功标准\n\n").append(nz(g.getSuccessCriteria())).append("\n\n");
        sb.append("## 质量底线\n\n").append(nz(g.getQualityBar())).append("\n\n");
        sb.append("## 交付概况\n\n");
        sb.append("- 状态:").append(g.getStatus()).append("\n");
        sb.append("- 进度:").append(g.getProgress()).append("%\n");
        sb.append("- 评审均分:").append(g.getFinalScore()).append("\n");
        sb.append("- 部门工作流:").append(wss.size()).append("\n");
        sb.append("- 成果物:").append(arts.size()).append("\n\n");
        sb.append("## 成果目录\n\n");
        for (Artifact a : arts) {
            sb.append("- [").append(a.getTitle()).append("](artifacts/) · 评分 ").append(a.getScore())
              .append(" · v").append(a.getVersion()).append("\n");
        }
        return sb.toString();
    }

    private String projectPlan(Goal g, List<Workstream> wss) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 项目计划 · ").append(g.getTitle()).append("\n\n");
        sb.append("## CEO 拆解思路\n\n").append(nz(g.getPlanNotes())).append("\n\n");
        for (Workstream ws : wss) {
            sb.append("## ").append(ws.getDepartment() == null ? "" : ws.getDepartment().getName())
              .append(" · ").append(ws.getTitle()).append("\n\n");
            sb.append("- 负责人:").append(ws.getLead() == null ? "未指定" : ws.getLead().getName()).append("\n");
            sb.append("- 状态:").append(ws.getStatus()).append(" · 进度 ").append(ws.getProgress()).append("%\n\n");
            sb.append(nz(ws.getBrief())).append("\n\n");
            sb.append("**验收标准**\n\n").append(nz(ws.getAcceptanceCriteria())).append("\n\n");
            for (Deliverable d : delRepo.findByWorkstreamIdOrderBySortOrderAscIdAsc(ws.getId())) {
                sb.append("- [").append(Deliverable.DONE.equals(d.getStatus()) ? "x" : " ").append("] ")
                  .append(d.getTitle()).append(" · ")
                  .append(d.getOwner() == null ? "未指定" : d.getOwner().getName())
                  .append(" · ").append(d.getStatus()).append(" · 评分 ").append(d.getReviewScore()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safeName(String s) {
        String n = s == null ? "artifact" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void push(Goal g) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("goal", g);
        pusher.push("goal-update", p);
    }
}
