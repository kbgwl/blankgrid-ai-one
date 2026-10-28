package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    List<Department> findAllByOrderBySortOrderAscIdAsc();
}
