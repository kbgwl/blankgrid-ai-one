package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.repo.ArtifactRepository;
import com.blankgrid.aione.repo.DeliverableRepository;
import com.blankgrid.aione.repo.GoalRepository;
import com.blankgrid.aione.repo.TokenLogRepository;
import com.blankgrid.aione.repo.WorkstreamRepository;
import com.blankgrid.aione.ws.EventPusher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 追加预算的行为:钱到位就该接着跑,而不是让人再点一次「恢复」。 */
class GoalServiceBudgetTest {

    private GoalRepository goalRepo;
    private WorkstreamRepository wsRepo;
    private ActivityRepository activityRepo;
    private GoalService svc;

    @BeforeEach
    void setUp() {
        goalRepo = mock(GoalRepository.class);
        wsRepo = mock(WorkstreamRepository.class);
        activityRepo = mock(ActivityRepository.class);
        svc = new GoalService(goalRepo, wsRepo,
                mock(DeliverableRepository.class), mock(ArtifactRepository.class),
                activityRepo, mock(TokenLogRepository.class),
                mock(EventPusher.class), mock(ApplicationEventPublisher.class), new ObjectMapper(),
                costMock(), mock(EscalationService.class));
        when(goalRepo.save(any(Goal.class))).thenAnswer(i -> i.getArgument(0));
        when(wsRepo.countByGoalId(anyLong())).thenReturn(1L);
    }

    /** 成本折算与预算逻辑无关,这里只要不 NPE 即可 */
    private CostService costMock() {
        CostService c = mock(CostService.class);
        when(c.ofGoal(any())).thenReturn(0.0);
        return c;
    }

    private Goal budgetPaused() {
        Goal g = new Goal();
        g.setTitle("测试目标");
        g.setBudgetTokens(200_000);
        g.setSpentTokens(200_000);
        g.setStatus(Goal.PAUSED);
        g.setCeoSummary("目标 token 预算已用尽,已自动暂停。");
        return g;
    }

    @Test
    @DisplayName("追加预算:额度累加,目标从预算暂停里自动恢复")
    void addBudgetResumesGoal() {
        Goal g = budgetPaused();
        when(goalRepo.findById(1L)).thenReturn(Optional.of(g));

        Goal after = svc.addBudget(1L, 100_000);

        assertEquals(300_000, after.getBudgetTokens(), "追加是累加,不是覆盖");
        assertEquals(Goal.ACTIVE, after.getStatus(), "有钱了就该继续,不该让人再点恢复");
        assertTrue(after.getCeoSummary() == null || after.getCeoSummary().isBlank(), "预算提示要清掉,否则界面一直挂着红色横幅");
        verify(activityRepo).save(any(Activity.class));
    }

    @Test
    @DisplayName("人工暂停的目标不会被追加预算顺手唤醒")
    void addBudgetDoesNotResumeManualPause() {
        Goal g = budgetPaused();
        g.setCeoSummary("创始人手动暂停");
        when(goalRepo.findById(1L)).thenReturn(Optional.of(g));

        Goal after = svc.addBudget(1L, 50_000);

        assertEquals(Goal.PAUSED, after.getStatus(), "人暂停的就是要停着,不能因为加了额度就自己跑起来");
        assertEquals(250_000, after.getBudgetTokens());
    }

    @Test
    @DisplayName("追加额度必须为正数")
    void addBudgetRejectsNonPositive() {
        when(goalRepo.findById(1L)).thenReturn(Optional.of(budgetPaused()));
        assertThrows(IllegalArgumentException.class, () -> svc.addBudget(1L, 0));
        assertThrows(IllegalArgumentException.class, () -> svc.addBudget(1L, -1));
    }

    @Test
    @DisplayName("目标不存在时给出可读错误,而不是空指针")
    void addBudgetUnknownGoal() {
        when(goalRepo.findById(9L)).thenReturn(Optional.empty());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.addBudget(9L, 10_000));
        assertEquals("目标不存在", e.getMessage());
    }
}
