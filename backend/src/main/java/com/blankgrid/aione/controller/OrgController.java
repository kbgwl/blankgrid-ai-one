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
    private final com.blankgrid.aione.service.DocExportService docExport;

    public OrgController(OrgService orgService, DepartmentRepository deptRepo,
                         EmployeeRepository empRepo, TaskRepository taskRepo,
                         com.blankgrid.aione.service.DocExportService docExport) {
        this.orgService = orgService;
        this.deptRepo = deptRepo;
        this.empRepo = empRepo;
        this.taskRepo = taskRepo;
        this.docExport = docExport;
    }

    /**
     * 全岗位说明书导出 —— 一份文档看完这套编制到底被要求做什么。
     *
     * <p>单个岗位的说明书在组织页能看,但"这套设定合不合理"必须横着比:
     * 谁负责什么、哪些职责重叠、哪个环节没人接。逐页点二十次看不出这些,
     * 而这恰恰是调整编制时最先要看的东西。
     *
     * <p>导的是 systemPrompt 原文,不是简介 —— 简介会美化,原文才是它真正收到的指令。
     */
    @GetMapping("/manual")
    public org.springframework.http.ResponseEntity<byte[]> manual(
            @RequestParam(required = false) String format) {
        String fmt = (format == null || format.isBlank()) ? "md" : format.trim().toLowerCase();
        if (!com.blankgrid.aione.service.DocExportService.supports(fmt)) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        try {
            String md = buildManual();
            byte[] body = "md".equals(fmt)
                    ? md.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : docExport.render("岗位说明书", md, fmt);
            String name = "岗位说明书." + fmt;
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"manual." + fmt + "\"; filename*=UTF-8''"
                                    + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                                            .replace("+", "%20"))
                    .contentType(org.springframework.http.MediaType.parseMediaType(
                            com.blankgrid.aione.service.ExportService.contentType(fmt)))
                    .body(body);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    private String buildManual() {
        orgService.seedIfEmpty();
        orgService.ensureHierarchy();
        StringBuilder sb = new StringBuilder();
        sb.append("# 岗位说明书 · 全部在编岗位\n\n");
        sb.append("> 本文由系统当前配置导出。「职责」一节是该岗位真正收到的指令原文,不是简介 ——\n");
        sb.append("> 判断一份说明书有没有用,看的是它能不能让人知道「什么算做完、什么不能做」。\n\n");

        Employee ceo = orgService.ceos().stream().findFirst().orElse(null);
        if (ceo != null) {
            sb.append("## 管理层\n\n");
            appendEmployee(sb, ceo);
        }

        for (Department d : orgService.departments()) {
            sb.append("## ").append(d.getName()).append("\n\n");
            for (Employee e : orgService.employeesOf(d.getId())) {
                appendEmployee(sb, e);
            }
        }

        // 没有部门的岗位最容易在调整编制时被漏掉,单独列出来
        List<Employee> orphans = new ArrayList<>();
        for (Employee e : empRepo.findAll()) {
            if (e.getDepartmentId() == null && !e.isCeo()) orphans.add(e);
        }
        if (!orphans.isEmpty()) {
            sb.append("## 未归属部门\n\n");
            for (Employee e : orphans) appendEmployee(sb, e);
        }

        sb.append("\n---\n\n共 ").append(empRepo.count()).append(" 个岗位。\n");
        return sb.toString();
    }

    private void appendEmployee(StringBuilder sb, Employee e) {
        sb.append("### ").append(e.getName());
        if (e.getTitle() != null && !e.getTitle().isBlank()) sb.append(" · ").append(e.getTitle());
        sb.append("\n\n");

        String level = switch (e.getLevel() == null ? "" : e.getLevel()) {
            case Employee.LEVEL_CEO -> "负责人";
            case Employee.LEVEL_LEAD -> "主管";
            default -> "执行";
        };
        sb.append("- 层级:").append(level);
        if (e.getManagerId() != null) {
            empRepo.findById(e.getManagerId())
                    .ifPresent(m -> sb.append(" · 汇报给 ").append(m.getName()));
        }
        if (e.getModel() != null && !e.getModel().isBlank()) sb.append(" · 模型 ").append(e.getModel());
        sb.append("\n\n");

        String prompt = e.getSystemPrompt();
        if (prompt == null || prompt.isBlank()) {
            sb.append("> 这个岗位还没有填写职责说明 —— 它现在只会按派活时的临时指令工作。\n\n");
        } else {
            sb.append("**职责说明**\n\n").append(prompt.trim()).append("\n\n");
        }
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
