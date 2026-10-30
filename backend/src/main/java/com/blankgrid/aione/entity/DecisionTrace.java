package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一次决策的完整档案:它查了什么、看到了什么、最后怎么拍的板。
 *
 * <p>为什么单独一张表,而不是塞进公司动态:
 * 公司动态是给创始人"看公司发生了什么"的叙事,一条一两百字;
 * 这里存的是原始材料 —— 取证结果可能有几万字,决策 JSON 又是另一段。
 * 混在一起,要么把叙事撑爆,要么把证据裁掉,两头都不像。
 *
 * <p>这张表要能回答的问题是:「这条决策凭什么这么定?」
 * 没有它,CEO 说什么都只能选择相信或不信,没法追问。
 */
@Entity
@Table(name = "decision_traces")
public class DecisionTrace {

    /** 定时巡视 */
    public static final String KIND_CYCLE = "CYCLE";
    /** 目标拆解(PLAN 阶段) */
    public static final String KIND_PLAN = "PLAN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cycleNo;
    private String kind = KIND_CYCLE;

    /** 这一轮用的决策模型(记下来才知道是不是被降级了) */
    private String model;

    /** 取证阶段的工具返回原文 */
    @Lob
    private String evidence;

    /** 拍板输出的 JSON 原文 */
    @Lob
    private String decisionJson;

    /** 本轮结论摘要,列表页显示用 */
    @Column(length = 1000)
    private String summary;

    /** 取证调过的工具名,逗号分隔 */
    private String toolNames;

    /** 取证里有几条变成了「需批准」申请 */
    private int approvalsRequested;

    /** 本轮估算 token */
    private long tokens;

    private Instant createdAt = Instant.now();

    public DecisionTrace() {}

    public DecisionTrace(Long cycleNo, String kind, String model) {
        this.cycleNo = cycleNo;
        this.kind = kind;
        this.model = model;
    }

    public Long getId() { return id; }
    public Long getCycleNo() { return cycleNo; }
    public void setCycleNo(Long v) { this.cycleNo = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String v) { this.evidence = v; }
    public String getDecisionJson() { return decisionJson; }
    public void setDecisionJson(String v) { this.decisionJson = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getToolNames() { return toolNames; }
    public void setToolNames(String v) { this.toolNames = v; }
    public int getApprovalsRequested() { return approvalsRequested; }
    public void setApprovalsRequested(int v) { this.approvalsRequested = v; }
    public long getTokens() { return tokens; }
    public void setTokens(long v) { this.tokens = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
