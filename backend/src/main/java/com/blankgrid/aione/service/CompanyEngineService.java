package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.*;
import com.blankgrid.aione.repo.*;
import com.blankgrid.aione.service.LlmService.ChatMessage;
import com.blankgrid.aione.ws.EventPusher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 公司的目标引擎:
 * 创始人只下目标 → CEO 拆部门工作流 → 部门负责人拆交付物 → 岗位执行
 * → 负责人/QA 按验收标准评审 → 不合格返工 → 通过后进入成果库 → 目标归档。
 */
@Service
public class CompanyEngineService {

    private static final Logger log = LoggerFactory.getLogger(CompanyEngineService.class);

    private final GoalRepository goalRepo;
    private final WorkstreamRepository wsRepo;
    private final DeliverableRepository delRepo;
    private final ArtifactRepository artRepo;
    private final EmployeeRepository empRepo;
    private final DepartmentRepository deptRepo;
    private final ActivityRepository activityRepo;
    private final TokenLogRepository tokenRepo;
    private final TaskRepository taskRepo;
    private final OrgService orgService;
    private final GoalService goalService;
    private final TaskService taskService;
    private final LlmService llm;
    private final SettingsService settings;
    private final ExportService exportService;
    private final EventPusher pusher;
    private final ObjectMapper mapper;

