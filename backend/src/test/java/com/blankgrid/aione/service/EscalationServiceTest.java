package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.Deliverable;
import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.entity.Workstream;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.repo.DeliverableRepository;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.GoalRepository;
import com.blankgrid.aione.repo.WorkstreamRepository;
import com.blankgrid.aione.ws.EventPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 上报的收敛:一件事一条,问题没了那条自己消失。
 *
 * <p>这组用例都对着真实的失败场景写:252 条 OPEN 上报里 92 条是同一句
 * 「余额不足」,54 条是同一个目标反复问「需要你先确认 N 件事」。
 */
class EscalationServiceTest {

    private ActivityRepository repo;
    private DeliverableRepository delRepo;
    private WorkstreamRepository wsRepo;
    private GoalRepository goalRepo;
    private EmployeeRepository empRepo;
    private EscalationService svc;

    @BeforeEach
    void setUp() {
        repo = mock(ActivityRepository.class);
        delRepo = mock(DeliverableRepository.class);
        wsRepo = mock(WorkstreamRepository.class);
        goalRepo = mock(GoalRepository.class);
        empRepo = mock(EmployeeRepository.class);
        when(repo.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));
        svc = new EscalationService(repo, delRepo, wsRepo, goalRepo, empRepo, mock(EventPusher.class));
    }

    private Activity act(String key, String content, String status) {
        Activity a = new Activity(Activity.ESCALATION, content, null);
        a.setDedupeKey(key);
        a.setStatus(status);
        a.setOccurrences(1);
        a.setLastSeenAt(Instant.now());
        return a;
    }

    // ==================== 一件事一条 ====================

    @Test
    @DisplayName("同一件事第二次上报:不新增,只更新原因、次数和最后时间")
    void sameSubjectUpdatesInsteadOfInserting() {
        Activity existing = act(EscalationService.LLM, "余额不足", Activity.OPEN);
        ReflectionTestUtils.setField(existing, "id", 7L);
        when(repo.findByDedupeKeyAndStatusOrderByIdDesc(EscalationService.LLM, Activity.OPEN))
                .thenReturn(List.of(existing));

        Activity out = svc.raise(EscalationService.LLM, null, "余额不足(再次确认)");

        assertEquals(7L, out.getId(), "还是原来那条,没有新建");
        assertEquals(2, out.getOccurrences());
        assertEquals("余额不足(再次确认)", out.getContent(), "展示最新一次的原因,而不是第一次的");
        verify(repo, never()).save(argThat(a -> a.getId() == null));
    }

    @Test
    @DisplayName("同一主体被老数据拆成多条 OPEN 时,留最新一条,其余标成已收敛")
    void duplicateOpenRowsCollapseToNewest() {
        Activity newest = act(EscalationService.ofDeliverable(53L), "第三次受阻", Activity.OPEN);
        ReflectionTestUtils.setField(newest, "id", 30L);
        Activity older = act(EscalationService.ofDeliverable(53L), "第一次受阻", Activity.OPEN);
        ReflectionTestUtils.setField(older, "id", 12L);
        when(repo.findByDedupeKeyAndStatusOrderByIdDesc(anyString(), anyString()))
                .thenReturn(List.of(newest, older));

        Activity out = svc.raise(EscalationService.ofDeliverable(53L), 6L, "第四次受阻");

        assertEquals(Activity.OPEN, out.getStatus());
        assertEquals(Activity.RESOLVED, older.getStatus(), "旧的那条不能再占待办位");
        assertTrue(older.getContent().contains("#30"), "要留痕指向合并到哪条,否则查无对证");
    }

    @Test
    @DisplayName("没有主体就不合并 —— 泛泛的上报本来就各说各的")
    void blankKeyStaysSeparate() {
        Activity a = svc.raise("", null, "本周经营情况概述");
        assertNull(a.getDedupeKey());
        verify(repo).save(any(Activity.class));
        verify(repo, never()).findByDedupeKeyAndStatusOrderByIdDesc(anyString(), anyString());
    }

    // ==================== 问题没了就关 ====================

    @Test
    @DisplayName("交付物通过之后,它挂的「受阻」自动关掉")
    void sweepClosesEscalationOfCompletedDeliverable() {
        Deliverable d = new Deliverable();
        ReflectionTestUtils.setField(d, "id", 53L);
        d.setStatus(Deliverable.DONE);
        when(delRepo.findById(53L)).thenReturn(Optional.of(d));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN))
                .thenReturn(List.of(act(EscalationService.ofDeliverable(53L), "受阻:72 分", Activity.OPEN)));

        assertEquals(1, svc.sweep());
    }

    @Test
    @DisplayName("还在受阻的不能关 —— 对账不是清空")
    void sweepKeepsLiveProblem() {
        Deliverable d = new Deliverable();
        ReflectionTestUtils.setField(d, "id", 53L);
        d.setStatus(Deliverable.BLOCKED);
        when(delRepo.findById(53L)).thenReturn(Optional.of(d));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN))
                .thenReturn(List.of(act(EscalationService.ofDeliverable(53L), "受阻", Activity.OPEN)));

        assertEquals(0, svc.sweep());
        assertEquals(Activity.OPEN, act(EscalationService.ofDeliverable(53L), "x", Activity.OPEN).getStatus());
    }

    @Test
    @DisplayName("重新跑起来也算好了:卡住这件事已经不成立")
    void sweepClosesWhenDeliverableIsRunningAgain() {
        Deliverable d = new Deliverable();
        ReflectionTestUtils.setField(d, "id", 53L);
        d.setStatus(Deliverable.RUNNING);
        when(delRepo.findById(53L)).thenReturn(Optional.of(d));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN))
                .thenReturn(List.of(act(EscalationService.ofDeliverable(53L), "受阻", Activity.OPEN)));

        assertEquals(1, svc.sweep(), "追加轮次后它已经在跑了,不该还挂着「受阻」");
    }

    @Test
    @DisplayName("目标暂停:缺料那种事可以关,预算用尽那条得留着")
    void pausedGoalResolvesFactsButKeepsBudget() {
        Goal g = new Goal();
        ReflectionTestUtils.setField(g, "id", 6L);
        g.setStatus(Goal.PAUSED);
        g.setNeedsInput("");
        when(goalRepo.findById(6L)).thenReturn(Optional.of(g));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)).thenReturn(List.of(
                act(EscalationService.ofGoal(6L, "facts"), "需要你先确认 4 件事", Activity.OPEN),
                act(EscalationService.ofGoal(6L, "budget"), "token 预算用尽", Activity.OPEN)));

        assertEquals(1, svc.sweep(), "只关缺料那条");
    }

    @Test
    @DisplayName("工作流不再是 BLOCKED 就关")
    void workstreamEscalationClosesWhenUnblocked() {
        Workstream ws = new Workstream();
        ReflectionTestUtils.setField(ws, "id", 12L);
        ws.setStatus(Workstream.ACTIVE);
        when(wsRepo.findById(12L)).thenReturn(Optional.of(ws));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN))
                .thenReturn(List.of(act(EscalationService.ofWorkstream(12L), "工作流受阻", Activity.OPEN)));

        assertEquals(1, svc.sweep());
    }

    @Test
    @DisplayName("对账必须幂等:关过的不会再被当成待办数一遍")
    void sweepOnlyLooksAtOpen() {
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)).thenReturn(List.of());
        assertEquals(0, svc.sweep());
        verify(repo).findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN);
    }

    @Test
    @DisplayName("CEO 泛泛上报没有主体键,对账不能瞎关 —— 那得创始人点头")
    void legacyWithoutKeyIsLeftAlone() {
        Activity a = act(null, "请拍板三件事", Activity.OPEN);
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)).thenReturn(List.of(a));

        assertEquals(0, svc.sweep());
        assertEquals(Activity.OPEN, a.getStatus());
    }

    // ==================== 历史数据 ====================

    @Test
    @DisplayName("老上报能靠正文里的标题认出主体,同一份交付物的多条合成一条")
    void backfillInfersDeliverableKeyAndMerges() {
        Deliverable d = new Deliverable();
        ReflectionTestUtils.setField(d, "id", 53L);
        d.setTitle("官网静态站技术方案");
        when(delRepo.findByTitleOrderByIdDesc("官网静态站技术方案")).thenReturn(List.of(d));

        Activity old = act(null, "交付物「官网静态站技术方案」受阻:评审未通过(72 分)", Activity.OPEN);
        ReflectionTestUtils.setField(old, "id", 90L);
        when(repo.findByTypeAndDedupeKeyIsNullOrderByIdAsc(Activity.ESCALATION)).thenReturn(List.of(old));
        when(repo.findByDedupeKeyAndStatusOrderByIdDesc(EscalationService.ofDeliverable(53L), Activity.OPEN))
                .thenReturn(List.of(old));

        assertEquals(1, svc.backfill());
        assertEquals(EscalationService.ofDeliverable(53L), old.getDedupeKey());
        assertNotNull(old.getLastSeenAt());
    }

    @Test
    @DisplayName("认不出主体的历史上报归进同一个「请拍板」桶 —— 措辞不同也算同一件事")
    void backfillPutsUnknownLegacyIntoAskBucket() {
        Activity a = act(null, "一件说不清是什么的事", Activity.OPEN);
        when(repo.findByTypeAndDedupeKeyIsNullOrderByIdAsc(Activity.ESCALATION)).thenReturn(List.of(a));

        svc.backfill();
        assertEquals(EscalationService.ofAsk(null), a.getDedupeKey(),
                "关键词匹配拦不住换措辞,只能按目标归并");
    }

    @Test
    @DisplayName("余额不足那条认成全局主体,不跟着目标走")
    void backfillRecognisesAccountProblem() {
        Activity a = act(null, "大模型账户余额不足,生产线已暂停调度", Activity.OPEN);
        when(repo.findByTypeAndDedupeKeyIsNullOrderByIdAsc(Activity.ESCALATION)).thenReturn(List.of(a));

        svc.backfill();
        assertEquals(EscalationService.LLM, a.getDedupeKey(), "这是全局问题,挂在某个目标上会跟着目标一起被关");
    }

    @Test
    @DisplayName("CEO 换着措辞问同一件事:按目标归并成一条,而不是四十条")
    void backfillMergesRewordedAsks() {
        when(delRepo.findByTitleOrderByIdDesc(anyString())).thenReturn(List.of());
        List<Activity> legacy = new ArrayList<>();
        String[] wordings = {
                "【请拍板】域名与备案号是多少?robots.txt 要用",
                "需创始人拍板两件事:1) 域名与备案号 2) 联系模块口径",
                "【第三次上报】三项对外事实各一句话即可解除空转:域名与备案号……",
                "请创始人在一件事上拍板:域名和备案信息",
        };
        for (int i = 0; i < wordings.length; i++) {
            Activity a = act(null, wordings[i], Activity.OPEN);
            ReflectionTestUtils.setField(a, "id", 100L + i);
            legacy.add(a);
        }
        when(repo.findByTypeAndDedupeKeyIsNullOrderByIdAsc(Activity.ESCALATION)).thenReturn(legacy);
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN))
                .thenReturn(new ArrayList<>(legacy));

        svc.backfill();

        for (Activity a : legacy) assertEquals(EscalationService.ofAsk(null), a.getDedupeKey(),
                "换措辞不该换主体");
        Activity newest = legacy.get(3);
        assertEquals(4, newest.getOccurrences(), "合并后要记住「这件事总共被问了几次」");
        assertEquals(Activity.OPEN, newest.getStatus());
        long open = legacy.stream().filter(a -> Activity.OPEN.equals(a.getStatus())).count();
        assertEquals(1, open, "只剩一条待办,其余标为已合并");
    }

    @Test
    @DisplayName("「需要你先确认 N 件事」这类不算拍板,按缺料走目标主体")
    void factsAskGoesToGoalFacts() {
        Activity a = act(null, "评审需要你先确认 2 件事,否则这部分成果只能靠编:ICP 备案号", Activity.OPEN);
        ReflectionTestUtils.setField(a, "id", 900L);
        a.setGoalId(6L);
        assertEquals(EscalationService.ofGoal(6L, "facts"), svc.inferKey(a));
    }

    @Test
    @DisplayName("目标暂停了,它名下的「请拍板」也不再是当下的待办")
    void pausedGoalSilencesAsk() {
        Goal g = new Goal();
        ReflectionTestUtils.setField(g, "id", 6L);
        g.setStatus(Goal.PAUSED);
        when(goalRepo.findById(6L)).thenReturn(Optional.of(g));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN))
                .thenReturn(List.of(act(EscalationService.ofAsk(6L), "请拍板三件事", Activity.OPEN)));

        assertEquals(1, svc.sweep());
    }

    @Test
    @DisplayName("反复上报的收件箱:按最后发生时间排序,最近又冒头的在最前")
    void openSortsByLastSeen() {
        Activity older = act("d:1", "旧事", Activity.OPEN);
        older.setLastSeenAt(Instant.now().minusSeconds(3600));
        Activity fresh = act("d:2", "刚又发生了一次", Activity.OPEN);
        fresh.setLastSeenAt(Instant.now());
        List<Activity> list = new ArrayList<>(List.of(older, fresh));
        when(repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)).thenReturn(list);

        List<Activity> out = svc.open();
        assertEquals("d:2", out.get(0).getDedupeKey());
    }

    @Test
    @DisplayName("关掉一条会推送新的计数,界面角标跟着变")
    void resolvePushesCount() {
        Activity a = act("d:9", "受阻", Activity.OPEN);
        when(repo.findByDedupeKeyAndStatusOrderByIdDesc("d:9", Activity.OPEN)).thenReturn(List.of(a));
        when(repo.countByTypeAndStatus(anyString(), anyString())).thenReturn(3L);

        assertEquals(1, svc.resolve("d:9"));
        assertEquals(Activity.RESOLVED, a.getStatus());
        verify(repo).countByTypeAndStatus(Activity.ESCALATION, Activity.OPEN);
    }
}
