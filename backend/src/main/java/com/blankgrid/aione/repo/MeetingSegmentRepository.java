package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.MeetingSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingSegmentRepository extends JpaRepository<MeetingSegment, Long> {

    List<MeetingSegment> findByMeetingIdOrderBySeqAsc(Long meetingId);

    /** 补录时要用到"最后一条"来算 seq 和去重 */
    MeetingSegment findTopByMeetingIdOrderBySeqDesc(Long meetingId);

    /**
     * 时间轴上最靠后的一条。
     *
     * <p>不能拿 seq 最大的那条来算时长:会结束时插入的「会议结束」标记 seq 最大,
     * 但它的偏移是 0 —— 于是列表里显示"开了 0 分钟,12 条发言"。
     */
    MeetingSegment findTopByMeetingIdOrderByAtMsDesc(Long meetingId);

    long countByMeetingId(Long meetingId);

    void deleteByMeetingId(Long meetingId);
}
