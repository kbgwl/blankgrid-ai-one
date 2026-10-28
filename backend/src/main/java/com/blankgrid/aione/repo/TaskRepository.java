package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findTop200ByOrderByCreatedAtDesc();
    List<Task> findTop100ByEmployeeIdOrderByCreatedAtDesc(Long employeeId);
    long countByStatus(String status);
    long countByStatusAndCreatedAtAfter(String status, java.time.Instant after);
    List<Task> findByStatus(String status);

    long countByEmployeeIdAndStatus(Long employeeId, String status);
    long countByEmployeeIdAndStatusAndCreatedAtAfter(Long employeeId, String status, java.time.Instant after);
    List<Task> findTop3ByEmployeeIdAndStatusOrderByFinishedAtDesc(Long employeeId, String status);
    List<Task> findTop20ByEmployeeIdOrderByCreatedAtDesc(Long employeeId);
}
