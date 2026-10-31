package com.blankgrid.aione.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 行程条目:从会议内容里抽出来的"接下来要发生的事"。
 *
 * <p>它不是待办清单 —— 待办是"谁要做什么",行程是"什么时候、在哪、和谁"。
 * 所以它能导出成日历(.ics),也能派成岗位任务:前者给人看,后者让 AI 去准备。
 *
 * <p>{@link #timeUnknown} 单独存在,是因为模型很爱给一个假时间。
 * 原话只说"下周找个时间对一下",它却排到周一 09:00 —— 这种"看起来排好了"
 * 比"没排"更危险:人会照着它去准备。所以时间不是从原话推出来的,就必须标出来。
 */
@Entity
@Table(name = "itinerary_items")
public class ItineraryItem {

    public static final String PLANNED = "PLANNED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    public static final String MEETING = "MEETING";
    public static final String TRAVEL = "TRAVEL";
    public static final String PREP = "PREP";
    public static final String CALL = "CALL";
    public static final String DEADLINE = "DEADLINE";
    public static final String OTHER = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "meeting_id")
    private Meeting meeting;

    @Column(nullable = false, length = 300)
    private String title;

    private Instant startAt;
    private Instant endAt;

    @Column(length = 300)
    private String location = "";

    @Column(length = 500)
    private String participants = "";

    /** MEETING / TRAVEL / PREP / CALL / DEADLINE / OTHER */
    @Column(length = 16)
    private String kind = MEETING;

    /** 会前要准备什么。行程真有用,就靠这一栏 */
    @Column(length = 2000)
    private String prepare = "";

    /** 原话依据:一句话能追回到谁在什么时候说的 */
    @Column(length = 2000)
    private String sourceQuote = "";

    /** 时间不是原话里说定的,是按会议时间推的 —— 界面上要标"待定" */
    private boolean timeUnknown = false;

    @Column(length = 16)
    private String status = PLANNED;

    /** 已经派给岗位的话,记下任务号,避免同一件事派两遍 */
    private Long taskId;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Meeting getMeeting() { return meeting; }
    public void setMeeting(Meeting meeting) { this.meeting = meeting; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getParticipants() { return participants; }
    public void setParticipants(String participants) { this.participants = participants; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getPrepare() { return prepare; }
    public void setPrepare(String prepare) { this.prepare = prepare; }
    public String getSourceQuote() { return sourceQuote; }
    public void setSourceQuote(String sourceQuote) { this.sourceQuote = sourceQuote; }
    public boolean isTimeUnknown() { return timeUnknown; }
    public void setTimeUnknown(boolean timeUnknown) { this.timeUnknown = timeUnknown; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
