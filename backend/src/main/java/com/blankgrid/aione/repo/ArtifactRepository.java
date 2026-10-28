package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    @Query("select a from Artifact a where a.deliverable.id = :deliverableId order by a.version desc")
    List<Artifact> findByDeliverableIdOrderByVersionDesc(@Param("deliverableId") Long deliverableId);

    @Query("select a from Artifact a where a.goal.id = :goalId order by a.createdAt desc")
    List<Artifact> findByGoalIdOrderByCreatedAtDesc(@Param("goalId") Long goalId);

    @Query("select a from Artifact a where a.goal.id = :goalId and a.status = :status order by a.score desc")
    List<Artifact> findByGoalIdAndStatusOrderByScoreDesc(@Param("goalId") Long goalId,
                                                         @Param("status") String status);

    @Query("select a from Artifact a where a.goal.id = :goalId and a.status = :status order by a.createdAt desc")
    List<Artifact> findByGoalIdAndStatusOrderByCreatedAtDesc(@Param("goalId") Long goalId,
                                                             @Param("status") String status);

    List<Artifact> findTop50ByStatusOrderByCreatedAtDesc(String status);

    List<Artifact> findTop200ByOrderByCreatedAtDesc();

    List<Artifact> findByStatusOrderByCreatedAtDesc(String status);

    @Query("select count(a) from Artifact a where a.goal.id = :goalId")
    long countByGoalId(@Param("goalId") Long goalId);

    @Query("select count(a) from Artifact a where a.deliverable.id = :deliverableId")
    long countByDeliverableId(@Param("deliverableId") Long deliverableId);

    long countByStatus(String status);
}
