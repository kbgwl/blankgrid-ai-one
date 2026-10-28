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

    /** 按模型汇总 token:[model, sum(tokens)],用于成本折算 */
    @Query("select t.model, sum(t.tokens) from TokenLog t group by t.model")
    java.util.List<Object[]> tokensGroupByModel();

    @Query("select t.model, sum(t.tokens) from TokenLog t where t.createdAt >= :since group by t.model")
    java.util.List<Object[]> tokensGroupByModelSince(@Param("since") java.time.Instant since);

    @Query("select t.model, sum(t.tokens) from TokenLog t where t.goalId = :goalId group by t.model")
    java.util.List<Object[]> tokensGroupByModelOfGoal(@Param("goalId") Long goalId);
}
