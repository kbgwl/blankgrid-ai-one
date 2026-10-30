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
    private com.blankgrid.aione.repo.ToolCallLogRepository calls;

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
        calls = mock(com.blankgrid.aione.repo.ToolCallLogRepository.class);
    }

    private ToolRegistry registry(AiTool... tools) {
        return new ToolRegistry(List.of(tools), settings, approvals, calls);
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
    @DisplayName("决策层能看到「需批准」的工具,否则它连申请都发不出来")
    void decisionSeesEverythingUsable() {
        CountingTool ok = new CountingTool("d_ok", true, true, "auto");
        CountingTool write = new CountingTool("d_write", true, false, "auto");
        CountingTool notAvailable = new CountingTool("d_na", false, true, "auto");
        CountingTool needsApproval = new CountingTool("d_confirm", true, true, "confirm");
        CountingTool closed = new CountingTool("d_off", true, true, "off");
        ToolRegistry r = registry(ok, write, notAvailable, needsApproval, closed);

        List<String> names = r.decisionTools().stream().map(AiTool::name).toList();
        assertEquals(List.of("d_ok", "d_write", "d_confirm"), names,
                "不可用的和已关闭的不该出现;需批准、有副作用的必须出现 —— 看不见就申请不了,"
                        + "需要授权的动作就只能靠岗位偶然撞上,那不是自主决策");
    }

    @Test
    @DisplayName("决策层申请需批准的工具:落单,但不执行")
    void decisionQueuesConfirmTool() {
        CountingTool t = new CountingTool("d_rev", true, true, "confirm");
        ToolRegistry r = registry(t);

        String out = r.invokeForDecision("d_rev", "{\"action\":\"doctor\"}", ToolContext.empty(), "要拆这个包");

        assertEquals(0, t.calls.get(), "申请 ≠ 执行");
        assertTrue(out.contains("已提交审批单"), out);
        ArgumentCaptor<ToolApproval> cap = ArgumentCaptor.forClass(ToolApproval.class);
        verify(approvals).save(cap.capture());
        assertEquals("要拆这个包", cap.getValue().getReason(),
                "申请理由必须留下,否则审批人只看到一行参数,不知道该不该批");
        assertEquals("{\"action\":\"doctor\"}", cap.getValue().getArgsJson(),
                "批准时要按原参数重放,参数不能丢");
    }

    @Test
    @DisplayName("决策层不能直接执行有副作用的工具,哪怕它被设成了 auto")
    void decisionCannotWriteEvenWhenAuto() {
        CountingTool w = new CountingTool("d_write", true, false, "auto");
        ToolRegistry r = registry(w);

        String out = r.invokeForDecision("d_write", "{}", ToolContext.empty(), "顺手改一下");

        assertEquals(0, w.calls.get(),
                "在「取证」这个名义下改状态,事后翻记录只会看到「CEO 查了一下」,实际它已经动了东西");
        assertTrue(out.contains("需创始人批准"), out);
    }

    @Test
    @DisplayName("决策层调只读工具照常执行,并留审计")
    void decisionReadRunsAndIsAudited() {
        CountingTool t = new CountingTool("d_read", true, true, "auto");
        ToolRegistry r = registry(t);

        assertEquals("执行了", r.invokeForDecision("d_read", "{}", ToolContext.empty(), ""));
        assertEquals(1, t.calls.get());

        ArgumentCaptor<com.blankgrid.aione.entity.ToolCallLog> cap =
                ArgumentCaptor.forClass(com.blankgrid.aione.entity.ToolCallLog.class);
        verify(calls).save(cap.capture());
        com.blankgrid.aione.entity.ToolCallLog log = cap.getValue();
        assertEquals("d_read", log.getToolName());
        assertEquals(com.blankgrid.aione.entity.ToolCallLog.SOURCE_DECISION, log.getSource());
        assertTrue(log.isOk());
        assertTrue(log.getResultPreview().contains("执行了"));
    }

    @Test
    @DisplayName("审计写失败不能把成功的工具调用变成失败")
    void auditFailureIsSwallowed() {
        CountingTool t = new CountingTool("d_x", true, true, "auto");
        when(calls.save(any())).thenThrow(new RuntimeException("磁盘满了"));
        ToolRegistry r = registry(t);

        assertEquals("执行了", r.invoke("d_x", "{}", ToolContext.empty()),
                "审计是旁路:记账出问题不该拖垮业务");
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
    @DisplayName("同一轮里同样的只读请求只真跑一次,参数不同不命中")
    void roundCacheReusesReadOnly() {
        CountingTool t = new CountingTool("c_read", true, true, "auto");
        ToolRegistry r = registry(t);

        r.invoke("c_read", "{\"q\":1}", ToolContext.empty());
        String second = r.invoke("c_read", "{\"q\":1}", ToolContext.empty());
        assertEquals(1, t.calls.get(), "同样的请求不该跑第二遍");
        assertTrue(second.contains("复用上次结果"), second);

        r.invoke("c_read", "{\"q\":2}", ToolContext.empty());
        assertEquals(2, t.calls.get(), "参数不同是另一件事,不能拿旧结果糊弄");
    }

    @Test
    @DisplayName("有副作用的工具不缓存:写两次就是写两次")
    void noCacheForWriteTools() {
        CountingTool w = new CountingTool("c_write", true, false, "auto");
        ToolRegistry r = registry(w);

        r.invoke("c_write", "{}", ToolContext.empty());
        r.invoke("c_write", "{}", ToolContext.empty());

        assertEquals(2, w.calls.get(), "把写操作缓存起来,等于悄悄吞掉一次执行");
    }

    @Test
    @DisplayName("失败不缓存:下次同样的调用也许就成功了")
    void failureIsNotCached() {
        AtomicInteger n = new AtomicInteger();
        AiTool flaky = new AiTool() {
            @Override public String name() { return "c_flaky"; }
            @Override public String description() { return "时好时坏"; }
            @Override public String paramsSchema() { return "{}"; }
            @Override public String execute(String argsJson, ToolContext ctx) {
                if (n.incrementAndGet() == 1) throw new IllegalStateException("外部接口 502");
                return "成功了";
            }
        };
        ToolRegistry r = registry(flaky);

        String first = r.invoke("c_flaky", "{}", ToolContext.empty());
        assertTrue(first.contains("失败"), first);
        assertEquals("成功了", r.invoke("c_flaky", "{}", ToolContext.empty()),
                "把失败缓存住,模型就再也没机会重试");
        assertEquals(2, n.get());
    }

    @Test
    @DisplayName("新一轮开始清空缓存,上一单的查证结果不会当这一单的现状")
    void resetClearsCache() {
        CountingTool t = new CountingTool("c_reset", true, true, "auto");
        ToolRegistry r = registry(t);

        r.invoke("c_reset", "{}", ToolContext.empty());
        r.resetStats();
        r.invoke("c_reset", "{}", ToolContext.empty());

        assertEquals(2, t.calls.get(), "跨任务复用就是拿过期数据当现状");
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
