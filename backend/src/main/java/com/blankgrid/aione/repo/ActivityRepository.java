package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findTop100ByOrderByCreatedAtDescIdDesc();
    List<Activity> findTop20ByTypeInAndStatusOrderByCreatedAtAsc(List<String> types, String status);
    List<Activity> findByGoalIdOrderByCreatedAtDescIdDesc(Long goalId);
    long countByTypeAndStatus(String type, String status);

    /** 同一个主体的 OPEN 上报(正常 0 或 1 条;老数据合并前可能多条) */
    List<Activity> findByDedupeKeyAndStatusOrderByIdDesc(String dedupeKey, String status);

    /** 对账用:一次把待处理的都捞出来,而不是逐条查库 */
    List<Activity> findByTypeAndStatusOrderByIdAsc(String type, String status);

    /** 补历史数据的主体键:只捞没键的,跑多少次都安全 */
    List<Activity> findByTypeAndDedupeKeyIsNullOrderByIdAsc(String type);
}
