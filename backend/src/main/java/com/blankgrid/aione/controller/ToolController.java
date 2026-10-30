package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.service.ToolApprovalService;
import com.blankgrid.aione.tool.ReaTool;
import com.blankgrid.aione.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具与自主调用相关的接口:工具清单、策略、待批调用、REA 自检。
 *
 * <p>这些端点共同回答一个问题:模型到底能自己动手做什么。
 * 所以要能看清「每个工具当前是什么策略」,而不只是看到一个总开关。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolApprovalService approvals;
    private final ReaTool reaTool;

    public ToolController(ToolRegistry toolRegistry, ToolApprovalService approvals, ReaTool reaTool) {
        this.toolRegistry = toolRegistry;
        this.approvals = approvals;
        this.reaTool = reaTool;
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

    @PostMapping("/approvals/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) {
        return approvals.approve(id);
    }

    @PostMapping("/approvals/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id) {
        return approvals.reject(id);
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
}
