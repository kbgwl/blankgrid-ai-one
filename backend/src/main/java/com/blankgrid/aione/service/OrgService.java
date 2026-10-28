package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Department;
import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.repo.DepartmentRepository;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 默认编制初始化与组织架构查询 */
@Service
public class OrgService {

    private final DepartmentRepository deptRepo;
    private final EmployeeRepository empRepo;
    private final TaskRepository taskRepo;

    public OrgService(DepartmentRepository deptRepo, EmployeeRepository empRepo, TaskRepository taskRepo) {
        this.deptRepo = deptRepo;
        this.empRepo = empRepo;
        this.taskRepo = taskRepo;
    }

    @Transactional
    public void seedIfEmpty() {
        // CEO 角色升级:由人担任改为 AI 担任(兼容旧库)
        for (Employee c : empRepo.findByCeoTrue()) {
            if (c.getName().contains("你")) {
                c.setName("CEO Agent");
                c.setTitle("AI 决策者 · 运营规划");
                c.setSystemPrompt("你是空白格(无锡空白格人工智能科技有限公司)的 CEO,一名 AI 决策者。创始人只提供 token 与方向纠偏,公司日常运营、目标拆解、派活协作、经营赚钱由你全权负责。你评估公司现状、规划下一步、指挥各岗位;大额支出、对外承诺、重大方向必须上报创始人拍板。决策果断,汇报精炼,先结论后理由。");
                empRepo.save(c);
            }
        }
        if (deptRepo.count() > 0 || empRepo.count() > 0) return;

        Employee ceo = empRepo.save(new Employee("CEO Agent", "AI 决策者 · 运营规划", null,
                "你是空白格(无锡空白格人工智能科技有限公司)的 CEO,一名 AI 决策者。创始人只提供 token 与方向纠偏,公司日常运营、目标拆解、派活协作、经营赚钱由你全权负责。你评估公司现状、规划下一步、指挥各岗位;大额支出、对外承诺、重大方向必须上报创始人拍板。决策果断,汇报精炼,先结论后理由。", 1, true));

        Department product = deptRepo.save(new Department("产品部", "product", 1));
        Department tech = deptRepo.save(new Department("技术部", "tech", 2));
        Department design = deptRepo.save(new Department("设计部", "design", 3));
        Department market = deptRepo.save(new Department("市场部", "market", 4));
        Department ops = deptRepo.save(new Department("职能部", "ops", 5));

        empRepo.save(new Employee("产品经理", "需求分析与 PRD", product,
                "你是空白格的产品经理。负责需求分析、撰写 PRD、排定优先级。产出结构清晰、可直接交给开发执行的产品文档。语言精炼,先给结论。", 11, false));
        empRepo.save(new Employee("架构师", "系统设计与技术选型", tech,
                "你是空白格的技术架构师。负责系统设计、技术选型、架构评审。给出方案时同时给出取舍理由与风险。", 21, false));
        empRepo.save(new Employee("开发工程师", "编码实现与修复", tech,
                "你是空白格的开发工程师。负责按需求实现代码、修复缺陷、编写测试。输出完整可运行的代码,遵循项目现有风格。", 22, false));
        empRepo.save(new Employee("测试工程师", "验收与质量把关", tech,
                "你是空白格的测试工程师。独立于开发,负责制定验收标准、设计测试用例、审查代码与产出的质量。严格但建设性。", 23, false));
        empRepo.save(new Employee("UI 设计师", "界面与品牌视觉", design,
                "你是空白格的 UI 设计师。品牌基调:静雅的东方科技美学——留白、墨韵、格光。深墨底色、天青主色、克制动效。产出设计说明或前端样式代码。", 31, false));
        empRepo.save(new Employee("市场运营", "文案、社媒与增长", market,
                "你是空白格的市场运营。负责品牌文案、社媒内容、增长策略。文风克制、有留白感,不喊口号,不用感叹号。", 41, false));
        empRepo.save(new Employee("销售客服", "线索跟进与客户应答", market,
                "你是空白格的销售客服。负责解答客户咨询、跟进线索、维护 FAQ。专业、温和、守信,拿不准的承诺一律上报 CEO。", 42, false));
        empRepo.save(new Employee("财务助理", "记账与合同初审", ops,
                "你是空白格的财务助理。负责记账、发票管理、收支报表、合同条款初审(金额/付款/违约)。发现异常必须提示 CEO 复核,不擅自决策。", 51, false));
        empRepo.save(new Employee("法务助理", "合规与风险提示", ops,
                "你是空白格的法务助理。负责合规检查、风险提示、合同初审。你的意见仅供参考,重大事项必须提示 CEO 咨询执业律师。", 52, false));
        empRepo.save(new Employee("项目管理", "任务流转与周报", ops,
                "你是空白格的项目管理。负责跟进各岗位任务流转、整理会议纪要、产出周报。汇报格式:进度 / 风险 / 下一步。", 53, false));
    }

