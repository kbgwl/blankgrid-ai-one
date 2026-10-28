package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.TokenLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TokenLogRepository extends JpaRepository<TokenLog, Long> {

    @Query("select coalesce(sum(t.tokens), 0) from TokenLog t")
    long totalTokens();

    @Query("select coalesce(sum(t.tokens), 0) from TokenLog t where t.createdAt >= :since")
    long tokensSince(@Param("since") java.time.Instant since);

    @Query("select coalesce(sum(t.tokens), 0) from TokenLog t where t.goalId = :goalId")
    long tokensOfGoal(@Param("goalId") Long goalId);
}
