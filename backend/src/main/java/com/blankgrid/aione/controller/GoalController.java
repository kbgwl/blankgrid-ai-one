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

    /** 把整个项目写成磁盘上的文件(目录由设置页决定),返回落地路径。format=all 时四种格式都写。 */
    @PostMapping("/{id}/export-to-dir")
    public Map<String, Object> exportToDir(@PathVariable Long id,
                                           @RequestParam(required = false) String format) {
        try {
            return exports.exportGoal(id, format);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "写盘失败:" + e.getMessage());
        }
    }

    @PostMapping("/{id}/replan")
    public Map<String, Object> replan(@PathVariable Long id) {
        engine.requestGoalPlan(id);
        return Map.of("ok", true);
    }

    /** 一键把受阻/挂起的交付物全部推回队列(充值、换模型、重启之后用)。 */
    @PostMapping("/{id}/retry-blocked")
    public Map<String, Object> retryBlocked(@PathVariable Long id) {
        return goals.retryBlocked(id);
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

    /**
     * 追加返工轮次:返工用尽之后的第三条路。
     *
     * <p>重试会把 attempts 归零(白送满额轮次),这里是"再给一轮" ——
     * 上限从全局轮次往上抬,历史轮次仍然算数。
     */
    @PostMapping("/{id}/deliverables/{deliverableId}/grant-rounds")
    public Map<String, Object> grantRounds(@PathVariable Long id, @PathVariable Long deliverableId,
                                           @RequestBody(required = false) GrantBody body) {
        int cap = engine.grantRounds(id, deliverableId,
                body == null || body.rounds <= 0 ? 1 : body.rounds,
                body == null ? "" : body.reason);
        return Map.of("ok", true, "cap", cap);
    }

    public static class GrantBody {
        public int rounds = 1;
        public String reason = "";
    }

    /**
     * 砍掉一项交付。
     *
     * <p>目标结项的前提是"所有交付要么通过、要么砍掉"。没有这个出口,
     * 一项反复不过的活就能把整个目标永久钉在 REVIEW 上 —— 剩下的干得再好也没用。
     */
    @PostMapping("/{id}/deliverables/{deliverableId}/cancel")
    public Map<String, Object> cancelDeliverable(@PathVariable Long id, @PathVariable Long deliverableId,
                                                 @RequestBody(required = false) CancelBody body) {
        var d = goals.cancelDeliverable(id, deliverableId, body == null ? null : body.reason);
        return Map.of("ok", true, "status", d.getStatus());
    }

    public static class CancelBody {
        public String reason = "";
    }

    /**
     * 打包下载:默认 zip(原样的 Markdown 工程包);
     * ?format=docx|pdf|pptx 则整本项目合成一个文件直接下载。
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(required = false) String format) {
        Map<String, Object> detail = goals.detail(id);
        Goal g = (Goal) detail.get("goal");
        String base = g.getTitle() == null ? "project" : g.getTitle().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        String fmt = format == null ? "" : format.trim().toLowerCase();

        if (fmt.isEmpty() || "zip".equals(fmt)) {
            byte[] zip = goals.exportZip(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + base + ".zip\"; filename*=UTF-8''"
                                    + java.net.URLEncoder.encode(base + ".zip", StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zip);
        }

        String ext = com.blankgrid.aione.service.DocExportService.extension(fmt);
        byte[] body;
        try {
            body = exports.goalBytes(id, ext);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
        String name = base + "-全书." + ext;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(com.blankgrid.aione.service.ExportService.contentType(ext)))
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "请求无效" : e.getMessage()));
    }
}