    /**
     * 建立/修复公司层级:CEO → 部门负责人 → 岗位。
     * 幂等:老库升级时会自动补齐负责人并给现有岗位挂上汇报关系。
     */
    @Transactional
    public void ensureHierarchy() {
        Employee ceo = empRepo.findByCeoTrue().stream().findFirst().orElse(null);
        if (ceo == null) return;
        boolean ceoChanged = false;
        if (!Employee.LEVEL_CEO.equals(ceo.getLevel())) { ceo.setLevel(Employee.LEVEL_CEO); ceoChanged = true; }
        if (ceo.getManagerId() != null) { ceo.setManagerId(null); ceoChanged = true; }
        if (ceo.getStandards() == null || ceo.getStandards().isBlank()) {
            ceo.setStandards("你是公司的最终质量责任人。目标必须被拆成可验收的交付物;关键产出必须过评审;不达标就打回,不允许用空话交差。");
            ceoChanged = true;
        }
        if (ceoChanged) empRepo.save(ceo);

        for (Department d : departments()) {
            List<Employee> list = empRepo.findByDepartmentIdOrderByIdAsc(d.getId());
            Employee lead = list.stream()
                    .filter(e -> Employee.LEVEL_LEAD.equals(e.getLevel()))
                    .findFirst()
                    .orElseGet(() -> list.stream().filter(e -> looksLikeLead(e.getName())).findFirst().orElse(null));
            boolean leadIsNew = false;
            if (lead == null) {
                LeadSpec spec = specFor(d);
                lead = new Employee(spec.name, spec.title, d, spec.prompt, spec.seed, false);
                lead.setLevel(Employee.LEVEL_LEAD);
                lead.setManagerId(ceo.getId());
                lead.setMetricLabel("部门交付");
                lead.setStandards("你对本部门的所有交付负责:拆活具体到人、验收标准可检查、评审不走过场。宁可打回重做,也不让半成品进入成果库。");
                lead = empRepo.save(lead);
                leadIsNew = true;
            } else {
                boolean changed = false;
                if (!Employee.LEVEL_LEAD.equals(lead.getLevel())) { lead.setLevel(Employee.LEVEL_LEAD); changed = true; }
                if (lead.getManagerId() == null) { lead.setManagerId(ceo.getId()); changed = true; }
                if (lead.getStandards() == null || lead.getStandards().isBlank()) {
                    lead.setStandards("你对本部门的所有交付负责:拆活具体到人、验收标准可检查、评审不走过场。宁可打回重做,也不让半成品进入成果库。");
                    changed = true;
                }
                if (lead.getMetricLabel() == null || lead.getMetricLabel().isBlank()) {
                    lead.setMetricLabel("部门交付");
                    changed = true;
                }
                if (changed) empRepo.save(lead);
            }

            // 新建负责人后需要重新取一次部门成员,把新旧岗位都挂到负责人名下
            List<Employee> members = leadIsNew ? empRepo.findByDepartmentIdOrderByIdAsc(d.getId()) : list;
            for (Employee e : members) {
                if (e.getId().equals(lead.getId()) || e.isCeo()) continue;
                boolean changed = false;
                if (e.getLevel() == null || (!Employee.LEVEL_LEAD.equals(e.getLevel()) && !Employee.LEVEL_CEO.equals(e.getLevel()))) {
                    e.setLevel(Employee.LEVEL_STAFF);
                    changed = true;
                }
                if (e.getManagerId() == null) {
                    e.setManagerId(lead.getId());
                    changed = true;
                }
                if (changed) empRepo.save(e);
            }
        }
    }

    private record LeadSpec(String name, String title, String prompt, int seed) {}

    private static LeadSpec specFor(Department d) {
        String n = d.getName() == null ? "" : d.getName();
        if (n.contains("产品")) return new LeadSpec("产品总监", "部门负责人 · 需求判断与验收",
                "你是空白格的产品总监,统筹产品部。你负责把公司目标翻译成清晰的产品需求与优先级,给产品经理拆出可验收的交付物,并逐条验收。你不写空泛方案,所有判断都要落到用户价值与可执行性上。",
                19);
        if (n.contains("技术")) return new LeadSpec("技术总监", "部门负责人 · 架构质量与排期",
                "你是空白格的技术总监,统筹技术部。你负责架构取舍、技术排期、代码质量与测试验收。给团队拆活时明确输入、输出、依赖与验收标准;评审时优先指出会导致返工的真实风险。",
                29);
        if (n.contains("设计")) return new LeadSpec("设计总监", "部门负责人 · 品牌与体验",
                "你是空白格的设计总监,统筹设计部。你负责品牌一致性、界面质量与体验验收。要求交付物给出具体方案而非形容词,评审时对照品牌基调与可用性逐项检查。",
                39);
        if (n.contains("市场")) return new LeadSpec("市场总监", "部门负责人 · 增长与销售",
                "你是空白格的市场总监,统筹市场部。你负责增长策略、内容质量、线索与客户应答。拆活时明确目标人群、渠道、可衡量指标;评审时拒绝自嗨文案与无法执行的计划。",
                49);
        if (n.contains("职能")) return new LeadSpec("运营总监", "部门负责人 · 合规与节奏",
                "你是空白格的运营总监,统筹职能部。你负责财务、法务、项目管理的合规与节奏。拆活时明确风险边界、时间节点与责任人;评审时优先指出合规与交付风险。",
                59);
        return new LeadSpec(n + "负责人", "部门负责人 · 统筹与验收",
                "你是空白格" + n + "的负责人。你负责把部门工作拆成可验收的交付物,监督执行并逐条评审。", 69);
    }

    private static boolean looksLikeLead(String name) {
        return name != null && (name.contains("总监") || name.contains("负责人"));
    }

    @Transactional(readOnly = true)
    public List<Department> departments() {
        return deptRepo.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<Employee> employeesOf(Long deptId) {
        return empRepo.findByDepartmentIdOrderByIdAsc(deptId);
    }

    @Transactional(readOnly = true)
    public List<Employee> ceos() {
        return empRepo.findByCeoTrue();
    }

    @Transactional(readOnly = true)
    public List<Employee> allEmployees() {
        return empRepo.findAll();
    }
}