    private final Set<Long> planningGoals = ConcurrentHashMap.newKeySet();
    private final Set<Long> planningWorkstreams = ConcurrentHashMap.newKeySet();
    /** 跨部门拆活查重的临界区:两个部门可能同时落库,读清单与写入必须在同一把锁里 */
    private final Object planLock = new Object();
    private final ExecutorService planner = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "company-planner");
        t.setDaemon(true);
        return t;
    });

    public CompanyEngineService(GoalRepository goalRepo, WorkstreamRepository wsRepo,
                                DeliverableRepository delRepo, ArtifactRepository artRepo,
                                EmployeeRepository empRepo, DepartmentRepository deptRepo,
                                ActivityRepository activityRepo, TokenLogRepository tokenRepo,
                                TaskRepository taskRepo, OrgService orgService,
                                GoalService goalService, TaskService taskService,
                                LlmService llm, SettingsService settings,
                                ExportService exportService,
                                EventPusher pusher, ObjectMapper mapper) {
        this.goalRepo = goalRepo;
        this.wsRepo = wsRepo;
        this.delRepo = delRepo;
        this.artRepo = artRepo;
        this.empRepo = empRepo;
        this.deptRepo = deptRepo;
        this.activityRepo = activityRepo;
        this.tokenRepo = tokenRepo;
        this.taskRepo = taskRepo;
        this.orgService = orgService;
        this.goalService = goalService;
        this.taskService = taskService;
        this.llm = llm;
        this.settings = settings;
        this.exportService = exportService;
        this.pusher = pusher;
        this.mapper = mapper;
    }

    // ==================== 事件入口 ====================

    @EventListener
    public void onGoalCreated(GoalCreatedEvent event) {
        requestGoalPlan(event.goalId());
    }

    @EventListener
    public void onTaskFinished(TaskFinishedEvent event) {
        try {
            handleTaskFinished(event);
        } catch (Exception e) {
            log.error("目标引擎处理任务 {} 结束事件失败", event.taskId(), e);
        }
    }

    // ==================== 定时推进 ====================

    /** 每 10 秒推进一次:补拆解、按依赖启动交付、检查目标完成。 */
    @Scheduled(fixedDelay = 10000)
    public void tick() {
        if (!settings.hasProvider() || !budgetAvailable()) return;
        try {
            orgService.ensureHierarchy();
            for (Goal g : goalRepo.findAllByOrderByCreatedAtDesc()) {
                if (Goal.PAUSED.equals(g.getStatus()) || Goal.DONE.equals(g.getStatus())) continue;
                if (goalBudgetExceeded(g)) continue;
                if (Goal.PLANNING.equals(g.getStatus()) && wsRepo.countByGoalId(g.getId()) == 0) {
                    requestGoalPlan(g.getId());
                }
            }
            for (Workstream ws : wsRepo.findByStatusIn(List.of(Workstream.PLANNING))) {
                Goal g = ws.getGoal();
                if (g == null || Goal.PAUSED.equals(g.getStatus()) || Goal.DONE.equals(g.getStatus())) continue;
                if (delRepo.countByWorkstreamId(ws.getId()) == 0) requestWorkstreamPlan(ws.getId());
            }
            // 并发上限:同一时刻最多有这么多交付物在跑,其余排队等空位。
            // 不限并发会同时打出十几个请求,撞服务商限流后全部失败重试,比串行更慢也更贵。
            int limit = Math.max(1, settings.get().concurrency);
            int running = delRepo.findByStatusIn(List.of(Deliverable.RUNNING)).size();
            for (Deliverable d : delRepo.findByStatusIn(List.of(Deliverable.PENDING, Deliverable.READY,
                    Deliverable.WAITING))) {
                Goal g = d.getGoal();
                if (g == null || Goal.PAUSED.equals(g.getStatus()) || Goal.DONE.equals(g.getStatus())) continue;
                if (d.getCurrentTaskId() != null) continue;
                if (goalBudgetExceeded(g)) continue;
                DepState dep = dependencyState(d);
                // 上游卡死时下游一律挂起:带着空上下文硬写,产出的只能是编出来的东西
                if (dep == DepState.BROKEN) {
                    markWaiting(d, brokenReason(d));
                    continue;
                }
                if (dep == DepState.RUNNING) continue;      // 上游还在跑,本项继续排队
                if (Deliverable.WAITING.equals(d.getStatus())) {
                    d.setStatus(Deliverable.READY);
                    d.setReviewNotes("");
                    d.setUpdatedAt(Instant.now());
                    delRepo.save(d);
                    activity(Activity.REPORT, "上游已交付,「" + d.getTitle() + "」重新进入执行队列。", g.getId());
                }
                if (running >= limit) continue;             // 已到并发上限,留在队列里等下一轮
                startWork(d);
                running++;
            }
            recoverStaleDeliverables();
            recoverReviewStages();
            for (Goal g : goalRepo.findAllByOrderByCreatedAtDesc()) {
                if (Goal.PAUSED.equals(g.getStatus()) || Goal.DONE.equals(g.getStatus())) continue;
                checkGoalCompletion(g);
            }
        } catch (Exception e) {
            log.warn("目标引擎 tick 异常: {}", e.getMessage());
        }
    }

    // ==================== CEO 拆解 ====================

    public void requestGoalPlan(Long goalId) {
        if (!planningGoals.add(goalId)) return;
        planner.submit(() -> {
            try {
                planGoal(goalId);
            } catch (Exception e) {
                failGoal(goalId, e.getMessage());
            } finally {
                planningGoals.remove(goalId);
            }
        });
    }

    private void planGoal(Long goalId) {
        Goal g = goalRepo.findById(goalId).orElse(null);
        if (g == null || Goal.DONE.equals(g.getStatus()) || Goal.PAUSED.equals(g.getStatus())) return;
        if (wsRepo.countByGoalId(goalId) > 0) {
            if (Goal.PLANNING.equals(g.getStatus())) {
                g.setStatus(Goal.ACTIVE);
                goalRepo.save(g);
                pushGoal(goalId);
            }
            return;
        }
        checkBudget(goalId);

        Employee ceo = empRepo.findByCeoTrue().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("公司没有 CEO,无法拆解目标"));
        List<Department> departments = deptRepo.findAllByOrderBySortOrderAscIdAsc();
        if (departments.isEmpty()) throw new IllegalStateException("公司还没有部门");

        String sys = nz(ceo.getSystemPrompt())
                + "\n\n【公司质量准则】\n" + qualityBar(g)
                + "\n\n【你的职责边界】\n你只做公司级拆解:把目标分给最合适的部门负责人。你不亲自写交付物,不替部门决定具体执行细节。";
        String user = buildPlanPrompt(g, departments);

        // 目标拆解同样是 CEO 的决策,和巡视走同一个决策模型槽位
        String reply = llm.chatStream(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)),
                settings.decisionModel(ceo.getModel()), null, settings.decisionProvider());
        tokenRepo.save(new TokenLog(null, goalId, "CEO_PLAN",
                CeoLoopService.estimateTokens(sys.length() + user.length() + reply.length())));

        JsonNode root;
        try {
            root = mapper.readTree(extractJson(reply));
        } catch (Exception e) {
            throw new IllegalStateException("CEO 拆解结果不是有效 JSON: " + clip(reply, 300));
        }
        String summary = root.path("summary").asText("CEO 已完成拆解");
        JsonNode arr = root.path("workstreams");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("CEO 没有拆出任何部门工作流,请检查目标描述或模型输出");
        }

        List<Workstream> created = new ArrayList<>();
        int order = 0;
        for (JsonNode node : arr) {
            Department dept = findDepartment(node);
            if (dept == null) continue;
            Employee lead = findLead(dept, node.path("leadId").asLong(0));
            if (lead == null) continue;
            Workstream ws = new Workstream();
            ws.setGoal(g);
            ws.setDepartment(dept);
            ws.setLead(lead);
            ws.setTitle(clip(node.path("title").asText(dept.getName() + "工作流"), 300));
            ws.setBrief(clip(node.path("brief").asText(""), 8000));
            ws.setAcceptanceCriteria(clip(node.path("acceptanceCriteria").asText(""), 8000));
            ws.setDueAt(parseInstant(node.path("dueAt").asText("")));
            ws.setSortOrder(++order);
            ws.setStatus(Workstream.PLANNING);
            created.add(wsRepo.save(ws));
        }
        if (created.isEmpty()) throw new IllegalStateException("CEO 拆出的工作流无法匹配到有效部门与负责人");

        g.setStatus(Goal.ACTIVE);
        g.setPlanNotes(clip(summary, 4000));
        g.setCeoSummary(clip(summary, 2000));
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);

        recordNeedsInput(g, root.path("needsInput"), "CEO");

        activity(Activity.REPORT, "CEO 已拆解目标「" + g.getTitle() + "」:" + clip(summary, 600)
                + " 共 " + created.size() + " 条部门工作流。", goalId);
        pushGoal(goalId);
        for (Workstream ws : created) requestWorkstreamPlan(ws.getId());
    }

    private String buildPlanPrompt(Goal g, List<Department> departments) {
        StringBuilder sb = new StringBuilder();
        sb.append("【创始人下达的公司目标】\n");
        sb.append("标题:").append(g.getTitle()).append("\n");
        sb.append("说明:").append(nz(g.getBrief())).append("\n");
        sb.append("成功标准:").append(nz(g.getSuccessCriteria())).append("\n");
        if (g.getDeadline() != null) sb.append("截止时间:").append(g.getDeadline()).append("\n");
        String facts = factsBlock(g);
        if (!facts.isBlank()) sb.append("\n").append(facts).append("\n");
        sb.append("\n【公司编制(只能派给部门负责人)】\n");
        for (Department d : departments) {
            List<Employee> list = empRepo.findByDepartmentIdOrderByIdAsc(d.getId());
            Employee lead = list.stream().filter(e -> Employee.LEVEL_LEAD.equals(e.getLevel())).findFirst().orElse(null);
            sb.append("- 部门 id=").append(d.getId()).append(" 「").append(d.getName()).append("」");
            if (lead != null) {
                sb.append(" 负责人 id=").append(lead.getId()).append(" ").append(lead.getName())
                  .append("(").append(lead.getStatus()).append(")");
            } else {
                sb.append(" 暂无负责人");
            }
            sb.append(" | 成员:");
            String members = list.stream()
                    .filter(e -> !e.getId().equals(lead == null ? -1L : lead.getId()))
                    .map(e -> e.getName() + (e.getTitle() == null ? "" : "(" + e.getTitle() + ")"))
                    .reduce((a, b) -> a + "、" + b).orElse("无");
            sb.append(members).append("\n");
        }
        sb.append("\n【拆解要求】\n");
        sb.append("1. 只拆 2-5 条部门工作流,每条必须能独立验收;不需要的部门不要硬凑。\n");
        sb.append("2. 每条工作流写清楚:要产出什么、为什么需要、整体验收标准;不写具体执行步骤。\n");
        sb.append("2.1 不同部门不许认领同一份产出。同一件东西(例如“产品页主体文案”)只能由一个部门负责,"
                + "其他部门需要时直接复用那一份,不要各自立一条工作流——重复开工只会互相挡住、白烧预算。\n");
        sb.append("3. 负责人必须使用上面列出的该部门负责人 id;部门 id 必须准确。\n");
        sb.append("4. 验收标准必须可检查:数量、结构、覆盖范围、可用性、证据要求。禁止“高质量”“尽量完善”这类空话。\n");
        sb.append("5. 缺信息分两种处理,不要混为一谈:\n");
        sb.append("   · 可以合理假设的(文风、篇幅、受众侧重、内容结构):直接按假设推进,把假设写进对应的 brief;\n");
        sb.append("   · 必须核实的外部事实(产品功能名与数量、真实客户或业绩数据、价格、资质、上线时间、实际人员配置):"
                + "绝不猜、绝不编,写进 needsInput 交创始人补料。宁可少写一句,也不许编一个数字。\n");
        sb.append("6. 交付物必须是对目标直接有用的成品(文案、页面内容、方案、报告、代码、设计说明)。"
                + "禁止把“核对清单”“执行台账”“自检表”“边界说明”“工作计划”这类过程性产物当成交付物;"
                + "只有目标本身明确要求时,才产出清单类文件。\n");
        sb.append("7. 验收标准必须能被机械核对,但不许写无法机械判定的绝对化限定:"
                + "不要写“全部中文”“不含任何英文”“文字必须完美”这类;"
                + "确实需要限定语种或形式时,写明例外(如“中文表述,行业通用缩写 AI/API 等与专有名词除外”)。\n");
        sb.append("\n只输出一个 JSON 对象,不要 Markdown 代码块,不要任何解释:\n");
        sb.append("{\"summary\":\"你的拆解思路(一两句话)\",");
        sb.append("\"needsInput\":[\"待创始人核实的事项 —— 为什么必须要,没有它会怎样\"],\"workstreams\":[");
        sb.append("{\"departmentId\":1,\"leadId\":12,\"title\":\"工作流标题\",\"brief\":\"要产出的内容与假设\",");
        sb.append("\"acceptanceCriteria\":\"逐条可检查的验收标准\",\"dueAt\":\"2026-11-01T00:00:00Z 或空\"}]}");
        sb.append("\nneedsInput 只放真的必须由创始人提供的外部事实(产品事实、真实数据、对外口径、资质);"
                + "本公司内部谁都能自己产出的东西(清单、说明、方案)不要往里塞。没有就写 []。");
        return sb.toString();
    }

    // ==================== 部门负责人拆活 ====================

    public void requestWorkstreamPlan(Long wsId) {
        if (!planningWorkstreams.add(wsId)) return;
        planner.submit(() -> {
            try {
                planWorkstream(wsId);
            } catch (Exception e) {
                blockWorkstream(wsId, "拆解失败:" + e.getMessage());
            } finally {
                planningWorkstreams.remove(wsId);
            }
        });
    }

    private void planWorkstream(Long wsId) {
        Workstream ws = wsRepo.findById(wsId).orElse(null);
        if (ws == null || Workstream.DONE.equals(ws.getStatus()) || Workstream.PAUSED.equals(ws.getStatus())) return;
        if (delRepo.countByWorkstreamId(wsId) > 0) {
            if (Workstream.PLANNING.equals(ws.getStatus())) {
                ws.setStatus(Workstream.ACTIVE);
                wsRepo.save(ws);
            }
            return;
        }
        Goal g = ws.getGoal();
        if (g == null || Goal.PAUSED.equals(g.getStatus()) || Goal.DONE.equals(g.getStatus())) return;
        checkBudget(g.getId());

        Employee lead = resolveLead(ws);
        if (lead == null) {
            blockWorkstream(wsId, "部门没有可用负责人");
            return;
        }
        List<Employee> team = teamOf(ws.getDepartment(), lead);
        if (team.isEmpty()) {
            blockWorkstream(wsId, "部门没有可用岗位");
            return;
        }

        String sys = nz(lead.getSystemPrompt())
                + "\n\n【你的质量准则】\n" + nz(lead.getStandards())
                + "\n\n【公司质量底线】\n" + qualityBar(g)
                + "\n\n你是部门负责人,只负责把工作流拆成可执行、可验收的交付物,并指定执行人。你不亲自写交付物正文。";
        String user = buildWorkstreamPrompt(g, ws, lead, team);

        String reply = llm.chatStream(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)), lead.getModel(), null);
        tokenRepo.save(new TokenLog(null, g.getId(), "LEAD_PLAN",
                CeoLoopService.estimateTokens(sys.length() + user.length() + reply.length())));

        JsonNode root;
        try {
            root = mapper.readTree(extractJson(reply));
        } catch (Exception e) {
            throw new IllegalStateException("负责人拆解结果不是有效 JSON: " + clip(reply, 300));
        }
        String summary = root.path("summary").asText("负责人已完成拆解");
        JsonNode arr = root.path("deliverables");
        List<Deliverable> created = new ArrayList<>();
        // 本次拆解下标 → 真实交付物 id。重复项被合并时,下标指向被合并到的那一项,依赖才不会断。
        Map<Integer, Long> indexToId = new LinkedHashMap<>();
        Map<Long, Integer> pendingDep = new LinkedHashMap<>();
        List<String> merged = new ArrayList<>();
        int order = 0;
        String deptName = ws.getDepartment() == null ? "部门" : ws.getDepartment().getName();
        // 两个部门可能同时拆活:读已有清单与落库必须在同一把锁里,否则各自都看不到对方刚写进去的东西
        synchronized (planLock) {
            List<String[]> existing = goalDeliverableTitles(g.getId());
            if (arr.isArray()) {
                int idx = 0;
                for (JsonNode node : arr) {
                    int myIndex = idx++;
                    if (order >= 8) break;
                    String title = clip(node.path("title").asText(""), 300);
                    if (title.isBlank()) continue;
                    String[] dup = DeliverableDedup.findDuplicate(title, existing);
                    if (dup != null) {
                        indexToId.put(myIndex, Long.parseLong(dup[0]));
                        merged.add("「" + title + "」并入「" + dup[1] + "」");
                        continue;
                    }
                    Employee owner = findTeamMember(team, node.path("ownerId").asLong(0));
                    if (owner == null) owner = lead;
                    Deliverable d = new Deliverable();
                    d.setGoal(g);
                    d.setWorkstream(ws);
                    d.setOwner(owner);
                    d.setReviewer(pickReviewer(ws, owner));
                    d.setTitle(title);
                    d.setBrief(clip(node.path("brief").asText(""), 8000));
                    d.setAcceptanceCriteria(clip(node.path("acceptanceCriteria").asText(""), 8000));
                    d.setOutputType(clip(node.path("outputType").asText("文档"), 40));
                    String priority = node.path("priority").asText("MEDIUM").toUpperCase(Locale.ROOT);
                    d.setPriority(List.of("HIGH", "MEDIUM", "LOW").contains(priority) ? priority : "MEDIUM");
                    d.setDueAt(parseInstant(node.path("dueAt").asText("")));
                    if (d.getDueAt() == null) d.setDueAt(ws.getDueAt() != null ? ws.getDueAt() : g.getDeadline());
                    d.setSortOrder(++order);
                    d.setStatus(Deliverable.PENDING);
                    Deliverable saved = delRepo.save(d);
                    created.add(saved);
                    existing.add(new String[]{String.valueOf(saved.getId()), title});
                    indexToId.put(myIndex, saved.getId());
                    JsonNode deps = node.path("dependsOn");
                    if (deps.isArray() && !deps.isEmpty()) pendingDep.put(saved.getId(), deps.get(0).asInt(-1));
                }
            }
        }
        // 依赖按本次拆解内的下标映射为真实 id;被合并掉的那一项也能映射到真正承担它的交付物
        for (Map.Entry<Long, Integer> e : pendingDep.entrySet()) {
            Long selfId = e.getKey();
            Long targetId = indexToId.get(e.getValue());
            if (targetId == null || targetId.equals(selfId)) continue;
            Deliverable d = delRepo.findById(selfId).orElse(null);
            if (d == null) continue;
            d.setDependencies(String.valueOf(targetId));
            delRepo.save(d);
        }
        if (!merged.isEmpty()) {
            activity(Activity.REPORT, "「" + deptName + "」有 " + merged.size()
                    + " 项与其他部门重复,已合并,不重复开工:" + clip(String.join(";", merged), 600), g.getId());
        }
        if (created.isEmpty() && merged.isEmpty()) {
            // 负责人没拆出有效交付物时,至少保证工作流有一个可验收出口
            Deliverable d = new Deliverable();
            d.setGoal(g);
            d.setWorkstream(ws);
            d.setOwner(lead);
            d.setReviewer(pickReviewer(ws, lead));
            d.setTitle(ws.getTitle());
            d.setBrief(ws.getBrief());
            d.setAcceptanceCriteria(ws.getAcceptanceCriteria());
            d.setOutputType("文档");
            d.setStatus(Deliverable.PENDING);
            d.setDueAt(ws.getDueAt() != null ? ws.getDueAt() : g.getDeadline());
            d.setSortOrder(1);
            created.add(delRepo.save(d));
        }

        ws.setLead(lead);
        ws.setStatus(Workstream.ACTIVE);
        ws.setPlanSummary(clip(summary, 2000));
        ws.setUpdatedAt(Instant.now());
        wsRepo.save(ws);

        recordNeedsInput(g, root.path("needsInput"), ws.getDepartment() == null ? "部门负责人" : ws.getDepartment().getName());

        if (created.isEmpty()) {
            activity(Activity.REPORT, "「" + deptName + "」本次没有新立项:它要的产出已由其他部门承担,直接引用对方的交付物。", g.getId());
        } else {
            activity(Activity.REPORT, "部门「" + deptName + "」已拆出 "
                    + created.size() + " 项交付物:" + clip(summary, 500), g.getId());
        }
        pushGoal(g.getId());
    }

    /** 同一目标下已有交付物的 [id, 标题],供跨部门查重。 */
    private List<String[]> goalDeliverableTitles(Long goalId) {
        List<String[]> out = new ArrayList<>();
        for (Deliverable d : delRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId)) {
            out.add(new String[]{String.valueOf(d.getId()), d.getTitle() == null ? "" : d.getTitle()});
        }
        return out;
    }

    private String buildWorkstreamPrompt(Goal g, Workstream ws, Employee lead, List<Employee> team) {
        StringBuilder sb = new StringBuilder();
        sb.append("【公司目标】\n").append(g.getTitle()).append("\n").append(nz(g.getBrief())).append("\n");
        sb.append("成功标准:").append(nz(g.getSuccessCriteria())).append("\n");
        String facts = factsBlock(g);
        if (!facts.isBlank()) sb.append("\n").append(facts).append("\n");
        sb.append("\n【你负责的部门工作流】\n");
        sb.append("标题:").append(ws.getTitle()).append("\n");
        sb.append("说明:").append(nz(ws.getBrief())).append("\n");
        sb.append("整体验收标准:").append(nz(ws.getAcceptanceCriteria())).append("\n\n");
        List<String[]> taken = goalDeliverableTitles(g.getId());
        if (!taken.isEmpty()) {
            sb.append("【其他部门已经在做的交付物】\n");
            for (String[] t : taken) sb.append("- ").append(t[1]).append("\n");
            sb.append("同一件东西只做一次:上面已有的产出不要重复立项,需要时直接作为你的输入引用。\n\n");
        }
        sb.append("【你部门可调用的岗位】\n");
        for (Employee e : team) {
            sb.append("- id=").append(e.getId()).append(" ").append(e.getName())
              .append(" | ").append(nz(e.getTitle()))
              .append(" | 状态:").append(e.getStatus())
              .append(" | 擅长:").append(clip(nz(e.getSystemPrompt()), 120)).append("\n");
        }
        sb.append("\n【拆活要求】\n");
        sb.append("1. 拆 2-6 项交付物,每项是单一责任人可以独立完成的完整产出。\n");
        sb.append("2. 每项必须有可检查的验收标准:具体结构、必须覆盖的要点、证据/数据要求。\n");
        sb.append("3. 依赖用本次列表下标表示:dependsOn:[0] 表示依赖第 1 项;没有依赖写 []。\n");
        sb.append("4. 执行人用上面的岗位 id;不要让所有人都做同一件事,按专长分工。\n");
        sb.append("5. outputType 只能是:文档 / 方案 / 代码 / 清单 / 设计说明 / 报告。\n");
        sb.append("6. 每项都要能直接进入评审,不允许空泛任务(如“优化一下”“完善内容”)。\n");
        sb.append("7. 交付物必须是目标读者拿来就能用的成品。禁止把“核对清单”“执行台账”“自检表”"
                + "“边界说明”“进度计划”这类过程性产物当成交付物 —— 它们是评审的工作,不是成果。"
                + "只有工作流明确要求清单时才产出清单。\n");
        sb.append("8. 缺信息分两种处理:文风、篇幅、结构这类可以假设,写进 brief;"
                + "产品功能名、真实数据、价格、资质这类必须核实的事实,写进 needsInput,不要编造。\n");
        sb.append("9. 验收标准要可机械核对,但禁止无法机械判定的绝对化限定:不写“全部中文”“不含英文”“完美”这类;"
                + "需要限定语种时必须带例外(如“中文表述,AI/API 等行业缩写与专有名词除外”)。\n");
        sb.append("\n只输出一个 JSON 对象,不要 Markdown 代码块,不要解释:\n");
        sb.append("{\"summary\":\"拆活思路\",\"needsInput\":[\"待创始人核实的事项 —— 为什么必须要\"],\"deliverables\":[");
        sb.append("{\"ownerId\":21,\"title\":\"交付物标题\",\"brief\":\"具体要做什么\",");
        sb.append("\"acceptanceCriteria\":\"逐条验收标准\",\"outputType\":\"文档\",");
        sb.append("\"dependsOn\":[],\"priority\":\"HIGH\",\"dueAt\":\"2026-11-01T00:00:00Z 或空\"}]}");
        sb.append("\nneedsInput 没有就写 [],只放公司外部、只能由创始人给出的事实(产品事实、真实数据、对外口径、资质);"
                + "本部门或别的部门自己能产出的东西不要填 —— 那是派活,不是缺料。");
        return sb.toString();
    }

    // ==================== 执行 ====================

    private void startWork(Deliverable d) {
        Goal g = d.getGoal();
        Workstream ws = d.getWorkstream();
        if (g == null || ws == null) return;
        Employee owner = resolveOwner(d);
        if (owner == null) {
            blockDeliverable(d, "没有可用执行人");
            return;
        }
        d.setAttempts(d.getAttempts() + 1);
        d.setStatus(Deliverable.RUNNING);
        d.setUpdatedAt(Instant.now());
        delRepo.save(d);

        try {
            String context = buildUpstreamContext(d);
            String instruction = buildWorkInstruction(d, owner);
            Task task = taskService.submitEngineTask(owner.getId(), instruction, g.getId(), ws.getId(), d.getId(),
                    Task.KIND_WORK, d.getAttempts(), d.getAcceptanceCriteria(), context, d.getLastArtifactId());
            d.setCurrentTaskId(task.getId());
            delRepo.save(d);
            pushGoal(g.getId());
        } catch (Exception e) {
            d.setStatus(Deliverable.READY);
            d.setReviewNotes(clip("派发失败,稍后自动重试:" + nz(e.getMessage()), 2000));
            d.setUpdatedAt(Instant.now());
            delRepo.save(d);
            log.warn("派发交付物 {} 失败: {}", d.getId(), e.getMessage());
        }
    }

    /** 服务重启或事件丢失时,把卡在 RUNNING 的交付物按任务真实状态收尾。 */
    private void recoverStaleDeliverables() {
        for (Deliverable d : delRepo.findByStatusIn(List.of(Deliverable.RUNNING))) {
            Goal g = d.getGoal();
            if (g == null || Goal.PAUSED.equals(g.getStatus())) continue;
            Long taskId = d.getCurrentTaskId();
            if (taskId == null) {
                d.setStatus(Deliverable.READY);
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
                continue;
            }
            Task t = taskRepo.findById(taskId).orElse(null);
            if (t == null) {
                d.setCurrentTaskId(null);
                d.setStatus(Deliverable.READY);
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
                continue;
            }
            if (Task.DONE.equals(t.getStatus())) {
                handleTaskFinished(new TaskFinishedEvent(taskId, true, null));
            } else if (Task.FAILED.equals(t.getStatus()) || Task.CANCELLED.equals(t.getStatus())) {
                handleTaskFinished(new TaskFinishedEvent(taskId, false, t.getError()));
            }
        }
    }

    private String buildWorkInstruction(Deliverable d, Employee owner) {
        Goal g = d.getGoal();
        Workstream ws = d.getWorkstream();
        StringBuilder sb = new StringBuilder();
        sb.append("你正在执行 AI 公司目标下的正式交付,不是聊天。产出会直接进入评审与成果库。\n\n");
        sb.append("【公司目标】").append(g.getTitle()).append("\n");
        sb.append("【目标说明】").append(nz(g.getBrief())).append("\n");
        String facts = factsBlock(g);
        if (!facts.isBlank()) sb.append("\n").append(facts).append("\n");
        if (d.getAttempts() > 1) {
            sb.append("\n【返工要求】这是第 ").append(d.getAttempts()).append(" 次执行。必须逐条修复上次评审意见,不能只做表面修改。\n");
            if (d.getReviewNotes() != null && !d.getReviewNotes().isBlank()) {
                sb.append("上次评审意见:\n").append(clip(d.getReviewNotes(), 2500)).append("\n");
            }
        }
        sb.append("\n【交付物】").append(d.getTitle()).append("(").append(nz(d.getOutputType())).append(")\n");
        sb.append("【要做什么】").append(nz(d.getBrief())).append("\n");
        sb.append("【验收标准】\n").append(nz(d.getAcceptanceCriteria())).append("\n");
        sb.append("【质量底线】\n").append(qualityBar(g)).append("\n");
        sb.append("\n【输出要求】\n");
        sb.append("1. 直接输出交付物正文,Markdown 格式;不要写“好的/如下/我将”,不要解释你的过程。\n");
        sb.append("2. 结论先行,结构清楚,内容具体;需要数据或示例时给出可核查的内容,不确定处标注假设。\n");
        sb.append("3. 严禁编造事实:产品功能名、客户名称、业绩数字、价格、资质这类没有来源的内容,"
                + "写成「[待确认:xxx]」占位,不要自己填一个看起来合理的值。\n");
        sb.append("4. 对照验收标准逐条自检,确保没有遗漏。\n");
        sb.append("5. 只输出最终稿本身,不要附加“希望有帮助”等客套话。");
        return clip(sb.toString(), 7800);
    }

    private String buildUpstreamContext(Deliverable d) {
        StringBuilder ctx = new StringBuilder();
        Set<Long> depIds = dependencyIds(d);
        for (Long id : depIds) {
            Deliverable dep = delRepo.findById(id).orElse(null);
            if (dep == null) continue;
            Artifact art = null;
            if (dep.getArtifactId() != null) art = artRepo.findById(dep.getArtifactId()).orElse(null);
            if (art == null && dep.getLastArtifactId() != null) art = artRepo.findById(dep.getLastArtifactId()).orElse(null);
            if (art == null) continue;
            ctx.append("【上游成果 · ").append(dep.getTitle()).append(" · 评分 ").append(art.getScore()).append("】\n");
            ctx.append(clip(art.getContent(), 6000)).append("\n\n");
        }
        if (d.getLastArtifactId() != null) {
            Artifact prev = artRepo.findById(d.getLastArtifactId()).orElse(null);
            if (prev != null) {
                ctx.append("【上一版成果 · 待修订】\n").append(clip(prev.getContent(), 6000)).append("\n\n");
            }
        }
        return clip(ctx.toString(), 60000);
    }

    // ==================== 评审与归档 ====================

    /** 事件线程与定时补偿线程可能同时到达,串行化处理避免同一交付物重复产出成果物。 */
    private synchronized void handleTaskFinished(TaskFinishedEvent event) {
        Task task = taskRepo.findById(event.taskId()).orElse(null);
        if (task == null || task.getDeliverableId() == null) return;
        Deliverable d = delRepo.findById(task.getDeliverableId()).orElse(null);
        if (d == null) return;
        boolean stale = d.getCurrentTaskId() != null && !d.getCurrentTaskId().equals(task.getId());
        if (stale) return;

        if (Task.KIND_WORK.equals(task.getKind())) {
            // 事件与定时补偿可能同时到达,用状态做幂等保护
            if (!Deliverable.RUNNING.equals(d.getStatus())) return;
            if (event.success() && Task.DONE.equals(task.getStatus())) {
                handleWorkSuccess(task, d);
            } else {
                handleWorkFailure(task, d, event.error());
            }
        } else if (Task.KIND_REVIEW.equals(task.getKind())) {
            if (!Deliverable.REVIEW.equals(d.getStatus())) return;
            if (event.success() && Task.DONE.equals(task.getStatus())) {
                handleReviewSuccess(task, d);
            } else {
                handleReviewFailure(task, d, event.error());
            }
        }
    }

    /** 成果已落库但评审任务没建起来(重启/异常)时补建评审。 */
    private void recoverReviewStages() {
        for (Deliverable d : delRepo.findByStatusIn(List.of(Deliverable.REVIEW))) {
            if (d.getCurrentTaskId() != null) continue;
            Goal g = d.getGoal();
            if (g == null || Goal.PAUSED.equals(g.getStatus()) || Goal.DONE.equals(g.getStatus())) continue;
            Artifact art = d.getLastArtifactId() == null ? null
                    : artRepo.findById(d.getLastArtifactId()).orElse(null);
            if (art != null) createReviewTask(d, art);
        }
    }

    private void handleWorkSuccess(Task task, Deliverable d) {
        Goal g = d.getGoal();
        Workstream ws = d.getWorkstream();
        if (g == null || ws == null) return;

        int version = (int) artRepo.countByDeliverableId(d.getId()) + 1;
        Artifact art = new Artifact();
        art.setGoal(g);
        art.setWorkstream(ws);
        art.setDeliverable(d);
        art.setAuthor(task.getEmployee());
        art.setTitle(d.getTitle());
        art.setType(nz(d.getOutputType()));
        art.setVersion(version);
        art.setContent(nz(task.getResult()));
        art.setStatus(Artifact.REVIEW);
        art = artRepo.save(art);

        d.setLastArtifactId(art.getId());
        d.setStatus(Deliverable.REVIEW);
        d.setCurrentTaskId(null);
        d.setReviewAttempts(0);
        d.setUpdatedAt(Instant.now());
        delRepo.save(d);

        Employee reviewer = pickReviewer(ws, d.getOwner());
        d.setReviewer(reviewer);
        delRepo.save(d);

        activity(Activity.REPORT, "「" + d.getTitle() + "」已产出 v" + version
                + ",交 " + (reviewer == null ? "负责人" : reviewer.getName()) + " 评审。", g.getId());
        createReviewTask(d, art);
        pushGoal(g.getId());
    }

    private void createReviewTask(Deliverable d, Artifact art) {
        if (art == null || d.getStatus().equals(Deliverable.DONE)) return;
        Goal g = d.getGoal();
        Workstream ws = d.getWorkstream();
        if (g == null) return;
        Employee reviewer = d.getReviewer();
        if (reviewer == null || Employee.STATUS_DISABLED.equals(reviewer.getStatus())) {
            reviewer = pickReviewer(ws, d.getOwner());
            d.setReviewer(reviewer);
        }
        if (reviewer == null) {
            blockDeliverable(d, "没有可用评审人");
            return;
        }
        d.setReviewAttempts(d.getReviewAttempts() + 1);
        d.setCurrentTaskId(null);
        d.setStatus(Deliverable.REVIEW);
        d.setUpdatedAt(Instant.now());
        delRepo.save(d);

        int threshold = settings.get().reviewThreshold;
        StringBuilder ins = new StringBuilder();
        ins.append("你是「").append(reviewer.getName()).append("」,负责公司交付物的质量评审。请严格、具体,不要客套。\n\n");
        ins.append("【公司目标】").append(g.getTitle()).append("\n");
        ins.append("【交付物】").append(d.getTitle()).append("(").append(nz(d.getOutputType())).append(")\n");
        ins.append("【验收标准】\n").append(nz(d.getAcceptanceCriteria())).append("\n");
        ins.append("【质量底线】\n").append(qualityBar(g)).append("\n\n");
        String reviewFacts = factsBlock(g);
        if (!reviewFacts.isBlank()) ins.append(reviewFacts).append("\n\n");
        ins.append("【评审规则】\n");
        ins.append("1. 逐条对照验收标准,缺一项即不通过。\n");
        ins.append("2. 空话、正确但无法执行、缺少数据/证据、结构残缺、偏离目标,均不通过。\n");
        ins.append("3. 满分 100,达到 ").append(threshold).append(" 分才通过。\n");
        ins.append("4. 必须指出必须修改的具体问题,不要只说“可以更好”。\n");
        ins.append("5. 判定口径:按业务意图判,不按字面抠字眼。下列情形不算违规,不得据此扣分或不通过:\n");
        ins.append("   · 行业通用缩写与专有名词:AI、API、SaaS、CRM、PRD、URL、KPI 等;\n");
        ins.append("   · 品牌名、产品名、技术栈与系统名称;\n");
        ins.append("   · 行业内已通用的写法,如“转人工”“次日回复”“单据录入”。\n");
        ins.append("   若验收标准与上述冲突(例如要求“全部中文”),以“目标读者能看懂、交付物可直接使用”为最高准绳;\n");
        ins.append("   冲突本身写进 notes 提醒改口径,但不作为不通过的理由;只有真正影响可用性的问题才判不通过。\n");
        ins.append("6. 事实类问题单独处理:成果依赖的外部事实(产品功能名、真实客户或业绩数据、价格、资质)如果无法核实,\n");
        ins.append("   不要因为“和事实不符”判不通过 —— 你没有事实来源,反复打回只会让它继续编。\n");
        ins.append("   正确做法:该内容是否已明确标注为「[待确认]」;标了就视为合格处理,并把它列进 needsInput 交创始人补料。\n");
        ins.append("   只有「明明有据可查却写错」「凭空编出一个具体数字且没有任何标注」才判不通过。\n");
        ins.append("7. 只输出 JSON,不要 Markdown 代码块:\n");
        ins.append("{\"score\":0,\"pass\":false,\"summary\":\"一句话结论\",\"notes\":\"必须修改项,逐条\",");
        ins.append("\"strengths\":[\"优点\"],\"needsInput\":[\"需要创始人核实的事实 —— 缺了它成果哪里站不住\"]}");
        ins.append("\nneedsInput 没有就写 []。");

        try {
            String context = "【待评审成果 · v" + art.getVersion() + "】\n" + clip(art.getContent(), 60000);
            Task task = taskService.submitEngineTask(reviewer.getId(), clip(ins.toString(), 7800),
                    g.getId(), ws == null ? null : ws.getId(), d.getId(), Task.KIND_REVIEW,
                    d.getReviewAttempts(), d.getAcceptanceCriteria(), context, art.getId());
            d.setCurrentTaskId(task.getId());
            delRepo.save(d);
            pushGoal(g.getId());
        } catch (Exception e) {
            blockDeliverable(d, "评审任务派发失败:" + nz(e.getMessage()));
        }
    }

    private void handleReviewSuccess(Task task, Deliverable d) {
        Goal g = d.getGoal();
        Artifact art = task.getArtifactId() != null ? artRepo.findById(task.getArtifactId()).orElse(null)
                : (d.getLastArtifactId() != null ? artRepo.findById(d.getLastArtifactId()).orElse(null) : null);
        if (art == null) {
            blockDeliverable(d, "评审找不到对应成果物");
            return;
        }
        ReviewResult r = parseReview(task.getResult());
        int threshold = settings.get().reviewThreshold;
        boolean pass = r.pass && r.score >= threshold;
        art.setScore(r.score);
        art.setReviewNotes(clip(r.summary + (r.notes.isBlank() ? "" : "\n" + r.notes), 4000));
        art.setStatus(pass ? Artifact.APPROVED : Artifact.REJECTED);
        if (pass) art.setApprovedAt(Instant.now());
        artRepo.save(art);

        d.setReviewScore(r.score);
        d.setReviewNotes(clip(r.summary + (r.notes.isBlank() ? "" : "\n" + r.notes), 4000));
        d.setCurrentTaskId(null);
        d.setReviewAttempts(0);
        d.setUpdatedAt(Instant.now());

        // 评审也允许上报「我判不了,因为缺事实」:这类事项由创始人补料,而不是逼着成果继续编
        if (g != null) recordNeedsInput(g, r.needsInput, "评审");

        if (pass) {
            d.setStatus(Deliverable.DONE);
            d.setArtifactId(art.getId());
            delRepo.save(d);
            activity(Activity.REPORT, "「" + d.getTitle() + "」通过评审,评分 " + r.score + "。"
                    + (r.summary.isBlank() ? "" : " " + clip(r.summary, 300)), g == null ? null : g.getId());
            if (g != null) {
                exportService.autoExportGoal(g.getId());   // 静默落盘,失败不影响评审结论
                goalService.refreshProgress(g.getId());
                checkGoalCompletion(g);
                pushGoal(g.getId());
            }
        } else {
            // 评审说「缺事实」且分数明显不够:继续返工只会逼着它编,直接挂起等补料
            boolean factsMissing = r.needsInput != null && r.needsInput.isArray() && !r.needsInput.isEmpty();
            if (factsMissing && r.score < 70 && g != null) {
                d.setStatus(Deliverable.WAITING);
                d.setCurrentTaskId(null);
                d.setReviewNotes(clip("缺事实,已挂起等你补料 —— 补齐后自动重新排队,不会白费前面几轮。\n"
                        + r.summary + (r.notes.isBlank() ? "" : "\n" + r.notes), 4000));
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
                activity(Activity.ESCALATION, "「" + d.getTitle() + "」(" + r.score + " 分)缺事实,"
                        + "继续返工只能靠编,已停止返工并挂起。补料后自动重新排队。", g.getId());
                pushEscalation();
                pushGoal(g.getId());
                return;
            }
            int maxAttempts = Math.max(1, settings.get().maxReviewRounds) + 1;
            if (d.getAttempts() < maxAttempts) {
                d.setStatus(Deliverable.READY);
                delRepo.save(d);
                activity(Activity.REPORT, "「" + d.getTitle() + "」评审未通过(" + r.score + " 分),"
                        + "打回返工。问题:" + clip(r.notes, 500), g == null ? null : g.getId());
                if (g != null) pushGoal(g.getId());
            } else {
                delRepo.save(d);
                blockDeliverable(d, "评审未通过(" + r.score + " 分),返工轮次已用尽。" + clip(r.notes, 1500));
            }
        }
    }

    private void handleWorkFailure(Task task, Deliverable d, String error) {
        d.setCurrentTaskId(null);
        d.setReviewNotes(clip("上次执行失败:" + nz(error), 2000));
        d.setUpdatedAt(Instant.now());
        int maxAttempts = Math.max(1, settings.get().maxReviewRounds) + 1;
        if (d.getAttempts() < maxAttempts) {
            d.setStatus(Deliverable.READY);
            delRepo.save(d);
            Goal g = d.getGoal();
            activity(Activity.REPORT, "「" + d.getTitle() + "」执行失败,自动重试。原因:"
                    + clip(nz(error), 400), g == null ? null : g.getId());
            if (g != null) pushGoal(g.getId());
        } else {
            delRepo.save(d);
            blockDeliverable(d, "执行失败且重试次数已用尽:" + nz(error));
        }
    }

    private void handleReviewFailure(Task task, Deliverable d, String error) {
        d.setCurrentTaskId(null);
        if (d.getReviewAttempts() < 2) {
            Artifact art = d.getLastArtifactId() == null ? null : artRepo.findById(d.getLastArtifactId()).orElse(null);
            if (art != null) {
                activity(Activity.REPORT, "「" + d.getTitle() + "」评审任务异常,自动重试。原因:"
                        + clip(nz(error), 400), d.getGoalId());
                createReviewTask(d, art);
                return;
            }
        }
        blockDeliverable(d, "评审任务失败:" + nz(error));
    }

    private void checkGoalCompletion(Goal g) {
        List<Deliverable> ds = delRepo.findByGoalIdOrderBySortOrderAscIdAsc(g.getId());
        if (ds.isEmpty()) return;
        boolean allDone = ds.stream().allMatch(d -> Deliverable.DONE.equals(d.getStatus()));
        if (!allDone) return;
        if (Goal.DONE.equals(g.getStatus()) || Goal.PAUSED.equals(g.getStatus())) return;

        Goal fresh = goalRepo.findById(g.getId()).orElse(g);
        goalService.refreshProgress(fresh.getId());
        fresh = goalRepo.findById(g.getId()).orElse(fresh);
        int threshold = settings.get().reviewThreshold;
        int avg = fresh.getFinalScore();
        if (avg >= threshold) {
            fresh.setStatus(Goal.DONE);
            fresh.setProgress(100);
            fresh.setCeoSummary("目标完成:全部 " + ds.size() + " 项交付通过评审,均分 " + avg + "。");
            fresh.setUpdatedAt(Instant.now());
            goalRepo.save(fresh);
            activity(Activity.REPORT, "目标「" + fresh.getTitle() + "」达成。全部 " + ds.size()
                    + " 项交付通过评审,均分 " + avg + "。成果已归档,可一键导出。", fresh.getId());
            String dir = exportService.autoExportGoal(fresh.getId());
            if (dir != null) {
                activity(Activity.REPORT, "成果已落盘到:" + dir, fresh.getId());
            }
            pusher.push("goal-completed", Map.of("goalId", fresh.getId(), "title", fresh.getTitle(), "score", avg));
        } else {
            fresh.setStatus(Goal.REVIEW);
            fresh.setCeoSummary("全部交付完成,但均分 " + avg + " 低于质量线 " + threshold + ",需要创始人复核。");
            fresh.setUpdatedAt(Instant.now());
            goalRepo.save(fresh);
            activity(Activity.ESCALATION, "目标「" + fresh.getTitle() + "」全部交付完成,但评审均分 "
                    + avg + " 低于质量线 " + threshold + "。请创始人复核是否接受或要求返工。", fresh.getId());
            pushEscalation();
        }
        pushGoal(fresh.getId());
    }

    // ==================== 故障与升级 ====================

    private void failGoal(Long goalId, String message) {
        Goal g = goalRepo.findById(goalId).orElse(null);
        if (g == null) return;
        if (Goal.DONE.equals(g.getStatus()) || Goal.PAUSED.equals(g.getStatus())) return;
        String msg = nz(message);
        g.setStatus(Goal.FAILED);
        g.setCeoSummary("拆解失败:" + clip(msg, 180));
        g.setUpdatedAt(Instant.now());
        goalRepo.save(g);
        activity(Activity.REPORT, "目标拆解失败:" + clip(msg, 600)
                + (settings.hasProvider() ? "" : "(请先在设置页配置大模型服务)"), goalId);
        pushGoal(goalId);
    }

    private void blockWorkstream(Long wsId, String reason) {
        Workstream ws = wsRepo.findById(wsId).orElse(null);
        if (ws == null) return;
        ws.setStatus(Workstream.BLOCKED);
        ws.setUpdatedAt(Instant.now());
        wsRepo.save(ws);
        Goal g = ws.getGoal();
        Long goalId = g == null ? null : g.getId();
        if (g != null && !Goal.DONE.equals(g.getStatus()) && !Goal.PAUSED.equals(g.getStatus())) {
            g.setStatus(Goal.REVIEW);
            g.setCeoSummary("工作流受阻:" + clip(reason, 180));
            goalRepo.save(g);
        }
        activity(Activity.ESCALATION, "工作流「" + ws.getTitle() + "」受阻:" + clip(reason, 800), goalId);
        pushEscalation();
        if (goalId != null) pushGoal(goalId);
    }

    private void blockDeliverable(Deliverable d, String reason) {
        d.setStatus(Deliverable.BLOCKED);
        d.setCurrentTaskId(null);
        d.setReviewNotes(clip(reason, 4000));
        d.setUpdatedAt(Instant.now());
        delRepo.save(d);
        Goal g = d.getGoal();
        Long goalId = g == null ? null : g.getId();
        if (g != null && !Goal.DONE.equals(g.getStatus()) && !Goal.PAUSED.equals(g.getStatus())) {
            g.setStatus(Goal.REVIEW);
            g.setCeoSummary("交付受阻:" + clip(reason, 180));
            g.setUpdatedAt(Instant.now());
            goalRepo.save(g);
        }
        activity(Activity.ESCALATION, "交付物「" + d.getTitle() + "」受阻:" + clip(reason, 1200), goalId);
        pushEscalation();
        if (goalId != null) pushGoal(goalId);
    }

    // ==================== 选择负责人 / 评审人 ====================

    private Employee resolveLead(Workstream ws) {
        Employee lead = ws.getLead();
        if (isActive(lead)) return lead;
        List<Employee> list = ws.getDepartment() == null ? List.of()
                : empRepo.findByDepartmentIdOrderByIdAsc(ws.getDepartment().getId());
        Employee acting = list.stream()
                .filter(e -> Employee.LEVEL_LEAD.equals(e.getLevel()) && isActive(e))
                .findFirst()
                .orElseGet(() -> list.stream().filter(this::isActive).findFirst().orElse(null));
        if (acting != null && !Objects.equals(acting.getId(), lead == null ? null : lead.getId())) {
            ws.setLead(acting);
            wsRepo.save(ws);
            activity(Activity.REPORT, "工作流「" + ws.getTitle() + "」负责人不可用,由 " + acting.getName() + " 代管。",
                    ws.getGoalId());
        }
        return acting;
    }

    private Employee resolveOwner(Deliverable d) {
        Employee owner = d.getOwner();
        if (isActive(owner)) return owner;
        Workstream ws = d.getWorkstream();
        List<Employee> list = ws == null || ws.getDepartment() == null ? List.of()
                : empRepo.findByDepartmentIdOrderByIdAsc(ws.getDepartment().getId());
        Employee alt = list.stream().filter(this::isActive).findFirst().orElse(null);
        if (alt != null) {
            d.setOwner(alt);
            delRepo.save(d);
            activity(Activity.REPORT, "交付物「" + d.getTitle() + "」执行人不可用,改由 " + alt.getName() + " 执行。",
                    d.getGoalId());
        }
        return alt;
    }

    private Employee pickReviewer(Workstream ws, Employee owner) {
        List<Employee> team = ws == null || ws.getDepartment() == null ? List.of()
                : empRepo.findByDepartmentIdOrderByIdAsc(ws.getDepartment().getId());
        Long ownerId = owner == null ? null : owner.getId();
        for (Employee e : team) {
            if (isActive(e) && !Objects.equals(e.getId(), ownerId) && e.getName() != null && e.getName().contains("测试")) {
                return e;
            }
        }
        if (ws != null && isActive(ws.getLead()) && !Objects.equals(ws.getLead().getId(), ownerId)) {
            return ws.getLead();
        }
        for (Employee e : team) {
            if (isActive(e) && !Objects.equals(e.getId(), ownerId)) return e;
        }
        for (Employee e : empRepo.findAll()) {
            if (isActive(e) && Employee.LEVEL_LEAD.equals(e.getLevel()) && !Objects.equals(e.getId(), ownerId)) return e;
        }
        return isActive(owner) ? owner : null;
    }

    private boolean isActive(Employee e) {
        return e != null && !e.isCeo() && !Employee.STATUS_DISABLED.equals(e.getStatus());
    }

    private List<Employee> teamOf(Department dept, Employee lead) {
        if (dept == null) return lead == null ? List.of() : List.of(lead);
        List<Employee> out = new ArrayList<>();
        for (Employee e : empRepo.findByDepartmentIdOrderByIdAsc(dept.getId())) {
            if (isActive(e)) out.add(e);
        }
        if (out.isEmpty() && isActive(lead)) out.add(lead);
        return out;
    }

    private Employee findTeamMember(List<Employee> team, Long id) {
        if (id == null || id <= 0) return null;
        return team.stream().filter(e -> id.equals(e.getId())).findFirst().orElse(null);
    }

    private Employee findLead(Department dept, Long leadId) {
        List<Employee> list = empRepo.findByDepartmentIdOrderByIdAsc(dept.getId());
        if (leadId != null && leadId > 0) {
            Employee e = list.stream().filter(x -> leadId.equals(x.getId())).findFirst().orElse(null);
            if (isActive(e)) return e;
        }
        return list.stream().filter(e -> Employee.LEVEL_LEAD.equals(e.getLevel()) && isActive(e)).findFirst()
                .orElseGet(() -> list.stream().filter(this::isActive).findFirst().orElse(null));
    }

    private Department findDepartment(JsonNode node) {
        long id = node.path("departmentId").asLong(0);
        if (id > 0) {
            Department d = deptRepo.findById(id).orElse(null);
            if (d != null) return d;
        }
        String name = node.path("department").asText("");
        if (!name.isBlank()) {
            return deptRepo.findAllByOrderBySortOrderAscIdAsc().stream()
                    .filter(d -> name.contains(d.getName()) || d.getName().contains(name))
                    .findFirst().orElse(null);
        }
        return null;
    }

    // ==================== 依赖 / 预算 / 工具 ====================

    /** 上游的三种状态:全交付 / 还在推进 / 已经卡死 */
    private enum DepState { DONE, RUNNING, BROKEN }

    private DepState dependencyState(Deliverable d) {
        DepState state = DepState.DONE;
        for (Long id : dependencyIds(d)) {
            Deliverable dep = delRepo.findById(id).orElse(null);
            if (dep == null) continue;
            String s = dep.getStatus();
            if (Deliverable.BLOCKED.equals(s) || Deliverable.CANCELLED.equals(s)) return DepState.BROKEN;
            if (!Deliverable.DONE.equals(s)) state = DepState.RUNNING;
        }
        return state;
    }

    /** 说清楚是哪一项上游卡住了,创始人才能判断该重试哪一步。 */
    private String brokenReason(Deliverable d) {
        StringBuilder sb = new StringBuilder("上游未交付,本项已挂起等待:");
        int n = 0;
        for (Long id : dependencyIds(d)) {
            Deliverable dep = delRepo.findById(id).orElse(null);
            if (dep == null) continue;
            String s = dep.getStatus();
            if (Deliverable.BLOCKED.equals(s) || Deliverable.CANCELLED.equals(s)) {
                sb.append("\n- 「").append(dep.getTitle()).append("」").append("CANCELLED".equals(s) ? "已取消" : "受阻");
                n++;
            }
        }
        if (n == 0) sb.append("\n- 上游交付物缺失");
        sb.append("\n上游恢复后会自动重新排队,无需手工操作。");
        return sb.toString();
    }

    private void markWaiting(Deliverable d, String reason) {
        boolean changed = !Deliverable.WAITING.equals(d.getStatus());
        d.setStatus(Deliverable.WAITING);
        d.setCurrentTaskId(null);
        d.setReviewNotes(clip(reason, 4000));
        d.setUpdatedAt(Instant.now());
        delRepo.save(d);
        if (!changed) return;
        Goal g = d.getGoal();
        activity(Activity.REPORT, "「" + d.getTitle() + "」上游未完成,已挂起等待(不空上下文硬干)。"
                + clip(reason, 300), g == null ? null : g.getId());
        if (g != null) pushGoal(g.getId());
    }

    private Set<Long> dependencyIds(Deliverable d) {
        Set<Long> ids = new LinkedHashSet<>();
        if (d.getDependencies() == null || d.getDependencies().isBlank()) return ids;
        for (String s : d.getDependencies().split(",")) {
            try {
                long id = Long.parseLong(s.trim());
                if (id > 0) ids.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    private boolean budgetAvailable() {
        long budget = settings.get().dailyTokenBudget;
        if (budget <= 0) return true;
        return tokenRepo.tokensSince(Instant.now().truncatedTo(ChronoUnit.DAYS)) < budget;
    }

    private boolean goalBudgetExceeded(Goal g) {
        if (g.getBudgetTokens() <= 0) return false;
        if (tokenRepo.tokensOfGoal(g.getId()) < g.getBudgetTokens()) return false;
        if (!Goal.PAUSED.equals(g.getStatus())) {
            g.setStatus(Goal.PAUSED);
            g.setCeoSummary("目标 token 预算已用尽,已自动暂停。");
            goalRepo.save(g);
            activity(Activity.ESCALATION, "目标「" + g.getTitle() + "」token 预算用尽,已自动暂停。", g.getId());
            pushEscalation();
            pushGoal(g.getId());
        }
        return true;
    }

    private void checkBudget(Long goalId) {
        if (!settings.hasProvider()) throw new IllegalStateException("尚未配置大模型 API,请在「设置」中填写服务商信息");
        if (!budgetAvailable()) throw new IllegalStateException("今日 token 预算已用尽,自动拆解已暂停");
        Goal g = goalRepo.findById(goalId).orElse(null);
        if (g != null && g.getBudgetTokens() > 0 && tokenRepo.tokensOfGoal(goalId) >= g.getBudgetTokens()) {
            throw new IllegalStateException("目标 token 预算已用尽");
        }
    }

    private String qualityBar(Goal g) {
        if (g != null && g.getQualityBar() != null && !g.getQualityBar().isBlank()) return g.getQualityBar();
        String q = settings.get().qualityBar;
        return q == null || q.isBlank() ? SettingsService.DEFAULT_QUALITY_BAR : q;
    }

    /** 创始人已确认的事实:所有拆解、执行、评审都以此为准,不许另行编造。 */
    private String factsBlock(Goal g) {
        String facts = g == null ? "" : nz(g.getProvidedFacts()).trim();
        if (facts.isBlank()) return "";
        return "【创始人已确认的事实 · 必须以此为准】\n" + clip(facts, 6000)
                + "\n以上事实之外的信息不要编造;与之冲突的说法一律以这里为准。";
    }

    // ==================== 缺料上报 ====================

    /**
     * 登记需要创始人补充的事实。CEO 拆解、部门拆活、评审三处都会调用。
     * 内容只增不刷:同一件事重复上报只保留一条。
     */
    private void recordNeedsInput(Goal g, JsonNode arr, String from) {
        if (g == null || arr == null || !arr.isArray() || arr.isEmpty()) return;
        Goal fresh = goalRepo.findById(g.getId()).orElse(null);
        if (fresh == null) return;
        List<String> items = parseNeedsInput(fresh.getNeedsInput());
        List<String> added = new ArrayList<>();
        for (JsonNode n : arr) {
            String text;
            if (n.isObject()) {
                String why = n.path("why").asText("");
                text = nz(n.path("item").asText("")) + (why.isBlank() ? "" : " —— " + why);
            } else {
                text = n.asText("");
            }
            String item = clip(text.trim(), 280);
            if (item.isBlank()) continue;
            if (items.stream().anyMatch(x -> similar(x, item))) continue;
            items.add(item);
            added.add(item);
        }
        if (added.isEmpty()) return;
        while (items.size() > 12) items.remove(0);
        fresh.setNeedsInput(writeNeedsInput(items));
        fresh.setUpdatedAt(Instant.now());
        goalRepo.save(fresh);
        activity(Activity.ESCALATION, from + "需要你先确认 " + added.size() + " 件事,"
                + "否则这部分成果只能靠编:" + clip(String.join(";", added), 700), fresh.getId());
        pushEscalation();
        pushGoal(fresh.getId());
    }

    /** 归一化后前 12 字相同即视为同一件事,避免 CEO/负责人/评审把同一条重复堆进去。 */
    private boolean similar(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.contains(y) || y.contains(x)) return true;
        String hx = x.length() > 12 ? x.substring(0, 12) : x;
        String hy = y.length() > 12 ? y.substring(0, 12) : y;
        return x.contains(hy) || y.contains(hx);
    }

    private String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s,，。.、:：;；()（）\\[\\]【】《》\"'“”‘’—\\-]+", "");
    }

    private List<String> parseNeedsInput(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        try {
            JsonNode n = mapper.readTree(raw);
            if (n.isArray()) {
                for (JsonNode x : n) {
                    String s = x.asText("");
                    if (!s.isBlank()) out.add(s);
                }
                return out;
            }
        } catch (Exception ignored) { }
        for (String line : raw.split("\n")) {
            if (!line.isBlank()) out.add(line.trim());
        }
        return out;
    }

    private String writeNeedsInput(List<String> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void activity(String type, String content, Long goalId) {
        Activity a = new Activity(type, content, null);
        a.setGoalId(goalId);
        activityRepo.save(a);
    }

    private void pushEscalation() {
        pusher.push("escalation", Map.of("count",
                activityRepo.countByTypeAndStatus(Activity.ESCALATION, Activity.OPEN)));
    }

    private void pushGoal(Long goalId) {
        Goal g = goalRepo.findById(goalId).orElse(null);
        if (g == null) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("goal", g);
        p.put("goalId", g.getId());
        pusher.push("goal-update", p);
    }

    private record ReviewResult(int score, boolean pass, String summary, String notes, JsonNode needsInput) {}

    private ReviewResult parseReview(String raw) {
        String text = nz(raw);
        try {
            JsonNode n = mapper.readTree(extractJson(text));
            int score = Math.max(0, Math.min(100, n.path("score").asInt(0)));
            boolean pass = n.path("pass").asBoolean(false);
            String summary = n.path("summary").asText("");
            StringBuilder notes = new StringBuilder(n.path("notes").asText(""));
            JsonNode must = n.path("mustFix");
            if (must.isArray()) {
                for (JsonNode x : must) {
                    if (!x.asText("").isBlank()) notes.append("\n- ").append(x.asText());
                }
            }
            return new ReviewResult(score, pass, summary, notes.toString(), n.path("needsInput"));
        } catch (Exception e) {
            int score = 0;
            boolean pass = false;
            Matcher sm = Pattern.compile("\"?score\"?\\s*[:：]\\s*(\\d{1,3})").matcher(text);
            if (sm.find()) score = Math.max(0, Math.min(100, Integer.parseInt(sm.group(1))));
            Matcher pm = Pattern.compile("\"?pass\"?\\s*[:：]\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (pm.find()) pass = Boolean.parseBoolean(pm.group(1).toLowerCase(Locale.ROOT));
            return new ReviewResult(score, pass, "评审输出未按 JSON 返回", clip(text, 3000), null);
        }
    }

    private static String extractJson(String s) {
        if (s == null) return "{}";
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        return (l >= 0 && r > l) ? s.substring(l, r + 1) : "{}";
    }

    public static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception ignored) {}
        try {
            return LocalDate.parse(s.trim().substring(0, Math.min(10, s.trim().length())))
                    .atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
