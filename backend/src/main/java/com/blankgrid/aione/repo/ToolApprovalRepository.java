package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.ToolApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ToolApprovalRepository extends JpaRepository<ToolApproval, Long> {
    List<ToolApproval> findByStatusOrderByCreatedAtDesc(String status);
    List<ToolApproval> findTop50ByOrderByCreatedAtDescIdDesc();
    long countByStatus(String status);

    /** 同一个工具+同一份参数不重复排队,避免模型每轮巡视都塞一条一样的待批 */
    boolean existsByToolNameAndArgsJsonAndStatus(String toolName, String argsJson, String status);

    /** 过期清理用:只看还在等的那批,而且只挑足够老的 */
    List<ToolApproval> findByStatusAndCreatedAtBefore(String status, Instant before);
}
