package com.blankgrid.aione.entity;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;

/**
 * 一场正在开(或开过)的会。
 *
 * <p>会议不是"一份文档",它是一段有时间轴的过程:人还在说的时候,
 * 纪要、原话、行程都该跟着长出来。所以这里的模型是
 * <b>会议(容器) + 片段(时间轴) + 行程(从内容里抽出来的事)</b>,
 * 而不是"上传一个录音,等它转成文字"。
 *
 * <p>时长不能只记 {@code startedAt} 和 {@code endedAt} 之差:
 * 会中途暂停(休息、等人、断线),暂停的那段不该算进"有效时长",
 * 否则原话实录的时间戳和"开了 72 分钟"对不上。所以累计的是 {@link #activeMs}。
 */
@Entity
@Table(name = "meetings")
public class Meeting {

    /** 建好了还没开始 */
    public static final String DRAFT = "DRAFT";
    /** 正在开:计时走动,片段可以进来 */
    public static final String LIVE = "LIVE";
    /** 暂停:计时停住,片段仍可补录(等人回来先记一笔的情况很常见) */
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";

    /** 纪要状态:还没生成 / 生成过但会后补录了新内容 / 只有确定性速记(模型没跑成) / 完整 */
    public static final String SUMMARY_NONE = "NONE";
    public static final String SUMMARY_STALE = "STALE";
    public static final String SUMMARY_PARTIAL = "PARTIAL";
    public static final String SUMMARY_READY = "READY";

    public static final String SRC_MANUAL = "MANUAL";
    public static final String SRC_BROWSER = "BROWSER";
    public static final String SRC_IMPORT = "IMPORT";
    public static final String SRC_PUSH = "PUSH";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    /** 预设议题。有了它,纪要才能回答"该聊的聊到没有" */
    @Column(length = 4000)
    private String agenda = "";

    @Column(length = 300)
    private String location = "";

    /** 参会人,逗号分隔。说话人下拉从这个列表来,减少手打错字 */
    @Column(length = 1000)
    private String participants = "";

    private Instant scheduledAt;

    private Instant startedAt;
    private Instant endedAt;

    @Column(length = 16, nullable = false)
    private String status = DRAFT;

    /** 累计有效时长(毫秒),暂停不计 */
    private long activeMs = 0;
    /** 本轮开始计时的时刻;只有 LIVE 时有意义 */
    private Instant resumedAt;

    /** 转写来源:手工速记 / 浏览器实时识别 / 粘贴导入 / 外部 ASR 推送 */
    @Column(length = 16)
    private String source = SRC_MANUAL;

    /** 外部 ASR 往这里推片段时用的口令。和主口令分开:给出去的只能是这一场的写权限 */
    @Column(length = 64, unique = true)
    private String ingestToken;

    @Column(length = 200000)
    private String summaryMd;

    @Column(length = 16)
    private String summaryStatus = SUMMARY_NONE;

    private Instant summaryAt;

    /** 纪要生成时用的模型:换过模型之后分数/质量对不上,得说得清 */
    @Column(length = 80)
    private String summaryModel = "";

    /** 模型没跑成时的原因,界面上要能看见"为什么这份纪要是速记版" */
    @Column(length = 500)
    private String summaryNote = "";

    @Column(length = 200)
    private String owner = "";

    private Instant createdAt = Instant.now();

    /** 有效时长(秒),含正在走的那一段 */
    public long elapsedSeconds() {
        long ms = activeMs;
        if (LIVE.equals(status) && resumedAt != null) {
            ms += Math.max(0, Duration.between(resumedAt, Instant.now()).toMillis());
        }
        return ms / 1000;
    }

    public boolean isLive() { return LIVE.equals(status); }
    public boolean isEnded() { return ENDED.equals(status); }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAgenda() { return agenda; }
    public void setAgenda(String agenda) { this.agenda = agenda; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getParticipants() { return participants; }
    public void setParticipants(String participants) { this.participants = participants; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getActiveMs() { return activeMs; }
    public void setActiveMs(long activeMs) { this.activeMs = activeMs; }
    public Instant getResumedAt() { return resumedAt; }
    public void setResumedAt(Instant resumedAt) { this.resumedAt = resumedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getIngestToken() { return ingestToken; }
    public void setIngestToken(String ingestToken) { this.ingestToken = ingestToken; }
    public String getSummaryMd() { return summaryMd; }
    public void setSummaryMd(String summaryMd) { this.summaryMd = summaryMd; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }
    public Instant getSummaryAt() { return summaryAt; }
    public void setSummaryAt(Instant summaryAt) { this.summaryAt = summaryAt; }
    public String getSummaryModel() { return summaryModel; }
    public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel; }
    public String getSummaryNote() { return summaryNote; }
    public void setSummaryNote(String summaryNote) { this.summaryNote = summaryNote; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
