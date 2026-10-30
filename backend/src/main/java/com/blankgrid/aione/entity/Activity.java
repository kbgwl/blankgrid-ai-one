package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 公司运营动态:目标 / 纠偏 / CEO 汇报 / 上报待决
 * GOAL=公司目标(创始人)  FEEDBACK=纠偏指令(创始人)
 * REPORT=CEO 汇报        ESCALATION=CEO 上报,等创始人处理
 *
 * <p>注意:CEO 取证与决策原文不写这里,走 {@link DecisionTrace}。
 * 动态是给创始人看"公司发生了什么"的叙事,一条一两百字;
 * 证据可能几万字,混进来要么撑爆长度上限,要么被裁掉,两头都不像。
 */
@Entity
@Table(name = "activities")
public class Activity {

    public static final String GOAL = "GOAL";
    public static final String FEEDBACK = "FEEDBACK";
    public static final String REPORT = "REPORT";
    public static final String ESCALATION = "ESCALATION";

    public static final String OPEN = "OPEN";
    public static final String ACK = "ACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;

    @Column(length = 8000)
    private String content;

    /** OPEN / ACK */
    private String status = OPEN;

    /** 产生该动态的周期号(CEO 汇报用) */
    private Long cycleNo;

    /** 关联公司目标 id(项目动态用) */
    private Long goalId;

    private Instant createdAt = Instant.now();

    public Activity() {}

    public Activity(String type, String content, Long cycleNo) {
        this.type = type;
        this.content = content;
        this.cycleNo = cycleNo;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCycleNo() { return cycleNo; }
    public void setCycleNo(Long cycleNo) { this.cycleNo = cycleNo; }
    public Long getGoalId() { return goalId; }
    public void setGoalId(Long goalId) { this.goalId = goalId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
