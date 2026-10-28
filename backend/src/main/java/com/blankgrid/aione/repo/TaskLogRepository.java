package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.TaskLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskLogRepository extends JpaRepository<TaskLog, Long> {
    List<TaskLog> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);
}
