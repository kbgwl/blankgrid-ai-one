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
    private final CostService costs;

    public GoalService(GoalRepository goalRepo, WorkstreamRepository wsRepo,
                       DeliverableRepository delRepo, ArtifactRepository artRepo,
                       ActivityRepository activityRepo, TokenLogRepository tokenRepo,
                       EventPusher pusher, ApplicationEventPublisher publisher,
                       ObjectMapper mapper, CostService costs) {
        this.goalRepo = goalRepo;
        this.wsRepo = wsRepo;
        this.delRepo = delRepo;
        this.artRepo = artRepo;
        this.activityRepo = activityRepo;
        this.tokenRepo = tokenRepo;
        this.pusher = pusher;
        this.publisher = publisher;
        this.mapper = mapper;
        this.costs = costs;
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

    /**
     * 追加 token 预算。预算用尽时目标会被自动暂停,这里补上额度后自动恢复,
     * 不用再点一次「恢复」——那一步对创始人来说没有意义。
     */
    /**
     * 人工修订成果正文。评审不过或者差一口气的时候,创始人应该能自己动手改,
     * 而不是只能反复重跑模型。改过的成果打上 manualEdit,评审分只作历史参考。
     */
    @Transactional
    public Artifact editArtifact(Long id, String title, String content) {
        Artifact a = artRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("成果不存在"));
        if (title != null && !title.isBlank()) a.setTitle(title.trim());
        if (content != null) a.setContent(content);
        a.setManualEdit(true);
        artRepo.save(a);
        Activity ac = new Activity(Activity.REPORT, "创始人手工修订了成果「" + a.getTitle() + "」。", null);
        ac.setGoalId(a.getGoalId());
        activityRepo.save(ac);
        if (a.getGoalId() != null) push(goalRepo.findById(a.getGoalId()).orElse(null));
        return a;
    }

    /**
     * 人工定稿:把这份成果直接定为本项目的最终版,并把它对应的交付物置为完成,
     * 下游等待中的交付物会自动被引擎唤醒。
     */
    @Transactional
    public Artifact adoptArtifact(Long id) {
        Artifact a = artRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("成果不存在"));
        a.setStatus(Artifact.APPROVED);
        a.setApprovedAt(Instant.now());
        if (a.getScore() <= 0) a.setScore(85);
        artRepo.save(a);

        Deliverable d = a.getDeliverable();
        if (d != null && !Deliverable.DONE.equals(d.getStatus())) {
            d.setStatus(Deliverable.DONE);
            d.setArtifactId(a.getId());
            d.setCurrentTaskId(null);
            d.setReviewAttempts(0);
            d.setReviewScore(Math.max(d.getReviewScore(), a.getScore()));
            d.setReviewNotes("创始人手工定稿,视为通过。");
            d.setUpdatedAt(Instant.now());
            delRepo.save(d);
        }
        if (a.getGoalId() != null) {
            refreshProgress(a.getGoalId());
            Activity ac = new Activity(Activity.REPORT,
                    "创始人把「" + a.getTitle() + "」定为最终版,对应交付物已标记完成。", null);
            ac.setGoalId(a.getGoalId());
            activityRepo.save(ac);
            push(goalRepo.findById(a.getGoalId()).orElse(null));
        }
        return a;
    }

    @Transactional
    public Goal addBudget(Long id, long tokens) {
        Goal g = goalRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        if (tokens <= 0) throw new IllegalArgumentException("追加额度必须大于 0");
        g.setBudgetTokens(g.getBudgetTokens() + tokens);
        boolean wasPausedForBudget = Goal.PAUSED.equals(g.getStatus())
                && g.getCeoSummary() != null && g.getCeoSummary().contains("预算");
        if (wasPausedForBudget) {
            g.setStatus(wsRepo.countByGoalId(id) > 0 ? Goal.ACTIVE : Goal.PLANNING);
            g.setCeoSummary("");
        }
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);

        Activity a = new Activity(Activity.REPORT, "创始人给目标「" + g.getTitle() + "」追加了 "
                + tokens + " token 预算" + (wasPausedForBudget ? ",已自动恢复推进。" : "。"), null);
        a.setGoalId(id);
        activityRepo.save(a);
        push(g);
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
        // token 数对创始人没有决策价值,折算成钱才有
        m.put("cost", costs.ofGoal(g.getId()));
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

    /**
     * 一键恢复:把该目标所有受阻/挂起的交付物重新推回队列。
     * 用在"充值了 / 换了模型 / 重启过"这类外部原因停摆之后 ——
     * 否则创始人得逐条点重试,几十条就是几十次点击。
     */
    @Transactional
    public Map<String, Object> retryBlocked(Long goalId) {
        int reset = 0;
        for (Deliverable d : delRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId)) {
            if (Deliverable.BLOCKED.equals(d.getStatus()) || Deliverable.WAITING.equals(d.getStatus())) {
                d.setStatus(Deliverable.READY);
                d.setAttempts(0);
                d.setReviewAttempts(0);
                d.setCurrentTaskId(null);
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
                reset++;
            }
        }
        if (reset > 0) resumeGoalFor(goalId, "已把 " + reset + " 项受阻交付重新推回执行队列。");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("reset", reset);
        return out;
    }

    /**
     * 创始人补料:写入已确认的事实,清空待补清单,并把因缺料/上游受阻的交付物重新推回队列。
     * 这是「缺料上报」闭环的回收端 —— 没有它,上报只是一张纸条。
     */
    @Transactional
    public Map<String, Object> provideFacts(Long goalId, String facts) {
        Goal g = goalRepo.findById(goalId).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        String text = facts == null ? "" : facts.trim();
        if (text.isBlank()) throw new IllegalArgumentException("补料内容不能为空");

        String prev = g.getProvidedFacts() == null ? "" : g.getProvidedFacts().trim();
        g.setProvidedFacts(clip(prev.isBlank() ? text : prev + "\n\n" + text, 11000));
        g.setNeedsInput("");
        if (Goal.REVIEW.equals(g.getStatus()) || Goal.PAUSED.equals(g.getStatus())
                || Goal.FAILED.equals(g.getStatus())) {
            g.setStatus(wsRepo.countByGoalId(goalId) > 0 ? Goal.ACTIVE : Goal.PLANNING);
            if (g.getProgress() >= 100) g.setProgress(99);
        }
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);

        int reset = 0;
        for (Deliverable d : delRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId)) {
            if (Deliverable.BLOCKED.equals(d.getStatus()) || Deliverable.WAITING.equals(d.getStatus())) {
                d.setStatus(Deliverable.READY);
                d.setAttempts(0);
                d.setReviewAttempts(0);
                d.setCurrentTaskId(null);
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
                reset++;
            }
        }
        Activity a = new Activity(Activity.REPORT, "创始人补充了事实依据"
                + (reset > 0 ? ",已把 " + reset + " 项受阻交付重新推回队列。" : ",后续拆解与执行以这些事实为准。"), null);
        a.setGoalId(goalId);
        activityRepo.save(a);
        push(g);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("reset", reset);
        out.put("status", g.getStatus());
        return out;
    }

    /** 重试受阻工作流:重置其受阻交付物并让引擎继续推进。 */    @Transactional
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
        Map<String, String> files = goalFiles(goalId);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : files.entrySet()) put(zip, e.getKey(), e.getValue());
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    /**
     * 一个项目的全部交付文件(文件名 → 内容),zip 导出与目录落盘共用同一份内容,
     * 保证「导出的压缩包」和「写进磁盘的目录」完全一致。
     */
    @Transactional(readOnly = true)
    public Map<String, String> goalFiles(Long goalId) {
        Goal g = goalRepo.findById(goalId).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        List<Workstream> wss = wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId);
        List<Artifact> arts = artRepo.findByGoalIdAndStatusOrderByScoreDesc(goalId, Artifact.APPROVED);
        if (arts.isEmpty()) arts = artRepo.findByGoalIdOrderByCreatedAtDesc(goalId);

        Map<String, String> files = new LinkedHashMap<>();
        files.put("README.md", projectReadme(g, wss, arts));
        files.put("plan.md", projectPlan(g, wss));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("goal", g);
        manifest.put("workstreams", wss);
        manifest.put("artifacts", arts);
        try {
            files.put("manifest.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        } catch (Exception e) {
            files.put("manifest.json", "{}");
        }
        int i = 1;
        for (Artifact a : arts) {
            files.put(String.format("artifacts/%02d-%s-v%d.md", i++, safeName(a.getTitle()), a.getVersion()),
                    a.getContent() == null ? "" : a.getContent());
        }
        return files;
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
        if (g == null) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("goal", g);
        pusher.push("goal-update", p);
    }
}
