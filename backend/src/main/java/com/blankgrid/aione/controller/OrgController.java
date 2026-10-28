package com.blankgrid.aione.controller;

import com.blankgrid.aione.entity.Department;
import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.repo.DepartmentRepository;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.TaskRepository;
import com.blankgrid.aione.service.OrgService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/org")
public class OrgController {

    private final OrgService orgService;
    private final DepartmentRepository deptRepo;
    private final EmployeeRepository empRepo;
    private final TaskRepository taskRepo;

    public OrgController(OrgService orgService, DepartmentRepository deptRepo,
                         EmployeeRepository empRepo, TaskRepository taskRepo) {
        this.orgService = orgService;
        this.deptRepo = deptRepo;
        this.empRepo = empRepo;
        this.taskRepo = taskRepo;
    }

    /** 完整组织架构:CEO + 各部门及其岗位(附岗位任务统计) */
    @GetMapping
    public Map<String, Object> org() {
        orgService.seedIfEmpty();
        orgService.ensureHierarchy();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ceo", orgService.ceos().stream().findFirst().orElse(null));
        List<Map<String, Object>> depts = new ArrayList<>();
        for (Department d : orgService.departments()) {
            List<Employee> members = orgService.employeesOf(d.getId());
            Employee lead = members.stream()
                    .filter(e -> Employee.LEVEL_LEAD.equals(e.getLevel()))
                    .findFirst().orElse(null);
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.getId());
            dm.put("name", d.getName());
            dm.put("colorKey", d.getColorKey());
            dm.put("sortOrder", d.getSortOrder());
            dm.put("lead", lead);
            dm.put("employees", members);
            depts.add(dm);
        }
        out.put("departments", depts);
        out.put("stats", employeeStats());
        out.put("failed", taskRepo.countByStatus(Task.FAILED));
        return out;
    }

    /** 各岗位任务统计:待处理 / 执行中 / 今日完成 / 累计完成 / 最近一次完成时间 / 核心指标 */
    private Map<Long, Map<String, Object>> employeeStats() {
        java.time.Instant dayStart = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        java.time.Instant weekStart = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
        Map<Long, Map<String, Object>> stats = new LinkedHashMap<>();
        for (Employee e : empRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pending", taskRepo.countByEmployeeIdAndStatus(e.getId(), Task.PENDING));
            m.put("running", taskRepo.countByEmployeeIdAndStatus(e.getId(), Task.RUNNING));
            m.put("doneToday", taskRepo.countByEmployeeIdAndStatusAndCreatedAtAfter(e.getId(), Task.DONE, dayStart));
            m.put("doneTotal", taskRepo.countByEmployeeIdAndStatus(e.getId(), Task.DONE));
            List<Task> last = taskRepo.findTop3ByEmployeeIdAndStatusOrderByFinishedAtDesc(e.getId(), Task.DONE);
            m.put("lastDoneAt", last.isEmpty() ? null : last.get(0).getFinishedAt());
            if (e.getMetricLabel() != null && !e.getMetricLabel().isBlank()) {
                m.put("metricLabel", e.getMetricLabel());
                m.put("metricValue", taskRepo.countByEmployeeIdAndStatusAndCreatedAtAfter(e.getId(), Task.DONE, weekStart));
            }
            stats.put(e.getId(), m);
        }
        return stats;
    }

    // ---------- 岗位 CRUD ----------

    public static class EmployeeBody {
        public String name;
        public String title;
        public Long departmentId;
        public String systemPrompt;
        public String model;
        public Integer avatarSeed;
        public Boolean disabled;
        public String metricLabel;
        public String learnings;
        public Long managerId;
        public String level;
        public String standards;
    }

    @PostMapping("/employees")
    public Employee createEmployee(@RequestBody EmployeeBody body) {
        Department dept = body.departmentId == null ? null
                : deptRepo.findById(body.departmentId).orElse(null);
        Employee e = new Employee(body.name, body.title, dept,
                body.systemPrompt, body.avatarSeed == null ? (int) (Math.random() * 9999) : body.avatarSeed, false);
        if (body.model != null) e.setModel(body.model);
        if (body.managerId != null) e.setManagerId(body.managerId);
        else if (dept != null) {
            empRepo.findByDepartmentIdOrderByIdAsc(dept.getId()).stream()
                    .filter(x -> Employee.LEVEL_LEAD.equals(x.getLevel()))
                    .findFirst().ifPresent(x -> e.setManagerId(x.getId()));
        }
        e.setLevel(body.level == null ? Employee.LEVEL_STAFF : body.level);
        if (body.standards != null) e.setStandards(body.standards);
        if (Boolean.TRUE.equals(body.disabled)) e.setStatus(Employee.STATUS_DISABLED);
        return empRepo.save(e);
    }

    @PutMapping("/employees/{id}")
    public Employee updateEmployee(@PathVariable Long id, @RequestBody EmployeeBody body) {
        Employee e = empRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("岗位不存在"));
        if (body.name != null) e.setName(body.name);
        if (body.title != null) e.setTitle(body.title);
        if (body.departmentId != null) {
            e.setDepartment(body.departmentId == 0 ? null
                    : deptRepo.findById(body.departmentId).orElse(e.getDepartment()));
        }
        if (body.systemPrompt != null) e.setSystemPrompt(body.systemPrompt);
        if (body.model != null) e.setModel(body.model);
        if (body.metricLabel != null) e.setMetricLabel(body.metricLabel);
        if (body.learnings != null) e.setLearnings(body.learnings);
        if (body.managerId != null) e.setManagerId(body.managerId == 0 ? null : body.managerId);
        if (body.level != null) e.setLevel(body.level);
        if (body.standards != null) e.setStandards(body.standards);
        if (body.disabled != null) {
            e.setStatus(body.disabled ? Employee.STATUS_DISABLED : Employee.STATUS_IDLE);
        }
        return empRepo.save(e);
    }

    @DeleteMapping("/employees/{id}")
    public Map<String, Object> deleteEmployee(@PathVariable Long id) {
        long reports = empRepo.findAll().stream().filter(e -> id.equals(e.getManagerId())).count();
        if (reports > 0) {
            throw new IllegalArgumentException("该负责人名下仍有 " + reports + " 个岗位,请先调整汇报关系");
        }
        empRepo.deleteById(id);
        return Map.of("ok", true);
    }

    // ---------- 部门 CRUD ----------

    public static class DeptBody {
        public String name;
        public String colorKey;
        public Integer sortOrder;
    }

    @PostMapping("/departments")
    public Department createDepartment(@RequestBody DeptBody body) {
        int order = body.sortOrder == null ? (int) (deptRepo.count() + 1) : body.sortOrder;
        return deptRepo.save(new Department(body.name, body.colorKey == null ? "tech" : body.colorKey, order));
    }

    @PutMapping("/departments/{id}")
    public Department updateDepartment(@PathVariable Long id, @RequestBody DeptBody body) {
        Department d = deptRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("部门不存在"));
        if (body.name != null) d.setName(body.name);
        if (body.colorKey != null) d.setColorKey(body.colorKey);
        if (body.sortOrder != null) d.setSortOrder(body.sortOrder);
        return deptRepo.save(d);
    }

    @DeleteMapping("/departments/{id}")
    public Map<String, Object> deleteDepartment(@PathVariable Long id) {
        if (empRepo.findByDepartmentIdOrderByIdAsc(id).isEmpty()) {
            deptRepo.deleteById(id);
            return Map.of("ok", true);
        }
        throw new IllegalArgumentException("部门下仍有岗位,请先移除岗位");
    }
}
