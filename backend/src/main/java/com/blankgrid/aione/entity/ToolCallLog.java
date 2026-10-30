package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一次真实的工具执行记录(审计流水)。
 *
 * <p>为什么不能只靠 Activity:Activity 是"公司发生了什么"的叙事,
 * 只记批准过的那几次;而模型每天可能自己调几十次查询类工具,
 * 这些不进叙事,却正是"它到底查了什么、花了多久、有没有白跑"的证据。
 *
 * <p>审计和叙事分开还有一个好处:写日志失败绝不能影响工具本身。
 * 所以这里的写入一律吞异常 —— 审计是旁路,不是主链路。
 */
@Entity
@Table(name = "tool_call_logs")
public class ToolCallLog {

    /** 决策层取证时调用 */
    public static final String SOURCE_DECISION = "DECISION";
    /** 岗位执行任务时调用 */
    public static final String SOURCE_TASK = "TASK";
    /** 创始人批准后重放 */
    public static final String SOURCE_APPROVAL = "APPROVAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String toolName;
    private String category;
    /** DECISION / TASK / APPROVAL */
    private String source;

    /** 谁在调:岗位名,或 CEO */
    private String actor;

    private Long goalId;
    private Long taskId;

    /** 原始参数,便于复现 */
    @Column(length = 4000)
    private String argsJson;

    private boolean ok;
    private long ms;
    private int chars;

    /** 结果开头一小段,别把它当存储用 */
    @Column(length = 500)
    private String resultPreview;

    private Instant createdAt = Instant.now();

    public ToolCallLog() {}

    public ToolCallLog(String toolName, String category, String source, String actor,
                       Long goalId, Long taskId, String argsJson, boolean ok, long ms, int chars,
                       String resultPreview) {
        this.toolName = toolName;
        this.category = category;
        this.source = source;
        this.actor = actor;
        this.goalId = goalId;
        this.taskId = taskId;
        this.argsJson = argsJson;
        this.ok = ok;
        this.ms = ms;
        this.chars = chars;
        this.resultPreview = resultPreview;
    }

    public Long getId() { return id; }
    public String getToolName() { return toolName; }
    public String getCategory() { return category; }
    public String getSource() { return source; }
    public String getActor() { return actor; }
    public Long getGoalId() { return goalId; }
    public Long getTaskId() { return taskId; }
    public String getArgsJson() { return argsJson; }
    public boolean isOk() { return ok; }
    public long getMs() { return ms; }
    public int getChars() { return chars; }
    public String getResultPreview() { return resultPreview; }
    public Instant getCreatedAt() { return createdAt; }
}
