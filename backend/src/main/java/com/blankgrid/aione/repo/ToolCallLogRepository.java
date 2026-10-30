package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.ToolCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {

    List<ToolCallLog> findTop100ByOrderByCreatedAtDescIdDesc();

    List<ToolCallLog> findTop100ByOrderByIdDesc();

    List<ToolCallLog> findByCreatedAtAfterOrderByCreatedAtDescIdDesc(Instant since);

    long countByCreatedAtAfter(Instant since);
}
