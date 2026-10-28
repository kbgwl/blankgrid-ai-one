package com.blankgrid.aione.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "employees")
public class Employee {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 角色层级:CEO / 部门负责人 / 具体岗位 */
    public static final String LEVEL_CEO = "CEO";
    public static final String LEVEL_LEAD = "LEAD";
    public static final String LEVEL_STAFF = "STAFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 岗位名,如"产品经理" */
    @Column(nullable = false)
    private String name;

    /** 一句话职责,展示在格子卡片上 */
    private String title = "";

    @ManyToOne
    @JoinColumn(name = "dept_id")
    private Department department;

    /** 岗位说明书(系统提示词) */
    @Column(length = 8000)
    private String systemPrompt = "";

    /** 指定模型;空则用全局默认 */
    private String model;

    /** IDLE / RUNNING / ERROR / DISABLED */
    private String status = STATUS_IDLE;

    /** 当前任务摘要,展示在格子卡片上 */
    private String currentTaskSummary = "";

    /** 头像种子,用于程序化几何头像 */
    private Integer avatarSeed = 0;

    /** 岗位是否为决策层(CEO 节点,不执行任务) */
    private boolean ceo = false;

    /** 核心指标名称,如"本周产出";设定后架构卡展示近 7 天完成任务数大数字 */
    @Column(length = 40)
    private String metricLabel;

    /** 该岗位自己的经验教训(复盘沉淀,执行时注入灵魂) */
    @Column(length = 6000)
    private String learnings;

    /** CEO / LEAD / STAFF */
    private String level = LEVEL_STAFF;

    /** 直属上级 id(CEO 为空) */
    private Long managerId;

    /** 岗位质量准则:这个岗位交出来的东西必须达到什么标准 */
    @Column(length = 8000)
    private String standards;

    public Employee() {}

    public Employee(String name, String title, Department dept, String systemPrompt, Integer avatarSeed, boolean ceo) {
        this.name = name;
        this.title = title;
        this.department = dept;
        this.systemPrompt = systemPrompt;
        this.avatarSeed = avatarSeed;
        this.ceo = ceo;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public Long getDepartmentId() { return department == null ? null : department.getId(); }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentTaskSummary() { return currentTaskSummary; }
    public void setCurrentTaskSummary(String currentTaskSummary) { this.currentTaskSummary = currentTaskSummary; }
    public Integer getAvatarSeed() { return avatarSeed; }
    public void setAvatarSeed(Integer avatarSeed) { this.avatarSeed = avatarSeed; }
    public boolean isCeo() { return ceo; }
    public void setCeo(boolean ceo) { this.ceo = ceo; }
    public String getMetricLabel() { return metricLabel; }
    public void setMetricLabel(String metricLabel) { this.metricLabel = metricLabel; }
    public String getLearnings() { return learnings; }
    public void setLearnings(String learnings) { this.learnings = learnings; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public String getStandards() { return standards; }
    public void setStandards(String standards) { this.standards = standards; }
}
