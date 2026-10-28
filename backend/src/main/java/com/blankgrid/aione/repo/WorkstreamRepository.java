package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Workstream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WorkstreamRepository extends JpaRepository<Workstream, Long> {

    @Query("select w from Workstream w where w.goal.id = :goalId order by w.sortOrder asc, w.id asc")
    List<Workstream> findByGoalIdOrderBySortOrderAscIdAsc(@Param("goalId") Long goalId);

    List<Workstream> findByStatusIn(Collection<String> statuses);

    @Query("select count(w) from Workstream w where w.goal.id = :goalId")
    long countByGoalId(@Param("goalId") Long goalId);

    @Query("select count(w) from Workstream w where w.goal.id = :goalId and w.status = :status")
    long countByGoalIdAndStatus(@Param("goalId") Long goalId, @Param("status") String status);
}
