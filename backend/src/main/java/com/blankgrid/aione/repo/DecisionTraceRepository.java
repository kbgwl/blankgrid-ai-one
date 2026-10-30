package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.DecisionTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface DecisionTraceRepository extends JpaRepository<DecisionTrace, Long> {
    List<DecisionTrace> findTop50ByOrderByIdDesc();
    List<DecisionTrace> findTop100ByOrderByIdDesc();
    List<DecisionTrace> findByCreatedAtAfterOrderByIdDesc(Instant since);
    List<DecisionTrace> findByKindOrderByIdDesc(String kind);
}
