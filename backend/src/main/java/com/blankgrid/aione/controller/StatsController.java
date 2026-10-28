package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Artifact;
import com.blankgrid.aione.entity.Deliverable;
import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.repo.ArtifactRepository;
import com.blankgrid.aione.repo.DeliverableRepository;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.GoalRepository;
import com.blankgrid.aione.repo.TaskRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TaskRepository taskRepo;
    private final EmployeeRepository empRepo;
    private final com.blankgrid.aione.repo.TokenLogRepository tokenRepo;
    private final com.blankgrid.aione.service.SettingsService settingsService;
    private final GoalRepository goalRepo;
    private final DeliverableRepository delRepo;
    private final ArtifactRepository artRepo;

    public StatsController(TaskRepository taskRepo, EmployeeRepository empRepo,
                           com.blankgrid.aione.repo.TokenLogRepository tokenRepo,
                           com.blankgrid.aione.service.SettingsService settingsService,
                           GoalRepository goalRepo, DeliverableRepository delRepo,
                           ArtifactRepository artRepo) {
        this.taskRepo = taskRepo;
        this.empRepo = empRepo;
        this.tokenRepo = tokenRepo;
        this.settingsService = settingsService;
        this.goalRepo = goalRepo;
        this.delRepo = delRepo;
        this.artRepo = artRepo;
    }

    @GetMapping
    public Map<String, Object> stats() {
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", taskRepo.countByStatus(Task.PENDING));
        m.put("running", taskRepo.countByStatus(Task.RUNNING));
        m.put("done", taskRepo.countByStatus(Task.DONE));
        m.put("failed", taskRepo.countByStatus(Task.FAILED));
        m.put("doneToday", taskRepo.countByStatusAndCreatedAtAfter(Task.DONE, dayStart));
        m.put("totalEmployees", empRepo.count());
        long tokensToday = tokenRepo.tokensSince(dayStart);
        m.put("tokensTotal", tokenRepo.totalTokens());
        m.put("tokensToday", tokensToday);
        long budget = settingsService.get().dailyTokenBudget;
        m.put("dailyTokenBudget", budget);
        m.put("budgetExceeded", budget > 0 && tokensToday >= budget);

        List<Goal> goals = goalRepo.findAllByOrderByCreatedAtDesc();
        m.put("goalsTotal", goals.size());
        m.put("goalsActive", goals.stream().filter(g -> Goal.ACTIVE.equals(g.getStatus())).count());
        m.put("goalsDone", goals.stream().filter(g -> Goal.DONE.equals(g.getStatus())).count());
        m.put("goalsReview", goals.stream().filter(g -> Goal.REVIEW.equals(g.getStatus())).count());
        m.put("activeEmployees", empRepo.findAll().stream()
                .filter(e -> !e.isCeo() && !Employee.STATUS_DISABLED.equals(e.getStatus())).count());
        m.put("deliverables", delRepo.count());
        m.put("deliverablesDone", delRepo.findByStatusIn(List.of(Deliverable.DONE)).size());
        m.put("artifactsTotal", artRepo.count());
        m.put("artifactsApproved", artRepo.countByStatus(Artifact.APPROVED));
        m.put("qualityThreshold", settingsService.get().reviewThreshold);
        m.put("hasProvider", settingsService.hasProvider());
        return m;
    }
}
