package com.blankgrid.aione.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 部门负责人拆出的具体交付物:有唯一负责人、验收标准、依赖与评审记录。
 * 这是目标体系里真正可执行、可验收的最小单元。
 */
@Entity
@Table(name = "deliverables")
public class Deliverable {

    public static final String PENDING = "PENDING";
    public static final String READY = "READY";
    public static final String RUNNING = "RUNNING";
    public static final String REVIEW = "REVIEW";
    public static final String REWORK = "REWORK";
    public static final String DONE = "DONE";
    public static final String BLOCKED = "BLOCKED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "goal_id")
    private Goal goal;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "workstream_id")
    private Workstream workstream;

    /** 执行人 */
    @ManyToOne
    @JoinColumn(name = "owner_id")
    private Employee owner;

    /** 评审人:默认部门负责人 */
    @ManyToOne
    @JoinColumn(name = "reviewer_id")
    private Employee reviewer;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 8000)
    private String brief;

    /** 逐条可检查的验收标准 */
    @Column(length = 8000)
    private String acceptanceCriteria;

    /** 文档 / 方案 / 代码 / 清单 / 设计说明 / 报告 */
    private String outputType = "文档";

    /** 依赖的交付物 id,逗号分隔 */
    @Column(length = 1000)
    private String dependencies = "";

    /** PENDING / READY / RUNNING / REVIEW / REWORK / DONE / BLOCKED / CANCELLED */
    private String status = PENDING;

    private int attempts = 0;
    private int reviewAttempts = 0;
    private int reviewScore = 0;

    @Column(length = 4000)
    private String reviewNotes = "";

    private Long currentTaskId;
    private Long artifactId;
    private Long lastArtifactId;

    private String priority = "MEDIUM";
    private int sortOrder = 0;
    private Instant dueAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Deliverable() {}

    public Long getGoalId() { return goal == null ? null : goal.getId(); }
    public Long getWorkstreamId() { return workstream == null ? null : workstream.getId(); }

    public Long getId() { return id; }
    public Goal getGoal() { return goal; }
    public void setGoal(Goal goal) { this.goal = goal; }
    public Workstream getWorkstream() { return workstream; }
    public void setWorkstream(Workstream workstream) { this.workstream = workstream; }
    public Employee getOwner() { return owner; }
    public void setOwner(Employee owner) { this.owner = owner; }
    public Employee getReviewer() { return reviewer; }
    public void setReviewer(Employee reviewer) { this.reviewer = reviewer; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public String getAcceptanceCriteria() { return acceptanceCriteria; }
    public void setAcceptanceCriteria(String acceptanceCriteria) { this.acceptanceCriteria = acceptanceCriteria; }
    public String getOutputType() { return outputType; }
    public void setOutputType(String outputType) { this.outputType = outputType; }
    public String getDependencies() { return dependencies; }
    public void setDependencies(String dependencies) { this.dependencies = dependencies; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public int getReviewAttempts() { return reviewAttempts; }
    public void setReviewAttempts(int reviewAttempts) { this.reviewAttempts = reviewAttempts; }
    public int getReviewScore() { return reviewScore; }
    public void setReviewScore(int reviewScore) { this.reviewScore = reviewScore; }
    public String getReviewNotes() { return reviewNotes; }
    public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }
    public Long getCurrentTaskId() { return currentTaskId; }
    public void setCurrentTaskId(Long currentTaskId) { this.currentTaskId = currentTaskId; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public Long getLastArtifactId() { return lastArtifactId; }
    public void setLastArtifactId(Long lastArtifactId) { this.lastArtifactId = lastArtifactId; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
