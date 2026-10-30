package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.repo.ToolApprovalRepository;
import com.blankgrid.aione.tool.AiTool;
import com.blankgrid.aione.tool.ToolContext;
import com.blankgrid.aione.tool.ToolRegistry;
import com.blankgrid.aione.ws.EventPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 批准即执行。
 *
 * <p>这条链路最怕的是「批准了但没执行」:界面上状态变成已批准,模型那边永远等不到结果,
 * 下一轮巡视又提一张新单。所以这里断言的不是"返回了 ok",而是**工具真的被调了一次**、
 * 结果写回单子、并且落了一条公司动态。
 */
class ToolApprovalServiceTest {

    @TempDir
    Path tempDir;

    private ToolApprovalRepository repo;
    private ActivityRepository activityRepo;
    private ToolRegistry tools;
    private ToolApprovalService svc;
    private SettingsService settings;

    /** 真实实例:approve 后靠它记录"到底执行了没有" */
    static class EchoTool implements AiTool {
        volatile int calls = 0;
        volatile String lastArgs = "";

        @Override public String name() { return "echo"; }
        @Override public String description() { return "回显"; }
        @Override public String paramsSchema() { return "{}"; }
        @Override public String execute(String argsJson, ToolContext ctx) {
            calls++;
            lastArgs = argsJson;
            return "回显:" + argsJson;
        }
    }

    private EchoTool echo;

    @BeforeEach
    void setUp() {
        settings = new SettingsService(tempDir.toString());
        repo = mock(ToolApprovalRepository.class);
        activityRepo = mock(ActivityRepository.class);
        EventPusher pusher = mock(EventPusher.class);
        echo = new EchoTool();
        tools = new ToolRegistry(List.of(echo), settings, repo, mock(com.blankgrid.aione.repo.ToolCallLogRepository.class));
        svc = new ToolApprovalService(repo, tools, activityRepo, pusher, settings);
    }

    /** 让 findById 拿到的永远是同一个对象,save 就是更新它 —— 贴近 JPA 的真实行为 */
    private ToolApproval stub(ToolApproval approval) {
        AtomicReference<ToolApproval> ref = new AtomicReference<>(approval);
        when(repo.findById(approval.getId())).thenAnswer(i -> Optional.ofNullable(ref.get()));
        when(repo.save(any())).thenAnswer(i -> {
            ref.set(i.getArgument(0));
            return i.getArgument(0);
        });
        return approval;
    }

