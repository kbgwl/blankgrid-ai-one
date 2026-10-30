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
    /** 创始人看过并处理了。和 RESOLVED 的区别是「谁动的手」 */
    public static final String ACK = "ACK";
    /**
     * 问题自己消失了(交付物通过了、目标暂停了、余额恢复了)。
     *
     * <p>不能复用 ACK:ACK 表示创始人真的处理过,RESOLVED 表示没人管但它不再是问题。
     * 混成一个值,就再也答不上「我到底忽略了多少件事」——而那恰恰是最该看的数。
     */
    public static final String RESOLVED = "RESOLVED";

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

    /**
     * 上报的「主体」:一件事的身份,例如 d:53(交付物)、w:12(工作流)、g:6:budget(目标预算)。
     *
     * <p>没有它,同一份交付物每卡一次就多一条上报 —— 实测 252 条里 92 条是同一句
     * 「余额不足」。有了它,同一个主体永远只有一条 OPEN,新的只更新原因和次数。
     */
    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    /**
     * 这事反复了几次。
     *
     * <p>必须用 Integer 而不是 int:ddl-auto=update 给已有数据的表加
     * 「not null」列会被 H2 拒绝(NULL not allowed),而且是静默失败 ——
     * 只打一条 WARN,直到第一次查询才炸「Column not found」。
     */
    @Column(name = "occurrences")
    private Integer occurrences = 1;

    /** 最后一次发生的时间。排序用它,createdAt 留作「第一次是什么时候」 */
    private Instant lastSeenAt = Instant.now();

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
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    /** 老数据没有这一列,读出来是 null,一律当 1 次 */
    public int getOccurrences() { return occurrences == null ? 1 : occurrences; }
    public void setOccurrences(Integer occurrences) { this.occurrences = occurrences; }
    public Instant getLastSeenAt() { return lastSeenAt == null ? createdAt : lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
