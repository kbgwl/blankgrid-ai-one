package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    @Query("select e from Employee e where e.department.id = :departmentId order by e.id asc")
    List<Employee> findByDepartmentIdOrderByIdAsc(@Param("departmentId") Long departmentId);

    List<Employee> findByCeoTrue();

    /** 补历史上报的主体键要用:上报里只写了岗位姓名 */
    List<Employee> findByNameOrderByIdDesc(String name);
}
