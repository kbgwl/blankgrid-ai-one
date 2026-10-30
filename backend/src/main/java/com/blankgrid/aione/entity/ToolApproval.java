package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一次「需要创始人点头」的工具调用。
 *
 * <p>为什么要落表而不是只在日志里写一笔:策略设为 confirm 的工具,
 * 模型调它的时候人往往不在场(CEO 巡视是定时的)。如果只回一句"需要批准",
 * 这个动作就永久丢了 —— 下一轮巡视模型看到的是"上次没查成",只能再试一次,
 * 无限循环。落表之后,批准的那一刻可以拿原参数把这次调用补上,证据才连得起来。
 */
@Entity
@Table(name = "tool_approvals")
public class ToolApproval {

    /** 等创始人处理 */
    public static final String PENDING = "PENDING";
    /** 已批准,正在执行 */
    public static final String RUNNING = "RUNNING";
    /** 批准并已执行完 */
    public static final String DONE = "DONE";
    /** 批准了但执行报错 */
    public static final String FAILED = "FAILED";
    /** 创始人拒绝 */
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String toolName;
    private String category;

    /** 模型原始给的参数(JSON 原文),批准时按它重放 */
    @Column(length = 4000)
    private String argsJson;

    /** 模型为什么调它(工具调用前的说明文字),给审批人看上下文 */
    @Column(length = 2000)
    private String reason;

    private String status = PENDING;

    /** 执行结果;失败时是失败原因 */
    @Lob
    private String result;

    private Long goalId;
    private Long taskId;
    /** 谁在调:岗位名,或 CEO */
    private String actor;

    private Instant createdAt = Instant.now();
    private Instant decidedAt;
    private Instant executedAt;

    public ToolApproval() {}

    public ToolApproval(String toolName, String category, String argsJson, String reason,
                        Long goalId, Long taskId, String actor) {
        this.toolName = toolName;
        this.category = category;
        this.argsJson = argsJson;
        this.reason = reason;
        this.goalId = goalId;
        this.taskId = taskId;
        this.actor = actor;
    }

    public Long getId() { return id; }
    public String getToolName() { return toolName; }
    public void setToolName(String v) { this.toolName = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String v) { this.argsJson = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getResult() { return result; }
    public void setResult(String v) { this.result = v; }
    public Long getGoalId() { return goalId; }
    public void setGoalId(Long v) { this.goalId = v; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long v) { this.taskId = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant v) { this.executedAt = v; }
}
