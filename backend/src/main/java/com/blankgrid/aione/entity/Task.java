package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "tasks")
public class Task {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 任务类型:人工派活 / 目标交付 / 质量评审 */
    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_WORK = "WORK";
    public static final String KIND_REVIEW = "REVIEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** 任务指令 */
    @Column(length = 8000, nullable = false)
    private String instruction;

    /**
     * 输入上下文:引用的其他任务产出,拼接后的文本。
     *
     * <p>用 CLOB 不用 VARCHAR:续写功能上线后,一份交付物写到八万字是常态,
     * 而 VARCHAR(100000) 会在最后一步 save 时抛 "Value too long",
     * 表现是任务卡在 RUNNING、正文查不到 —— 查起来极费劲。
     */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String contextText = "";

    /** 协作链:上游任务 id,逗号分隔 */
    @Column(length = 2000)
    private String sourceTaskIds = "";

    private String model;

    /** 关联公司目标 / 工作流 / 交付物 */
    private Long goalId;
    private Long workstreamId;
    private Long deliverableId;

    /** MANUAL / WORK / REVIEW */
    private String kind = KIND_MANUAL;

    /** 第几次尝试(交付物返工时递增) */
    @Column(nullable = false, columnDefinition = "int default 1")
    private int attempt = 1;

    /** 本次任务的验收标准 */
    @Column(length = 8000)
    private String acceptanceCriteria;

    /** 评审任务对应的成果物 id */
    private Long artifactId;

    /** PENDING / RUNNING / DONE / FAILED / CANCELLED */
    private String status = PENDING;

    /** 执行结果(LLM 最终回复)。同样必须是 CLOB —— 见 contextText 的说明。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String result = "";

    /** 失败原因。留 2000:异常栈里的 SQL 提示本身就能上千字,截到 255 会把真正的根因切掉 */
    @Column(length = 2000)
    private String error = "";

    private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;

    public Task() {}

    public Long getId() { return id; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public String getContextText() { return contextText; }
    public void setContextText(String contextText) { this.contextText = contextText; }
    public String getSourceTaskIds() { return sourceTaskIds; }
    public void setSourceTaskIds(String sourceTaskIds) { this.sourceTaskIds = sourceTaskIds; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Long getGoalId() { return goalId; }
    public void setGoalId(Long goalId) { this.goalId = goalId; }
    public Long getWorkstreamId() { return workstreamId; }
    public void setWorkstreamId(Long workstreamId) { this.workstreamId = workstreamId; }
    public Long getDeliverableId() { return deliverableId; }
    public void setDeliverableId(Long deliverableId) { this.deliverableId = deliverableId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public String getAcceptanceCriteria() { return acceptanceCriteria; }
    public void setAcceptanceCriteria(String acceptanceCriteria) { this.acceptanceCriteria = acceptanceCriteria; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
