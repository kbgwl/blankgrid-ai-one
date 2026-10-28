package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.service.CompanyEngineService;
import com.blankgrid.aione.service.ExportService;
import com.blankgrid.aione.service.GoalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 公司目标:创始人只需下目标,拆解/派活/评审/归档由 AI 组织自动完成。 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goals;
    private final CompanyEngineService engine;
    private final ExportService exports;

    public GoalController(GoalService goals, CompanyEngineService engine, ExportService exports) {
        this.goals = goals;
        this.engine = engine;
        this.exports = exports;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return goals.list();
    }

    public static class CreateBody {
        public String title;
        public String brief;
        public String successCriteria;
        public String qualityBar;
        public String deadline;
        public long budgetTokens = 0;
        public Boolean autoStart = true;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateBody body) {
        Goal input = new Goal();
        input.setTitle(body.title);
        input.setBrief(body.brief);
        input.setSuccessCriteria(body.successCriteria);
        input.setQualityBar(body.qualityBar);
        input.setBudgetTokens(body.budgetTokens);
        if (body.deadline != null && !body.deadline.isBlank()) {
            input.setDeadline(com.blankgrid.aione.service.CompanyEngineService.parseInstant(body.deadline));
        }
        Goal g = goals.create(input, body.autoStart == null || body.autoStart);
        return goals.summary(g);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return goals.detail(id);
    }

    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable Long id) {
        return goals.start(id);
    }

    @PostMapping("/{id}/pause")
    public Goal pause(@PathVariable Long id) {
        return goals.pause(id);
    }

    @PostMapping("/{id}/resume")
    public Goal resume(@PathVariable Long id) {
        return goals.resume(id);
    }

    @PostMapping("/{id}/accept")
    public Goal accept(@PathVariable Long id) {
        return goals.accept(id);
    }

    @PostMapping("/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable Long id) {
        return goals.refreshProgress(id);
    }

    public static class InputBody {
        public String facts;
    }

    public static class BudgetBody {
        /** 追加的 token 额度(正数,累加在原预算上) */
        public long tokens;
    }

    /** 预算用尽后追加额度;追加后自动恢复推进,不用再点一次恢复。 */
    @PostMapping("/{id}/budget")
    public Map<String, Object> addBudget(@PathVariable Long id, @RequestBody BudgetBody body) {
        Goal g = goals.addBudget(id, body.tokens);
        return goals.summary(g);
    }

    /** 创始人补料:把已核实的事实写进去,受阻的交付物会重新排队。 */
    @PostMapping("/{id}/inputs")
    public Map<String, Object> inputs(@PathVariable Long id, @RequestBody InputBody body) {
        return goals.provideFacts(id, body.facts);
    }

    /** 把整个项目写成磁盘上的文件(目录由设置页决定),返回落地路径。 */
    @PostMapping("/{id}/export-to-dir")
    public Map<String, Object> exportToDir(@PathVariable Long id) {
        try {
            return exports.exportGoal(id);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "写盘失败:" + e.getMessage());
        }
    }

    @PostMapping("/{id}/replan")
    public Map<String, Object> replan(@PathVariable Long id) {
        engine.requestGoalPlan(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/workstreams/{wsId}/retry")
    public Map<String, Object> retryWorkstream(@PathVariable Long id, @PathVariable Long wsId) {
        return goals.retryWorkstream(id, wsId);
    }

    @PostMapping("/{id}/deliverables/{deliverableId}/retry")
    public Map<String, Object> retryDeliverable(@PathVariable Long id, @PathVariable Long deliverableId) {
        goals.retryDeliverable(id, deliverableId);
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        Map<String, Object> detail = goals.detail(id);
        Goal g = (Goal) detail.get("goal");
        byte[] zip = goals.exportZip(id);
        String name = g.getTitle() == null ? "project" : g.getTitle().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + name + ".zip\"; filename*=UTF-8''"
                                + java.net.URLEncoder.encode(name + ".zip", StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "请求无效" : e.getMessage()));
    }
}
