package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.entity.ToolCallLog;
import com.blankgrid.aione.repo.ToolCallLogRepository;
import com.blankgrid.aione.service.ToolApprovalService;
import com.blankgrid.aione.tool.ReaTool;
import com.blankgrid.aione.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具与自主调用相关的接口:工具清单、策略、待批调用、审计流水、REA 自检。
 *
 * <p>这些端点共同回答一个问题:模型到底能自己动手做什么。
 * 所以要能看清「每个工具当前是什么策略」,而不只是看到一个总开关;
 * 也要能翻出「它昨天到底查了什么、花了多久」。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolApprovalService approvals;
    private final ReaTool reaTool;
    private final ToolCallLogRepository callLogs;

    public ToolController(ToolRegistry toolRegistry, ToolApprovalService approvals,
                          ReaTool reaTool, ToolCallLogRepository callLogs) {
        this.toolRegistry = toolRegistry;
        this.approvals = approvals;
        this.reaTool = reaTool;
        this.callLogs = callLogs;
    }

    /** 工具清单 + 当前生效策略 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return toolRegistry.describe();
    }

    /** 待批 + 最近处理过的调用 */
    @GetMapping("/approvals")
    public Map<String, Object> approvals(@RequestParam(required = false, defaultValue = "pending") String scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<ToolApproval> rows = "all".equals(scope) ? approvals.recent() : approvals.pending();
        out.put("pending", approvals.pendingCount());
        out.put("items", rows);
        return out;
    }

    /**
     * 只在导航上要一个数字时用这个:整张列表在轮询里太重。
     * 前端每 30 秒问一次,拿到数字决定要不要亮角标。
     */
    @GetMapping("/approvals/count")
    public Map<String, Object> approvalCount() {
        return Map.of("pending", approvals.pendingCount());
    }

    @PostMapping("/approvals/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) {
        return approvals.approve(id);
    }

    @PostMapping("/approvals/batch-approve")
    public Map<String, Object> batchApprove(@RequestBody BatchBody body) {
        return approvals.batchApprove(body == null ? List.of() : body.ids);
    }

    @PostMapping("/approvals/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestBody(required = false) RejectBody body) {
        return approvals.reject(id, body == null ? "" : body.reason);
    }

    /**
     * 工具调用审计流水。
     *
     * <p>回答的是"它到底干了什么":谁调的、什么时候、花了多久、成没成。
     * 不落这张表的话,只能从公司动态里猜 —— 而公司动态只记批准过的那几次。
     */
    @GetMapping("/calls")
    public Map<String, Object> calls(@RequestParam(required = false, defaultValue = "0") int hours) {
        List<ToolCallLog> rows = hours > 0
                ? callLogs.findByCreatedAtAfterOrderByCreatedAtDescIdDesc(Instant.now().minus(hours, ChronoUnit.HOURS))
                : callLogs.findTop100ByOrderByIdDesc();
        if (rows.size() > 200) rows = rows.subList(0, 200);

        // 顺手按工具聚合一下:一个工具反复失败,一眼就能看见
        Map<String, Map<String, Object>> byTool = new LinkedHashMap<>();
        for (ToolCallLog r : rows) {
            Map<String, Object> agg = byTool.computeIfAbsent(r.getToolName(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", k);
                m.put("calls", 0);
                m.put("fails", 0);
                m.put("ms", 0L);
                return m;
            });
            agg.put("calls", (int) agg.get("calls") + 1);
            if (!r.isOk()) agg.put("fails", (int) agg.get("fails") + 1);
            agg.put("ms", (long) agg.get("ms") + r.getMs());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("items", rows);
        out.put("byTool", new ArrayList<>(byTool.values()));
        return out;
    }

    /** REA 工具链自检:真跑一次 doctor,比"已启用"三个字有用 */
    @PostMapping("/rea/test")
    public Map<String, Object> testRea() {
        long t0 = System.currentTimeMillis();
        String detail = reaTool.selfTest();
        Map<String, Object> out = new LinkedHashMap<>(reaTool.status());
        // 三态要分清:命令没跑起来 ≠ 跑起来了但环境缺件。
        // 实测 doctor 在环境不全时也是 exit 1,但它给出的正是一份"缺什么、怎么补"的完整报告。
        boolean healthy = detail.startsWith("工具链正常");
        boolean ran = healthy || detail.startsWith("自检完成");
        out.put("ok", ran);
        out.put("healthy", healthy);
        out.put("detail", detail);
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
    }

    /** REA 当前配置状态,设置页展示用 */
    @GetMapping("/rea")
    public Map<String, Object> reaStatus() {
        return reaTool.status();
    }

    public static class BatchBody {
        public List<Long> ids;
    }

    public static class RejectBody {
        public String reason;
    }
}
