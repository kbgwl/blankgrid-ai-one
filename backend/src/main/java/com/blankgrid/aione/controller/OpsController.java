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

    public OpsController(ActivityRepository activityRepo, CeoLoopService ceoLoop,
                         com.blankgrid.aione.service.BackupService backupService) {
        this.activityRepo = activityRepo;
        this.ceoLoop = ceoLoop;
        this.backupService = backupService;
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

    /** 手动触发一次数据备份 */
    @PostMapping("/backup")
    public Map<String, Object> backupNow() {
        String name = backupService.doBackup();
        return Map.of("ok", name != null, "file", name == null ? "" : name);
    }

    /** 手动触发一轮 CEO 巡视 */
    @PostMapping("/ceo-cycle")
    public Map<String, Object> ceoCycle() {
        return ceoLoop.runCycle();
    }
}
