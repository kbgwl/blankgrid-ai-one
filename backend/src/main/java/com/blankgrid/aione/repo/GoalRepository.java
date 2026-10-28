package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GoalRepository extends JpaRepository<Goal, Long> {
    List<Goal> findAllByOrderByCreatedAtDesc();
    List<Goal> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
    long countByStatusIn(Collection<String> statuses);
}
