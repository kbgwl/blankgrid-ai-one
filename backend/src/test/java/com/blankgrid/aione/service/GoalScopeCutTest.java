package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.Deliverable;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 砍掉一项交付的作用域后果。
 *
 * <p>核心是两条:砍掉之后那项不再拖累完成度,以及"全部砍光"不能算达成 ——
 * 前者决定创始人敢不敢砍,后者决定这个机制会不会被用来伪造一个成功的目标。
 */
class GoalScopeCutTest {

    private GoalRepository goalRepo;
    private WorkstreamRepository wsRepo;
    private DeliverableRepository delRepo;
    private ActivityRepository activityRepo;
    private ApplicationEventPublisher publisher;
    private GoalService svc;

    @BeforeEach
    void setUp() {
        goalRepo = mock(GoalRepository.class);
        wsRepo = mock(WorkstreamRepository.class);
        delRepo = mock(DeliverableRepository.class);
        activityRepo = mock(ActivityRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        CostService costs = mock(CostService.class);
        when(costs.ofGoal(any())).thenReturn(0.0);
        svc = new GoalService(goalRepo, wsRepo, delRepo, mock(ArtifactRepository.class),
                activityRepo, mock(TokenLogRepository.class), mock(EventPusher.class),
                publisher, new ObjectMapper(), costs, mock(EscalationService.class));
        when(goalRepo.save(any(Goal.class))).thenAnswer(i -> i.getArgument(0));
        when(delRepo.save(any(Deliverable.class))).thenAnswer(i -> i.getArgument(0));
        when(wsRepo.countByGoalId(anyLong())).thenReturn(1L);
        when(goalRepo.findById(anyLong())).thenAnswer(i -> Optional.of(goal()));
        when(wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(anyLong())).thenReturn(List.of());
    }

    private Goal goal() {
        Goal g = new Goal();
        // id 由数据库发号,实体上没有 setter —— 这里只能直接写字段
        org.springframework.test.util.ReflectionTestUtils.setField(g, "id", 1L);
        g.setTitle("给我写一款空白格官网");
        g.setStatus(Goal.REVIEW);
        return g;
    }

    private Deliverable deliverable(long id, String status, String title) {
        Deliverable d = new Deliverable();
        org.springframework.test.util.ReflectionTestUtils.setField(d, "id", id);
        d.setTitle(title);
        d.setStatus(status);
        d.setGoal(goal());
        d.setReviewNotes("72 分,论证还差一层收口");
        return d;
    }

    @Test
    @DisplayName("砍掉一项:状态改成已砍掉,理由和原评审意见都要留着")
    void cancelKeepsTheReason() {
        Deliverable d = deliverable(9L, Deliverable.BLOCKED, "技术方案");
        when(delRepo.findById(9L)).thenReturn(Optional.of(d));
        when(delRepo.findByGoalIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(d));

        svc.cancelDeliverable(1L, 9L, "外部接口不开放,这项不在关键路径上");

        assertEquals(Deliverable.CANCELLED, d.getStatus());
        assertTrue(d.getReviewNotes().contains("外部接口不开放"), "理由要落库,事后才查得到当时怎么想的");
        assertTrue(d.getReviewNotes().contains("72 分"), "原评审意见不能丢:砍掉之后这是唯一还能看的线索");
        // 砍掉要进公司动态,并且写明砍的是哪一项 —— 决策得留痕,不能悄悄消失
        verify(activityRepo, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(
                a -> a != null && a.getContent() != null && a.getContent().contains("砍掉「技术方案」")));
    }

    @Test
    @DisplayName("进度不把砍掉的算进分母,否则砍一项反而掉分,谁还敢砍")
    void cancelledDoesNotDragProgress() {
        // 4 项:2 项完成、1 项评审中、1 项砍掉 —— 分母只剩 3,进度 2/3 而不是 2/4
        when(delRepo.findByGoalIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                deliverable(1L, Deliverable.DONE, "A"),
                deliverable(2L, Deliverable.DONE, "B"),
                deliverable(3L, Deliverable.REVIEW, "C"),
                deliverable(4L, Deliverable.CANCELLED, "D")));

        Goal g = (Goal) svc.refreshProgress(1L).get("goal");
        assertEquals(67, g.getProgress(), "2/3 而不是 2/4");
    }

    @Test
    @DisplayName("执行中的不能砍:它跑出来的成果没人接,而任务还在烧钱")
    void cannotCancelRunning() {
        Deliverable d = deliverable(9L, Deliverable.RUNNING, "技术方案");
        when(delRepo.findById(9L)).thenReturn(Optional.of(d));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.cancelDeliverable(1L, 9L, "不想要了"));
        assertTrue(e.getMessage().contains("正在执行"));
        assertEquals(Deliverable.RUNNING, d.getStatus());
    }

    @Test
    @DisplayName("已通过的不能砍:归档的东西不能事后抹掉")
    void cannotCancelDone() {
        Deliverable d = deliverable(9L, Deliverable.DONE, "给力方案");
        when(delRepo.findById(9L)).thenReturn(Optional.of(d));
        assertThrows(IllegalArgumentException.class, () -> svc.cancelDeliverable(1L, 9L, "反悔了"));
    }

    @Test
    @DisplayName("别的目标的交付物,借 id 砍不掉")
    void cannotCancelAcrossGoals() {
        Deliverable d = deliverable(9L, Deliverable.BLOCKED, "别人的活");
        org.springframework.test.util.ReflectionTestUtils.setField(d.getGoal(), "id", 2L);
        when(delRepo.findById(9L)).thenReturn(Optional.of(d));
        assertThrows(IllegalArgumentException.class, () -> svc.cancelDeliverable(1L, 9L, "试试"));
    }

    @Test
    @DisplayName("暂停中的目标:砍掉一项不能把它悄悄恢复 —— 暂停是「先别花钱」")
    void pausedGoalStaysPaused() {
        Goal paused = goal();
        paused.setStatus(Goal.PAUSED);
        when(goalRepo.findById(1L)).thenReturn(Optional.of(paused));
        Deliverable d = deliverable(9L, Deliverable.BLOCKED, "技术方案");
        when(delRepo.findById(9L)).thenReturn(Optional.of(d));
        when(delRepo.findByGoalIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(d));

        svc.cancelDeliverable(1L, 9L, "这项不做了");

        assertEquals(Goal.PAUSED, paused.getStatus(), "砍掉一项不等于替创始人按下恢复");
        verify(publisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    @DisplayName("归档:已完成 + 已砍掉可以结项,还有未完成的就不行")
    void acceptCountsCancelledAsSettled() {
        when(goalRepo.findById(1L)).thenReturn(Optional.of(goal()));
        when(delRepo.findByGoalIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                deliverable(1L, Deliverable.DONE, "A"),
                deliverable(2L, Deliverable.CANCELLED, "B")));
        assertEquals(Goal.DONE, svc.accept(1L).getStatus());

        when(delRepo.findByGoalIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
                deliverable(1L, Deliverable.DONE, "A"),
                deliverable(2L, Deliverable.BLOCKED, "B")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.accept(1L));
        assertTrue(e.getMessage().contains("1 项"), "要告诉创始人还差几项:" + e.getMessage());
    }
}
