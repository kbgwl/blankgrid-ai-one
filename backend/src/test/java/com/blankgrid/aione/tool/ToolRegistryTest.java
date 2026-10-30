package com.blankgrid.aione.tool;

import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.repo.ToolApprovalRepository;
import com.blankgrid.aione.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 工具授权的裁决点。
 *
 * <p>这里测的不是「能不能调通」,而是三种策略在真实调用路径上的表现:
 * off 必须彻底拦住、confirm 必须落单而不是静默执行、auto 才真的执行。
 * 这三条一旦错了,表现在界面上是"设置已保存",表现在生产上是"它自己动了"。
 */
class ToolRegistryTest {

    @TempDir
    Path tempDir;

    private SettingsService settings;
    private ToolApprovalRepository approvals;

    /** 记录执行次数:策略拦截的判据是"根本没执行",不是"返回了错误文案" */
    static class CountingTool implements AiTool {
        final AtomicInteger calls = new AtomicInteger();
        final String name;
        final boolean available;
        final boolean decisionSafe;
        final String defaultPolicy;

        CountingTool(String name, boolean available, boolean decisionSafe, String defaultPolicy) {
            this.name = name;
            this.available = available;
            this.decisionSafe = decisionSafe;
            this.defaultPolicy = defaultPolicy;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "测试工具"; }
        @Override public String paramsSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public String execute(String argsJson, ToolContext ctx) {
            calls.incrementAndGet();
            return "执行了";
        }
        @Override public boolean available() { return available; }
        @Override public String unavailableReason() { return available ? "" : "没配"; }
        @Override public boolean decisionSafe() { return decisionSafe; }
        @Override public String defaultPolicy() { return defaultPolicy; }
    }

    @BeforeEach
    void setUp() {
        settings = new SettingsService(tempDir.toString());
        approvals = mock(ToolApprovalRepository.class);
        when(approvals.existsByToolNameAndArgsJsonAndStatus(anyString(), anyString(), anyString())).thenReturn(false);
        when(approvals.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ToolRegistry registry(AiTool... tools) {
        return new ToolRegistry(List.of(tools), settings, approvals);
    }

    private void policy(Map<String, String> map) {
        SettingsService.Settings in = new SettingsService.Settings();
        in.toolPolicy = map;
        settings.save(in);
    }

    @Test
    @DisplayName("auto:直接执行")
    void autoExecutes() {
        CountingTool t = new CountingTool("t_auto", true, true, "auto");
        ToolRegistry r = registry(t);
        assertEquals("执行了", r.invoke("t_auto", "{}", ToolContext.empty()));
        assertEquals(1, t.calls.get());
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("confirm:落一条待批单,但不执行")
    void confirmQueuesWithoutExecuting() {
        CountingTool t = new CountingTool("t_confirm", true, true, "confirm");
        ToolRegistry r = registry(t);

        String out = r.invoke("t_confirm", "{\"a\":1}", ToolContext.of(7L, 9L), "我需要核价");

        assertEquals(0, t.calls.get(), "没批准就不能执行,否则 confirm 形同虚设");
        assertTrue(out.contains("需创始人批准"), "要告诉模型在等批准:" + out);

        ArgumentCaptor<ToolApproval> cap = ArgumentCaptor.forClass(ToolApproval.class);
        verify(approvals).save(cap.capture());
        ToolApproval a = cap.getValue();
        assertEquals("t_confirm", a.getToolName());
        assertEquals("{\"a\":1}", a.getArgsJson(), "原始参数要存下来,批准时按它重放");
        assertEquals(7L, a.getGoalId());
        assertEquals(9L, a.getTaskId());
        assertEquals("我需要核价", a.getReason());
        assertEquals(ToolApproval.PENDING, a.getStatus());
    }

    @Test
    @DisplayName("confirm:同样的参数不重复排队,避免每轮巡视塞一条一样的单")
    void confirmDeduplicates() {
        CountingTool t = new CountingTool("t_dup", true, true, "confirm");
        ToolRegistry r = registry(t);
        when(approvals.existsByToolNameAndArgsJsonAndStatus("t_dup", "{}", ToolApproval.PENDING)).thenReturn(true);

        String out = r.invoke("t_dup", "{}", ToolContext.empty());
        assertTrue(out.contains("已在待批队列"), out);
        verify(approvals, never()).save(any());
    }

    @Test
    @DisplayName("off:拒绝且不执行")
    void offBlocks() {
        CountingTool t = new CountingTool("t_off", true, true, "auto");
        ToolRegistry r = registry(t);
        policy(Map.of("t_off", "off"));

        String out = r.invoke("t_off", "{}", ToolContext.empty());
        assertEquals(0, t.calls.get());
        assertTrue(out.contains("已被创始人关闭"), out);
    }

    @Test
    @DisplayName("不可用的工具不执行,并且把缺什么说清楚")
    void unavailableExplainsWhy() {
        CountingTool t = new CountingTool("t_na", false, false, "auto");
        ToolRegistry r = registry(t);
        String out = r.invoke("t_na", "{}", ToolContext.empty());
        assertEquals(0, t.calls.get());
        assertTrue(out.contains("没配"), out);
    }

    @Test
    @DisplayName("决策层只看到:可用 + 决策安全 + 全自动")
    void decisionToolsFiltered() {
        CountingTool ok = new CountingTool("d_ok", true, true, "auto");
        CountingTool notSafe = new CountingTool("d_write", true, false, "auto");
        CountingTool notAvailable = new CountingTool("d_na", false, true, "auto");
        CountingTool needsApproval = new CountingTool("d_confirm", true, true, "confirm");
        ToolRegistry r = registry(ok, notSafe, notAvailable, needsApproval);

        List<String> names = r.decisionTools().stream().map(AiTool::name).toList();
        assertEquals(List.of("d_ok"), names,
                "写操作、不可用、需批准的一律不进决策层 —— 人不在场,拿到也只能干等");
    }

    @Test
    @DisplayName("全局 * 能把工具自带的 confirm 放开,这是「全部自动」的操作方式")
    void globalOverrideOpensConfirmTool() {
        CountingTool t = new CountingTool("t_rev", true, true, "confirm");
        ToolRegistry r = registry(t);
        policy(new LinkedHashMap<>(Map.of("*", "auto")));

        assertEquals("执行了", r.invoke("t_rev", "{}", ToolContext.empty()));
        assertEquals(1, t.calls.get());
    }

    @Test
    @DisplayName("未知工具名不炸,回一句可用的清单")
    void unknownTool() {
        ToolRegistry r = registry(new CountingTool("known", true, true, "auto"));
        String out = r.invoke("nope", "{}", ToolContext.empty());
        assertTrue(out.contains("没有名为 nope"), out);
        assertTrue(out.contains("known"), out);
    }
}
