package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findTop100ByOrderByCreatedAtDescIdDesc();
    List<Activity> findTop20ByTypeInAndStatusOrderByCreatedAtAsc(List<String> types, String status);
    List<Activity> findByGoalIdOrderByCreatedAtDescIdDesc(Long goalId);
    long countByTypeAndStatus(String type, String status);
}