    private static ToolApproval pending(long id, String name, String args) {
        ToolApproval a = new ToolApproval(name, "test", args, "要看一眼", 3L, 5L, "CEO");
        try {
            var f = ToolApproval.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return a;
    }

    private void awaitStatus(ToolApproval a, String want) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (want.equals(a.getStatus())) return;
            Thread.sleep(50);
        }
        fail("等待状态 " + want + " 超时,当前:" + a.getStatus());
    }

    @Test
    @DisplayName("批准 → 拿原始参数执行一次 → 结果写回 → 落一条公司动态")
    void approveReplaysOriginalArgs() throws Exception {
        ToolApproval a = stub(pending(1L, "echo", "{\"q\":\"iPhone\"}"));

        Map<String, Object> r = svc.approve(1L);
        assertEquals(true, r.get("ok"));
        awaitStatus(a, ToolApproval.DONE);

        assertEquals(1, echo.calls, "批准了就必须真的执行,否则模型永远等不到结果");
        assertEquals("{\"q\":\"iPhone\"}", echo.lastArgs, "重放要用原来的参数,不能重新猜");
        assertTrue(a.getResult().contains("回显"), a.getResult());
        assertNotNull(a.getExecutedAt());

        ArgumentCaptor<Activity> cap = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepo).save(cap.capture());
        assertTrue(cap.getValue().getContent().contains("echo"), "结果要进公司动态,CEO 下一轮才读得到");
    }

    @Test
    @DisplayName("同一个单子不能批两次")
    void cannotApproveTwice() {
        ToolApproval a = stub(pending(2L, "echo", "{}"));
        svc.approve(2L);
        Map<String, Object> again = svc.approve(2L);
        assertEquals(false, again.get("ok"));
        assertTrue(String.valueOf(again.get("message")).contains("不能重复批准"));
    }

    @Test
    @DisplayName("拒绝:不执行,状态留痕")
    void rejectDoesNotExecute() {
        ToolApproval a = stub(pending(3L, "echo", "{}"));
        Map<String, Object> r = svc.reject(3L);
        assertEquals(true, r.get("ok"));
        assertEquals(ToolApproval.REJECTED, a.getStatus());
        assertEquals(0, echo.calls);
    }

    @Test
    @DisplayName("单子不存在时给一句人话,不抛异常")
    void missingApproval() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        Map<String, Object> r = svc.approve(99L);
        assertEquals(false, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("不存在"));
    }

    @Test
    @DisplayName("工具执行报错也要落成 FAILED,不能停在 RUNNING")
    void failureMarksFailed() throws Exception {
        SettingsService settings = new SettingsService(tempDir.toString());
        AiTool broken = new AiTool() {
            @Override public String name() { return "broken"; }
            @Override public String description() { return ""; }
            @Override public String paramsSchema() { return "{}"; }
            @Override public String execute(String argsJson, ToolContext ctx) throws Exception {
                throw new IllegalStateException("外部接口 500");
            }
        };
        ToolRegistry reg = new ToolRegistry(List.of(broken), settings, repo, mock(com.blankgrid.aione.repo.ToolCallLogRepository.class));
        ToolApprovalService s = new ToolApprovalService(repo, reg, activityRepo, mock(EventPusher.class), settings);
        ToolApproval a = new ToolApproval("broken", "test", "{}", "", null, null, "CEO");
        try {
            var f = ToolApproval.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, 4L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AtomicReference<ToolApproval> ref = new AtomicReference<>(a);
        when(repo.findById(4L)).thenAnswer(i -> Optional.ofNullable(ref.get()));
        when(repo.save(any())).thenAnswer(i -> {
            ref.set(i.getArgument(0));
            return i.getArgument(0);
        });

        s.approve(4L);
        awaitStatus(a, ToolApproval.FAILED);
        assertTrue(a.getResult().contains("500"), a.getResult());
        verify(repo, atLeast(1)).save(any());
        verify(activityRepo, timeout(2000)).save(any());
    }

    @Test
    @DisplayName("等太久的待批单自动作废,但不删除 —— 历史要能查")
    void staleApprovalsExpire() {
        ToolApproval old = pending(10L, "echo", "{\"old\":1}");
        old.setCreatedAt(java.time.Instant.now().minusSeconds(48 * 3600));
        ToolApproval fresh = pending(11L, "echo", "{\"new\":1}");
        when(repo.findByStatusAndCreatedAtBefore(eq(ToolApproval.PENDING), any()))
                .thenReturn(List.of(old));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.expireStale();

        assertEquals(ToolApproval.EXPIRED, old.getStatus());
        assertTrue(old.getResult().contains("自动作废"), old.getResult());
        assertEquals(ToolApproval.PENDING, fresh.getStatus(), "没到期的不能动");
        verify(repo, times(1)).save(any());
    }

    @Test
    @DisplayName("批量批准逐条走同一条链路,已处理过的跳过而不是整批失败")
    void batchApproveSkipsDecided() throws Exception {
        ToolApproval a = stub(pending(20L, "echo", "{\"n\":1}"));
        ToolApproval already = pending(21L, "echo", "{\"n\":2}");
        already.setStatus(ToolApproval.DONE);
        when(repo.findById(21L)).thenReturn(Optional.of(already));

        Map<String, Object> r = svc.batchApprove(List.of(20L, 21L));

        assertEquals(1, r.get("approved"));
        // 批准是异步执行的,先等状态落定再数调用次数 —— 否则数到的是"提交了没有"而不是"跑了没有"
        awaitStatus(a, ToolApproval.DONE);
        assertEquals(1, echo.calls, "只有真正待批的那条被执行");
        assertEquals(ToolApproval.DONE, already.getStatus(), "已处理的不该被重复执行");
    }

    @Test
    @DisplayName("拒绝带理由时写进公司动态,模型下一轮才知道为什么不行")
    void rejectWithReasonIsNarrated() {
        ToolApproval a = stub(pending(30L, "rea_reverse", "{}"));

        svc.reject(30L, "这个包不该在本机打开");

        assertEquals(ToolApproval.REJECTED, a.getStatus());
        assertTrue(a.getResult().contains("不该在本机打开"), a.getResult());
        ArgumentCaptor<Activity> cap = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepo).save(cap.capture());
        assertTrue(cap.getValue().getContent().contains("rea_reverse"),
                "光拒绝不说话,模型下一轮只会换个参数再申请一次");
    }
}
