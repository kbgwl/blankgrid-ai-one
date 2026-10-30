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

    /**
     * 结构化调用的输出上限。中文长清单(4-5 条工作流 + 若干条待补事项)很容易越过 4K,
     * 服务商默认值就在那儿,越过去的表现不是报错,而是"输出不像 JSON"—— 其实是被截断了。
     */
    private static final int PLAN_MAX_TOKENS = 8000;

    @org.springframework.beans.factory.annotation.Value("${aione.data-dir:./data}")
    private String dataDir;

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
    private final LearningService learning;

    private final Set<Long> planningGoals = ConcurrentHashMap.newKeySet();
    private final Set<Long> planningWorkstreams = ConcurrentHashMap.newKeySet();

    /**
     * 大模型账户不可用(余额不足 / 配额用尽)时的闸门。
     * 继续调度只会把每个交付物挨个烧成 FAILED,页面上看着像"全线崩了",
     * 其实只有一个原因:该充值了。停 10 分钟,期间不再发新活,也不再刷失败记录。
     */
    private static final long LLM_BLOCK_COOLDOWN_MS = 10 * 60 * 1000L;
    private volatile long llmBlockedUntil = 0;
    private volatile String llmBlockedReason = "";

    public boolean llmBlocked() {
        return System.currentTimeMillis() < llmBlockedUntil;
    }

    public String llmBlockedReason() {
        return llmBlocked() ? llmBlockedReason : "";
    }

    /** 设置页测试连通成功后调一次:充值完不必等冷却,也不用重启。 */
    public void clearLlmBlock() {
        llmBlockedUntil = 0;
        llmBlockedReason = "";
    }

    /** 从错误信息里认出"不是活干错了,是账户里没钱了",并据此拉起闸门。 */
    private void noteLlmFailure(String msg) {
        if (msg == null || msg.isBlank()) return;
        String m = msg.toLowerCase(Locale.ROOT);
        boolean blocked = m.contains("insufficient balance") || m.contains("402")
                || m.contains("余额") || m.contains("quota") || m.contains("payment required");
        if (!blocked) return;
        boolean first = !llmBlocked();
        llmBlockedReason = clip(msg, 200);
        llmBlockedUntil = System.currentTimeMillis() + LLM_BLOCK_COOLDOWN_MS;
        if (first) {
            log.warn("大模型账户不可用,暂停调度:{}", llmBlockedReason);
            activity(Activity.ESCALATION, "大模型账户余额不足,生产线已暂停调度 —— 重试不会成功,也没必要把每个"
                    + "交付物都刷成失败。充值后在「设置 → 模型服务」点一次测试连通,10 分钟内自动恢复。", null);
            pusher.push("llm-blocked", Map.of("reason", llmBlockedReason));
        }
    }

    /**
     * 服务商侧的失败,不是"活干砸了":余额不足、限流、网关超时、空响应。
     * 拿它去扣重试次数,一次欠费就能把整条线永久钉死在 BLOCKED,
     * 之后即使充了值,也得人工一条条点重试才能动 —— 所以这类失败只排队,不判死。
     */
    static boolean providerError(String err) {
        if (err == null || err.isBlank()) return false;
        String e = err.toLowerCase(java.util.Locale.ROOT);
        return e.contains("api 返回") || e.contains("api 错误") || e.contains("402") || e.contains("429")
                || e.contains("insufficient") || e.contains("quota")
                || e.contains("模型未返回内容") || e.contains("连接大模型") || e.contains("请求被中断")
                || e.contains("timeout") || e.contains("timed out") || e.contains("connection");
    }

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
                                EventPusher pusher, ObjectMapper mapper,
                                LearningService learning) {
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
        this.learning = learning;
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

    /**
     * 进程重启后,上一次运行里"正在执行"的任务其实已经没人管了:它们不会自己变 DONE,
     * 交付物就会永远停在 RUNNING 还占着并发名额,整条线看起来"卡住了"却没有报错。
     * 所以启动时先把这些孤儿任务标成中断,把对应交付物放回队列 ——
     * 不消耗重试次数(不是它的错),评审阶段缺失的由 recoverReviewStages 补建。
     */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        try {
            List<Task> orphans = taskRepo.findByStatus(Task.RUNNING);
            for (Task t : orphans) {
                t.setStatus(Task.FAILED);
                t.setError("进程重启,本次执行中断,已自动重新排队");
                t.setFinishedAt(Instant.now());
                taskRepo.save(t);
                if (t.getDeliverableId() == null) continue;
                Deliverable d = delRepo.findById(t.getDeliverableId()).orElse(null);
                if (d == null || d.getCurrentTaskId() == null || !d.getCurrentTaskId().equals(t.getId())) continue;
                d.setCurrentTaskId(null);
                if (Deliverable.RUNNING.equals(d.getStatus())) d.setStatus(Deliverable.READY);
                d.setUpdatedAt(Instant.now());
                delRepo.save(d);
            }
            int idle = 0;
            for (Employee e : empRepo.findAll()) {
                if (Employee.STATUS_RUNNING.equals(e.getStatus())) {
                    e.setStatus(Employee.STATUS_IDLE);
                    e.setCurrentTaskSummary("");
                    empRepo.save(e);
                    idle++;
                }
            }
            if (!orphans.isEmpty() || idle > 0) {
                log.warn("启动清理:{} 个中断任务重新排队,{} 个岗位恢复在岗", orphans.size(), idle);
            }
        } catch (Exception e) {
            log.warn("启动清理失败: {}", e.getMessage());
        }
    }

    // ==================== 定时推进 ====================

    /** 每 10 秒推进一次:补拆解、按依赖启动交付、检查目标完成。 */
    @Scheduled(fixedDelay = 10000)
    public void tick() {
        if (!settings.hasProvider() || !budgetAvailable()) return;
        if (llmBlocked()) return;   // 账户欠费时停手,别把失败记录刷满
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
            log.warn("目标引擎 tick 异常: {}", e.getMessage(), e);
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

        // 目标拆解同样是 CEO 的决策,和巡视走同一个决策模型槽位。
        // 这一调用必须吐结构化数据:服务端强制 JSON 输出,并把输出上限调高 ——
        // 默认 4K 的上限装不下"4 条工作流 + 3 条待补事项"的中文描述,写到一半就断,
        // 断在数组里就是"不是有效 JSON",整条生产线卡在第一步。
        String planModel = settings.decisionModel(ceo.getModel());
        // 拆解是决策,不是执行:同样豁免省钱护栏,否则配的强模型在拆解这一步被换成便宜档,
        // 拆出来的工作流质量决定后面所有岗位的产出,省这一步的钱最不划算。
        String reply = settings.decisionStrong()
                ? llm.chatStreamStrong(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)),
                        planModel, null, settings.decisionProvider(), true, PLAN_MAX_TOKENS)
                : llm.chatStream(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)),
                        planModel, null, settings.decisionProvider(), true, PLAN_MAX_TOKENS);
        // 记账用服务商回传的真实用量 + 真实生效的模型名(省钱模式可能把 pro 换成 flash),
        // 否则成本表会把便宜模型的量算成贵模型的钱,越看越吓人。
        tokenRepo.save(new TokenLog(null, goalId, "CEO_PLAN",
                llm.tokensUsed(CeoLoopService.estimateTokens(sys.length() + user.length() + reply.length())),
                llm.lastModel()));

        boolean truncated = llm.lastTruncated() || LooseJson.looksTruncated(reply);
        JsonNode root = LooseJson.parse(mapper, reply);
        if (root == null) {
            log.warn("CEO 拆解 JSON 解析失败(目标 {},截断={},{} 字),让模型自修一次", goalId, truncated, reply.length());
            dumpRaw("ceo-plan", goalId, reply);
            String fixed = repairJson(reply, planModel, goalId,
                    "summary(字符串)、needsInput(字符串数组)、workstreams(数组,每项含 departmentId/leadId/title/brief/acceptanceCriteria/dueAt)");
            root = LooseJson.parse(mapper, fixed);
            if (root != null) truncated = false;
        }
        if (root == null) {
            throw new IllegalStateException("CEO 拆解结果不是有效 JSON"
                    + (truncated ? "(模型输出被截断,已自动重试修复仍失败)" : "")
                    + ": " + clip(reply, 300));
        }
        String summary = root.path("summary").asText("CEO 已完成拆解");
        JsonNode arr = root.path("workstreams");
        if (!arr.isArray() || arr.isEmpty()) {
            // 工作流没拆出来,但待补事项是有效的:先记下来,创始人补完料再点「重新拆解」,
            // 总比只留一句"拆解失败"、不知道缺什么强。
            recordNeedsInput(g, root.path("needsInput"), "CEO");
            throw new IllegalStateException("CEO 没有拆出任何部门工作流"
                    + (truncated ? "(模型输出被截断,建议把目标写短一些)" : "") + ",请检查目标描述或模型输出");
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
        if (truncated) {
            activity(Activity.REPORT, "提醒:CEO 的输出撞到了输出上限被截断,已按写完的部分拆出 "
                    + created.size() + " 条工作流。目标描述写短一点、少列几件要核实的资料,拆解会更完整。", goalId);
        }
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
        sb.append("6.1 一件产出只能算一项:「品牌视觉方案」就不要再单列「字体层级规格」「色板与间距规范」;"
                + "「产品方案」就不要再单列「token 用量核算表」「指标口径字典」。"
                + "这类附属内容并进成品章节,单独立项只会多派一次活、多评委一次,预算烧在同一件事上。\n");
        sb.append("7. 验收标准必须能被机械核对,但不许写无法机械判定的绝对化限定:"
                + "不要写“全部中文”“不含任何英文”“文字必须完美”这类;"
                + "确实需要限定语种或形式时,写明例外(如“中文表述,行业通用缩写 AI/API 等与专有名词除外”)。\n");
        sb.append("\n只输出一个 JSON 对象,不要 Markdown 代码块,不要任何解释:\n");
        sb.append("{\"summary\":\"你的拆解思路(一两句话)\",");
        sb.append("\"needsInput\":[\"待创始人核实的事项 —— 为什么必须要,没有它会怎样\"],\"workstreams\":[");
        sb.append("{\"departmentId\":1,\"leadId\":12,\"title\":\"工作流标题\",\"brief\":\"要产出的内容与假设\",");
        sb.append("\"acceptanceCriteria\":\"逐条可检查的验收标准\",\"dueAt\":\"2026-11-01T00:00:00Z 或空\"}]}");
        sb.append("\n篇幅硬约束:整段 JSON 不超过 1200 字 —— brief 每条 ≤120 字,acceptanceCriteria ≤200 字,"
                + "needsInput 最多 3 条、每条 ≤80 字。写不完就压缩措辞,但**不许省略 JSON 结构**"
                + "(少一个括号,下游解析就整段失败,等于白拆)。\n");
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

        String leadModel = (lead.getModel() != null && !lead.getModel().isBlank()) ? lead.getModel() : settings.defaultModel();
        // 和 CEO 拆解同一套要求:强制 JSON + 放宽输出上限,不靠模型自觉
        String reply = llm.chatStream(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)),
                lead.getModel(), null, null, true, PLAN_MAX_TOKENS);
        tokenRepo.save(new TokenLog(null, g.getId(), "LEAD_PLAN",
                llm.tokensUsed(CeoLoopService.estimateTokens(sys.length() + user.length() + reply.length())),
                llm.lastModel()));

        boolean truncated = llm.lastTruncated() || LooseJson.looksTruncated(reply);
        JsonNode root = LooseJson.parse(mapper, reply);
        if (root == null) {
            log.warn("负责人拆解 JSON 解析失败(工作流 {},截断={},{} 字),让模型自修一次", wsId, truncated, reply.length());
            dumpRaw("lead-plan", ws.getId(), reply);
            String fixed = repairJson(reply, leadModel, g.getId(),
                    "summary(字符串)、needsInput(字符串数组)、deliverables(数组,每项含 title/brief/acceptanceCriteria/ownerId/dependsOn)");
            root = LooseJson.parse(mapper, fixed);
            if (root != null) truncated = false;
        }
        if (root == null) {
            throw new IllegalStateException("负责人拆解结果不是有效 JSON"
                    + (truncated ? "(模型输出被截断,已自动重试修复仍失败)" : "") + ": " + clip(reply, 300));
        }
        String summary = root.path("summary").asText("负责人已完成拆解");
        JsonNode arr = root.path("deliverables");
        List<Deliverable> created = new ArrayList<>();
        // 本次拆解下标 → 真实交付物 id。重复项被合并时,下标指向被合并到的那一项,依赖才不会断。
        Map<Integer, Long> indexToId = new LinkedHashMap<>();
        Map<Long, Integer> pendingDep = new LinkedHashMap<>();
        List<String> merged = new ArrayList<>();
        // 本工作流内已立项的 [id, 标题, 责任人id]:附属物的宿主只能从这里选,
        // 跨工作流合并会把设计的东西塞进文案里,那是另一种错。
        List<String[]> local = new ArrayList<>();
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

                    // 粒度闸门:规格/台账/清单这类附属物不再单独占用一条生产线,
                    // 并进同一责任人的成品里当章节或附表。合并是显式记账的,内容不会凭空消失。
                    if (DeliverableGuard.isAttachment(title)) {
                        String[] host = DeliverableGuard.host(title, local, owner == null ? null : owner.getId());
                        if (host != null) {
                            Long hostId = Long.parseLong(host[0]);
                            Deliverable hd = delRepo.findById(hostId).orElse(null);
                            if (hd != null) {
                                hd.setBrief(clip(nz(hd.getBrief())
                                        + DeliverableGuard.mergeNote(title, node.path("brief").asText("")), 8000));
                                hd.setUpdatedAt(Instant.now());
                                delRepo.save(hd);
                                indexToId.put(myIndex, hostId);
                                merged.add("「" + title + "」是附属物,并进「" + host[1] + "」");
                                continue;
                            }
                        }
                    }

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
                    existing.add(new String[]{String.valueOf(saved.getId()), title,
                            owner == null ? "" : String.valueOf(owner.getId())});
                    local.add(new String[]{String.valueOf(saved.getId()), title,
                            owner == null ? "" : String.valueOf(owner.getId())});
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
                    + " 项被合并(重复开工或只是附属台账),不再单独占一条生产线:" + clip(String.join(";", merged), 600), g.getId());
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

    /** 同一目标下已有交付物的 [id, 标题, 责任人id],供跨部门查重。 */
    private List<String[]> goalDeliverableTitles(Long goalId) {
        List<String[]> out = new ArrayList<>();
        for (Deliverable d : delRepo.findByGoalIdOrderBySortOrderAscIdAsc(goalId)) {
            out.add(new String[]{String.valueOf(d.getId()), d.getTitle() == null ? "" : d.getTitle(),
                    d.getOwner() == null ? "" : String.valueOf(d.getOwner().getId())});
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
        sb.append("7.1 也不要把一件产出切成几片来凑数:“字体层级规格”“色板与间距规范”“图标命名清单”"
                + "“token 用量核算表”“指标口径字典”这类附属台账,必须并进对应的成品里当章节或附表,"
                + "不能单独立一项。判断标准:去掉它,成品还能不能直接用?能,它就是附属物。"
                + "交付物数量不必凑满,两项扎实的成品好过六项切片。\n");
        sb.append("8. 缺信息分两种处理:文风、篇幅、结构这类可以假设,写进 brief;"
                + "产品功能名、真实数据、价格、资质这类必须核实的事实,写进 needsInput,不要编造。\n");
        sb.append("9. 验收标准要可机械核对,但禁止无法机械判定的绝对化限定:不写“全部中文”“不含英文”“完美”这类;"
                + "需要限定语种时必须带例外(如“中文表述,AI/API 等行业缩写与专有名词除外”)。\n");
        sb.append("\n只输出一个 JSON 对象,不要 Markdown 代码块,不要解释:\n");
        sb.append("{\"summary\":\"拆活思路\",\"needsInput\":[\"待创始人核实的事项 —— 为什么必须要\"],\"deliverables\":[");
        sb.append("{\"ownerId\":21,\"title\":\"交付物标题\",\"brief\":\"具体要做什么\",");
        sb.append("\"acceptanceCriteria\":\"逐条验收标准\",\"outputType\":\"文档\",");
        sb.append("\"dependsOn\":[],\"priority\":\"HIGH\",\"dueAt\":\"2026-11-01T00:00:00Z 或空\"}]}");
        sb.append("\n篇幅硬约束:整段 JSON 不超过 1500 字 —— 每项 brief ≤100 字,acceptanceCriteria ≤200 字,"
                + "needsInput 最多 3 条。写不完就压缩措辞,但**不许省略 JSON 结构**。\n");
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
            // 只给最后一版意见是不够的:第三次返工时,第一版指出的结构问题往往还没解决,
            // 但 lastReviewNotes 已经被覆盖成第二轮的意见了 —— 模型于是只改最后一处,
            // 修完还是同一批问题。把历次版本的意见按版本摊开,它才知道哪些是欠账。
            List<Artifact> history = artRepo.findByDeliverableIdOrderByVersionDesc(d.getId());
            int shown = 0;
            for (Artifact a : history) {
                if (shown >= 2) break;
                if (a.getReviewNotes() == null || a.getReviewNotes().isBlank()) continue;
                sb.append("【v").append(a.getVersion()).append(" 评审意见")
                  .append(a.getScore() > 0 ? "(" + a.getScore() + " 分)" : "").append("】\n")
                  .append(clip(a.getReviewNotes(), 1200)).append("\n");
                shown++;
            }
            if (shown == 0 && d.getReviewNotes() != null && !d.getReviewNotes().isBlank()) {
                sb.append("上次评审意见:\n").append(clip(d.getReviewNotes(), 2500)).append("\n");
            }
            if (shown > 1) {
                sb.append("以上是两轮的意见。**先把上一轮还挂着的问题全部解决**,再谈新增内容 —— ");
                sb.append("同一处被指出两次,说明上次只做了表面修改。\n");
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
            // 返工之前,先把「这次为什么没通过」写进执行人的成长档案 ——
            // 下一轮它会以「你自己的经验教训」出现在系统提示里,比只在这一次的任务说明里
            // 提一句有效得多:同一岗位下次接同类活,不用再被同一个问题打回一遍。
            Employee owner = d.getOwner();
            if (owner != null) {
                learning.add(owner, "评审", firstIssues(r.notes));
            }
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
        noteLlmFailure(error);
        d.setCurrentTaskId(null);
        d.setReviewNotes(clip("上次执行失败:" + nz(error), 2000));
        d.setUpdatedAt(Instant.now());
        if (providerError(error)) {
            d.setAttempts(Math.max(0, d.getAttempts() - 1));   // 不是它的错,不扣次数
            d.setStatus(Deliverable.READY);
            delRepo.save(d);
            log.warn("交付物 {} 因模型服务侧原因中断,保持待执行: {}", d.getId(), clip(nz(error), 120));
            pushGoal(d.getGoalId());
            return;
        }
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
        noteLlmFailure(error);
        d.setCurrentTaskId(null);
        if (providerError(error)) {
            // 评审没跑成,不是成果不行:留在 REVIEW,等账户恢复后由 recoverReviewStages 补建评审任务
            d.setReviewNotes(clip("评审中断(模型服务侧):" + nz(error), 2000));
            d.setUpdatedAt(Instant.now());
            delRepo.save(d);
            log.warn("交付物 {} 评审因模型服务侧原因中断,稍后重试: {}", d.getId(), clip(nz(error), 120));
            return;
        }
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
        noteLlmFailure(message);
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
        List<String> before = parseNeedsInput(fresh.getNeedsInput());
        List<String> items = new ArrayList<>(compactTopics(before));
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
            // 同一类事实只问一次:CEO 和三个部门负责人各问一遍"你的产品是什么",
            // 创始人看到的是四条重复的待补,反而不知道该填哪条。按主题收敛,保留信息更全的那条。
            String topic = factTopic(item);
            if (topic != null) {
                int at = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (topic.equals(factTopic(items.get(i)))) { at = i; break; }
                }
                if (at >= 0) {
                    if (item.length() > items.get(at).length()) items.set(at, item);
                    continue;
                }
            }
            items.add(item);
            added.add(item);
        }
        boolean shrunk = items.size() < before.size();
        if (added.isEmpty() && !shrunk) return;
        while (items.size() > 12) items.remove(0);
        fresh.setNeedsInput(writeNeedsInput(items));
        fresh.setUpdatedAt(Instant.now());
        goalRepo.save(fresh);
        if (added.isEmpty()) return;   // 只是把重复项收敛了,不用再喊一遍
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

    /**
     * 待补事项的主题分类。同一类事实只该问一次。
     * 顺序即优先级:越具体的放越前 —— 「表单接收邮箱」属于表单服务,不是联系方式,
     * 否则两件必须分别确认的事会被合并掉,反而漏料。没命中任何主题就返回 null(不做合并)。
     */
    private static final String[][] FACT_TOPICS = {
            {"表单服务", "表单", "formspree", "emailjs", "接收邮箱"},
            {"备案", "备案", "icp"},
            {"域名与托管", "域名", "托管", "vercel", "netlify", "服务器", "部署账号"},
            {"客户案例", "客户", "案例", "业绩", "项目数量", "营收"},
            {"产品事实", "产品", "服务名称", "功能清单", "能力说明", "交付形态", "业务事实"},
            {"品牌资产", "品牌名", "商标", "logo", "字体授权", "视觉规范"},
            {"联系方式", "联系方式", "邮箱", "电话", "地址", "微信", "公众号"},
            {"价格口径", "价格", "报价", "套餐", "收费"},
            {"资质", "资质", "认证", "许可证"}
    };

    static String factTopic(String item) {
        if (item == null) return null;
        String s = item.toLowerCase(java.util.Locale.ROOT);
        for (String[] row : FACT_TOPICS) {
            for (int i = 1; i < row.length; i++) {
                if (s.contains(row[i])) return row[0];
            }
        }
        return null;
    }

    /** 按主题收敛一遍:同类只留信息最全的那条,原有清单也顺带清干净。 */
    static List<String> compactTopics(List<String> in) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> at = new HashMap<>();
        for (String s : in) {
            String t = factTopic(s);
            if (t == null) {
                out.add(s);
                continue;
            }
            Integer i = at.get(t);
            if (i == null) {
                at.put(t, out.size());
                out.add(s);
            } else if (s.length() > out.get(i).length()) {
                out.set(i, s);
            }
        }
        return out;
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

    /**
     * 兜底修复:把坏掉的输出原样丢回给模型,让它自己改成严格 JSON。
     * 比让创始人在页面上重下一次目标便宜得多,也比在本地写一个"什么坏法都能修"的解析器现实。
     * 失败就返回空串,由调用方给出可读的错误。
     */
    private String repairJson(String broken, String model, Long goalId, String schema) {
        String sys = "你是 JSON 修复器。只输出一个严格合法的 JSON 对象:不要 Markdown 代码块,不要解释,不要客套话。";
        String user = "下面这段内容本应是一个 JSON 对象,但格式坏了(可能被截断、混进了中文标点、缺右括号)。\n"
                + "请在保持原意的前提下补全并改正它:\n"
                + "1. 只输出修复后的 JSON,以 { 开头、以 } 结尾;\n"
                + "2. 键名与字符串值一律用英文半角双引号,键名不许改;\n"
                + "3. 被截断的部分按原意补完,但每条描述压到 80 字以内,确保一次能写完;\n"
                + "4. 必须有的字段:" + schema + "\n\n原内容:\n" + clip(broken, 12000);
        try {
            String fixed = settings.decisionStrong()
                    ? llm.chatStreamStrong(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)),
                            model, null, settings.decisionProvider(), true, PLAN_MAX_TOKENS)
                    : llm.chatStream(List.of(new ChatMessage("system", sys), new ChatMessage("user", user)),
                            model, null, settings.decisionProvider(), true, PLAN_MAX_TOKENS);
            tokenRepo.save(new TokenLog(null, goalId, "JSON_REPAIR",
                    llm.tokensUsed(CeoLoopService.estimateTokens(sys.length() + user.length() + fixed.length())),
                    llm.lastModel()));
            return fixed;
        } catch (Exception e) {
            log.warn("JSON 自动修复调用失败: {}", e.getMessage());
            return "";
        }
    }

    /** 解析失败的原始输出留一份在盘上,事后能看清楚到底坏在哪,而不是只凭日志里的 300 字猜。 */
    private void dumpRaw(String kind, Long id, String raw) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(dataDir, "diagnose");
            java.nio.file.Files.createDirectories(dir);
            String name = kind + "-" + id + "-" + Instant.now().toEpochMilli() + ".txt";
            java.nio.file.Files.writeString(dir.resolve(name), raw == null ? "" : raw,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 诊断信息写不下去不影响主流程
        }
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

    /**
     * 从评审意见里挑出最该被记住的一条。
     *
     * <p>评审的 notes 通常是一串编号条目,第一条往往就是最要命的那个问题。
     * 整段塞进成长档案没有意义(档案只有几百字额度),所以只取第一条实质内容。
     *
     * <p>要跳过三类"看着像内容其实不是"的行 —— 实测评审第一条就是
     * 「【必须修改 · 逐条】」这种小标题,直接取会把标题记成教训:
     * 纯方括号小标题、纯分隔线、以冒号收尾的引导语。
     */
    static String firstIssues(String notes) {
        if (notes == null || notes.isBlank()) return "";
        for (String line : notes.split("\n")) {
            String t = line.replaceAll("^[\\s\\-*·•]+", "")
                          .replaceAll("^[(（]?\\d{1,2}[.、)）]\\s*", "")
                          .replaceAll("^[【\\[][^】\\]]{0,16}[】\\]]\\s*", "")
                          .strip();
            if (t.length() < 6) continue;
            if (t.matches("[#=\\-*_\\s]{3,}")) continue;      // 分隔线
            if (t.endsWith("：") || t.endsWith(":")) continue; // 引导语,正文在下一行
            return t;
        }
        return notes.strip();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
