package com.blankgrid.aione.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 成果物:每次执行产出都留一个版本,评审通过才成为交付物。
 */
@Entity
@Table(name = "artifacts")
public class Artifact {

    public static final String DRAFT = "DRAFT";
    public static final String REVIEW = "REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "goal_id")
    private Goal goal;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "workstream_id")
    private Workstream workstream;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "deliverable_id")
    private Deliverable deliverable;

    @ManyToOne
    @JoinColumn(name = "author_id")
    private Employee author;

    @Column(nullable = false, length = 300)
    private String title;

    private String type = "文档";
    private int version = 1;

    @Column(length = 1000000)
    private String content = "";

    /** DRAFT / REVIEW / APPROVED / REJECTED */
    private String status = DRAFT;

    private int score = 0;

    @Column(length = 4000)
    private String reviewNotes = "";

    private String fileName = "";

    private Instant createdAt = Instant.now();
    private Instant approvedAt;

    public Artifact() {}

    public Long getGoalId() { return goal == null ? null : goal.getId(); }
    public Long getWorkstreamId() { return workstream == null ? null : workstream.getId(); }
    public Long getDeliverableId() { return deliverable == null ? null : deliverable.getId(); }

    public Long getId() { return id; }
    public Goal getGoal() { return goal; }
    public void setGoal(Goal goal) { this.goal = goal; }
    public Workstream getWorkstream() { return workstream; }
    public void setWorkstream(Workstream workstream) { this.workstream = workstream; }
    public Deliverable getDeliverable() { return deliverable; }
    public void setDeliverable(Deliverable deliverable) { this.deliverable = deliverable; }
    public Employee getAuthor() { return author; }
    public void setAuthor(Employee author) { this.author = author; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getReviewNotes() { return reviewNotes; }
    public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
