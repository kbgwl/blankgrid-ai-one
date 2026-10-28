package com.blankgrid.aione.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * CEO 拆出的部门级工作流:由一个部门负责人承接,再拆成具体交付物。
 */
@Entity
@Table(name = "workstreams")
public class Workstream {

    public static final String PLANNING = "PLANNING";
    public static final String ACTIVE = "ACTIVE";
    public static final String DONE = "DONE";
    public static final String BLOCKED = "BLOCKED";
    public static final String PAUSED = "PAUSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "goal_id")
    private Goal goal;

    @ManyToOne
    @JoinColumn(name = "dept_id")
    private Department department;

    /** 承接该工作流的部门负责人 */
    @ManyToOne
    @JoinColumn(name = "lead_id")
    private Employee lead;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 8000)
    private String brief;

    /** 工作流整体验收标准 */
    @Column(length = 8000)
    private String acceptanceCriteria;

    /** PLANNING / ACTIVE / DONE / BLOCKED / PAUSED */
    private String status = PLANNING;

    private int progress = 0;
    private int sortOrder = 0;

    @Column(length = 2000)
    private String planSummary = "";

    private Instant dueAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Workstream() {}

    public Long getGoalId() { return goal == null ? null : goal.getId(); }

    public Long getId() { return id; }
    public Goal getGoal() { return goal; }
    public void setGoal(Goal goal) { this.goal = goal; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public Employee getLead() { return lead; }
    public void setLead(Employee lead) { this.lead = lead; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public String getAcceptanceCriteria() { return acceptanceCriteria; }
    public void setAcceptanceCriteria(String acceptanceCriteria) { this.acceptanceCriteria = acceptanceCriteria; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getPlanSummary() { return planSummary; }
    public void setPlanSummary(String planSummary) { this.planSummary = planSummary; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
