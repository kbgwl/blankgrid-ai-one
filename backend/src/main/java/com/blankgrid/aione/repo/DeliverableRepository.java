package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Deliverable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DeliverableRepository extends JpaRepository<Deliverable, Long> {

    @Query("select d from Deliverable d where d.goal.id = :goalId order by d.sortOrder asc, d.id asc")
    List<Deliverable> findByGoalIdOrderBySortOrderAscIdAsc(@Param("goalId") Long goalId);

    @Query("select d from Deliverable d where d.workstream.id = :workstreamId order by d.sortOrder asc, d.id asc")
    List<Deliverable> findByWorkstreamIdOrderBySortOrderAscIdAsc(@Param("workstreamId") Long workstreamId);

    List<Deliverable> findByStatusIn(Collection<String> statuses);

    /** 补历史上报的主体键要用:老上报只留了标题,没有 id */
    List<Deliverable> findByTitleOrderByIdDesc(String title);

    @Query("select count(d) from Deliverable d where d.goal.id = :goalId")
    long countByGoalId(@Param("goalId") Long goalId);

    @Query("select count(d) from Deliverable d where d.goal.id = :goalId and d.status = :status")
    long countByGoalIdAndStatus(@Param("goalId") Long goalId, @Param("status") String status);

    @Query("select count(d) from Deliverable d where d.workstream.id = :workstreamId")
    long countByWorkstreamId(@Param("workstreamId") Long workstreamId);

    @Query("select count(d) from Deliverable d where d.workstream.id = :workstreamId and d.status = :status")
    long countByWorkstreamIdAndStatus(@Param("workstreamId") Long workstreamId, @Param("status") String status);
}
