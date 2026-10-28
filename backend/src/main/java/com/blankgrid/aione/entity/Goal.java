package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 公司目标:创始人只写目标,CEO 负责拆解、调度、验收。
 * PLANNING → ACTIVE → REVIEW → DONE,中途可 PAUSED / FAILED。
 */
@Entity
@Table(name = "goals")
public class Goal {

    public static final String PLANNING = "PLANNING";
    public static final String ACTIVE = "ACTIVE";
    public static final String REVIEW = "REVIEW";
    public static final String DONE = "DONE";
    public static final String PAUSED = "PAUSED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    /** 创始人原话:要什么、给谁、为什么 */
    @Column(length = 8000)
    private String brief;

    /** 成功标准:怎样才算真正完成 */
    @Column(length = 8000)
    private String successCriteria;

    /** 本目标的质量底线,可覆盖公司默认质量准则 */
    @Column(length = 3000)
    private String qualityBar;

    private Instant deadline;

    /** PLANNING / ACTIVE / REVIEW / DONE / PAUSED / FAILED */
    private String status = PLANNING;

    /** 0-100 */
    private int progress = 0;

    /** 所有已通过成果的平均评审分 */
    private int finalScore = 0;

    /** 0 = 不限 */
    private long budgetTokens = 0;
    private long spentTokens = 0;

    /** CEO 对本目标的一句话状态 */
    @Column(length = 2000)
    private String ceoSummary = "";

    /** CEO 拆解思路 */
    @Column(length = 4000)
    private String planNotes = "";

    /**
     * 待创始人补充的事实(JSON 数组)。CEO / 部门负责人 / 评审在缺少必须核实的外部事实时
     * 上报到这里,而不是自己编一个数字或功能名。空串 = 没有待补事项。
     */
    @Column(length = 6000)
    private String needsInput = "";

    /** 创始人已确认的事实,会注入这个目标后续所有拆解、执行、评审提示词 */
    @Column(length = 12000)
    private String providedFacts = "";

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Goal() {}

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public String getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(String successCriteria) { this.successCriteria = successCriteria; }
    public String getQualityBar() { return qualityBar; }
    public void setQualityBar(String qualityBar) { this.qualityBar = qualityBar; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public int getFinalScore() { return finalScore; }
    public void setFinalScore(int finalScore) { this.finalScore = finalScore; }
    public long getBudgetTokens() { return budgetTokens; }
    public void setBudgetTokens(long budgetTokens) { this.budgetTokens = budgetTokens; }
    public long getSpentTokens() { return spentTokens; }
    public void setSpentTokens(long spentTokens) { this.spentTokens = spentTokens; }
    public String getCeoSummary() { return ceoSummary; }
    public void setCeoSummary(String ceoSummary) { this.ceoSummary = ceoSummary; }
    public String getPlanNotes() { return planNotes; }
    public void setPlanNotes(String planNotes) { this.planNotes = planNotes; }
    public String getNeedsInput() { return needsInput; }
    public void setNeedsInput(String needsInput) { this.needsInput = needsInput; }
    public String getProvidedFacts() { return providedFacts; }
    public void setProvidedFacts(String providedFacts) { this.providedFacts = providedFacts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
