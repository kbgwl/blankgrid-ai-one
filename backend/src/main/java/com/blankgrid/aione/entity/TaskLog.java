package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "task_logs")
public class TaskLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    /** system / user / assistant / info / error */
    private String role;

    @Column(length = 100000)
    private String content;

    private Instant createdAt = Instant.now();

    public TaskLog() {}

    public TaskLog(Task task, String role, String content) {
        this.task = task;
        this.role = role;
        this.content = content;
    }

    public Long getId() { return id; }
    public Task getTask() { return task; }
    public void setTask(Task task) { this.task = task; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
