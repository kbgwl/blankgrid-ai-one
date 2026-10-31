package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.ItineraryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findByMeetingIdOrderByStartAtAscIdAsc(Long meetingId);

    /** 重新规划时保留已完成/已派活的条目,只清掉"没人动过"的 */
    List<ItineraryItem> findByMeetingIdAndStatus(Long meetingId, String status);

    List<ItineraryItem> findAllByOrderByStartAtAscIdAsc();
}
