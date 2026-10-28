package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** token 消耗账本:创始人提供燃料,每一笔 LLM 调用记账 */
@Entity
@Table(name = "token_logs")
public class TokenLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联任务,CEO 周期调用时为空 */
    private Long taskId;

    /** 关联公司目标,用于目标成本核算 */
    private Long goalId;

    /** 来源:TASK / CEO / PLAN / REVIEW */
    private String source;

    /** 估算 token 数 */
    private long tokens;

    private Instant createdAt = Instant.now();

    public TokenLog() {}

    public TokenLog(Long taskId, String source, long tokens) {
        this(taskId, null, source, tokens);
    }

    public TokenLog(Long taskId, Long goalId, String source, long tokens) {
        this.taskId = taskId;
        this.goalId = goalId;
        this.source = source;
        this.tokens = tokens;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public Long getGoalId() { return goalId; }
    public String getSource() { return source; }
    public long getTokens() { return tokens; }
    public Instant getCreatedAt() { return createdAt; }
}
