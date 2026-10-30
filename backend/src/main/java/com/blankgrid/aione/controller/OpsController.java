package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.service.CeoLoopService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运营页:创始人设目标 / 发纠偏,看 CEO 汇报与上报,手动触发巡视 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final ActivityRepository activityRepo;
    private final CeoLoopService ceoLoop;
    private final com.blankgrid.aione.service.BackupService backupService;
    private final com.blankgrid.aione.repo.DecisionTraceRepository traceRepo;
    private final com.blankgrid.aione.service.EscalationService escalations;

    public OpsController(ActivityRepository activityRepo, CeoLoopService ceoLoop,
                         com.blankgrid.aione.service.BackupService backupService,
                         com.blankgrid.aione.repo.DecisionTraceRepository traceRepo,
                         com.blankgrid.aione.service.EscalationService escalations) {
        this.activityRepo = activityRepo;
        this.ceoLoop = ceoLoop;
        this.backupService = backupService;
        this.traceRepo = traceRepo;
        this.escalations = escalations;
    }

    @GetMapping("/feed")
    public Map<String, Object> feed() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activities", activityRepo.findTop100ByOrderByCreatedAtDescIdDesc());
        m.put("openEscalations", activityRepo.countByTypeAndStatus(Activity.ESCALATION, Activity.OPEN));
        m.put("ceoRunning", ceoLoop.isRunning());
        m.put("cycleCount", ceoLoop.cycleCount());
        return m;
    }

    /**
     * 决策档案列表:最近几轮 CEO 巡视"查了什么、申请了什么、怎么拍的板"。
     *
     * <p>列表<b>不带</b>证据原文 —— 一条可能几万字,几十条一起返回会把页面拖死。
     * 想看全文点进详情。
     */
    @GetMapping("/traces")
    public List<Map<String, Object>> traces(@RequestParam(required = false, defaultValue = "30") int limit) {
        int cap = Math.max(1, Math.min(50, limit));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.blankgrid.aione.entity.DecisionTrace t : traceRepo.findTop50ByOrderByIdDesc()) {
            if (out.size() >= cap) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("cycleNo", t.getCycleNo());
            m.put("kind", t.getKind());
            m.put("model", t.getModel());
            m.put("summary", t.getSummary());
            m.put("toolNames", t.getToolNames());
            m.put("approvalsRequested", t.getApprovalsRequested());
            m.put("tokens", t.getTokens());
            m.put("evidenceChars", t.getEvidence() == null ? 0 : t.getEvidence().length());
            m.put("createdAt", t.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    /** 单轮决策全文:证据原文 + 决策 JSON。追问"它凭什么这么定"从这里开始。 */
    @GetMapping("/traces/{id}")
    public org.springframework.http.ResponseEntity<?> trace(@PathVariable Long id) {
        return traceRepo.findById(id)
                .<org.springframework.http.ResponseEntity<?>>map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    public static class ItemBody {
        public String content;
    }

    /** 创始人设公司目标 */
    @PostMapping("/goal")
    public Activity setGoal(@RequestBody ItemBody body) {
        return activityRepo.save(new Activity(Activity.GOAL, body.content, null));
    }

    /** 创始人发纠偏指令 */
    @PostMapping("/feedback")
    public Activity setFeedback(@RequestBody ItemBody body) {
        return activityRepo.save(new Activity(Activity.FEEDBACK, body.content, null));
    }

    /** 创始人处理上报(已阅/已拍板),也可用于手动关闭目标/纠偏 */
    @PostMapping("/{id}/ack")
    public Map<String, Object> ack(@PathVariable Long id) {
        activityRepo.findById(id).ifPresent(a -> {
            a.setStatus(Activity.ACK);
            activityRepo.save(a);
        });
        return Map.of("ok", true);
    }

    /**
     * 现在还成立的上报。已经自己好了的那些不在这里 —— 这是「等多少人拍板」的唯一来源。
     *
     * <p>带 occurrences:同一件事反复了多少次。老板要判断「值不值得管」,
     * 「这事第 92 次了」比「有条上报」有用得多。
     */
    @GetMapping("/escalations")
    public Map<String, Object> openEscalations() {
        List<Activity> open = escalations.open();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", open);
        m.put("count", open.size());
        m.put("occurrences", open.stream().mapToInt(Activity::getOccurrences).sum());
        return m;
    }

    /** 手动对账一次:主体已经不在问题状态的,立刻关掉 */
    @PostMapping("/escalations/sweep")
    public Map<String, Object> sweepEscalations() {
        int closed = escalations.sweep();
        return Map.of("ok", true, "closed", closed,
                "count", activityRepo.countByTypeAndStatus(Activity.ESCALATION, Activity.OPEN));
    }

    /** 一键已阅:一次清掉所有「知道了但没空逐条点」的 */
    @PostMapping("/escalations/ack-all")
    public Map<String, Object> ackAll() {
        int n = 0;
        for (Activity a : escalations.open()) {
            a.setStatus(Activity.ACK);
            activityRepo.save(a);
            n++;
        }
        return Map.of("ok", true, "acked", n);
    }

    /** 手动触发一次数据备份 */
    @PostMapping("/backup")
    public Map<String, Object> backupNow() {
        String name = backupService.doBackup();
        return Map.of("ok", name != null, "file", name == null ? "" : name);
    }

    /** 现有备份清单:能看见备份才算真的备份过 */
    @GetMapping("/backups")
    public Map<String, Object> backups() {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (java.nio.file.Path p : backupService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getFileName().toString());
            try {
                m.put("bytes", java.nio.file.Files.size(p));
                m.put("at", java.nio.file.Files.getLastModifiedTime(p).toInstant());
            } catch (Exception ignored) {
                // 单个文件读不到不影响整份清单
            }
            items.add(m);
        }
        return Map.of("items", items);
    }

    public static class RestoreBody {
        public String file;
    }

    /**
     * 恢复到某份备份。运行中不直接换库文件,只做暂存 + 标记,重启后由 RestoreApplier 在 JPA 之前完成替换。
     */
    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody RestoreBody body) {
        try {
            String name = backupService.stageRestore(body.file);
            return Map.of("ok", true, "file", name, "restartRequired", true,
                    "message", "已准备好,重启后生效;原数据库会另存一份,可回退。");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("ok", false, "message", e.getMessage() == null ? "恢复失败" : e.getMessage());
        }
    }

    /** 手动触发一轮 CEO 巡视 */
    @PostMapping("/ceo-cycle")
    public Map<String, Object> ceoCycle() {
        return ceoLoop.runCycle();
    }
}
