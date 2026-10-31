package com.blankgrid.aione.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 会议时间轴上的一条发言(或一条人工速记)。
 *
 * <p>原话版必须能"倒带":所以每条都带相对会议开始的毫秒偏移,
 * 而不是只记落库时间 —— 手工补录、批量导入时,落库时间和说话时间不是一回事。
 */
@Entity
@Table(name = "meeting_segments")
public class MeetingSegment {

    public static final String SPEECH = "SPEECH";
    /** 人工速记:不是原话,原话实录里要标出来,不能混成一句"他说" */
    public static final String NOTE = "NOTE";
    /** 系统插入的标记:暂停、恢复、议题切换 */
    public static final String SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "meeting_id")
    private Meeting meeting;

    /** 会议内的顺序号,从 1 开始。并发补录时也靠它稳定排序 */
    private int seq;

    @Column(length = 80)
    private String speaker = "";

    @Column(length = 8000)
    private String text = "";

    /** 相对会议开始的毫秒偏移 */
    private long atMs;

    /** 落库时刻 */
    private Instant at = Instant.now();

    @Column(length = 16)
    private String kind = SPEECH;

    /** 人工点过重点:总结版不能只靠模型猜哪句要紧 */
    private boolean marked = false;

    private Instant createdAt = Instant.now();

    public MeetingSegment() {}

    public MeetingSegment(Meeting meeting, int seq, String speaker, String text, long atMs, String kind) {
        this.meeting = meeting;
        this.seq = seq;
        this.speaker = speaker == null ? "" : speaker.trim();
        this.text = text == null ? "" : text.trim();
        this.atMs = atMs;
        this.kind = kind == null ? SPEECH : kind;
    }

    public Long getId() { return id; }
    public Meeting getMeeting() { return meeting; }
    public void setMeeting(Meeting meeting) { this.meeting = meeting; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public long getAtMs() { return atMs; }
    public void setAtMs(long atMs) { this.atMs = atMs; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public boolean isMarked() { return marked; }
    public void setMarked(boolean marked) { this.marked = marked; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
