package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.Deliverable;
import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.entity.Workstream;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.repo.DeliverableRepository;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.GoalRepository;
import com.blankgrid.aione.repo.TaskRepository;
import com.blankgrid.aione.repo.TokenLogRepository;
import com.blankgrid.aione.repo.WorkstreamRepository;
import com.blankgrid.aione.service.LlmService.ChatMessage;
import com.blankgrid.aione.ws.EventPusher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CEO 自治循环 —— 产品的决策引擎。
 * CEO 是 AI:定期巡视公司(目标、纠偏、任务产出、各岗位状态与队列),
 * 自主评估并决策:派活 / 返工 / 汇报 / 归档目标 / 上报大事。
 * 创始人只提供 token(有预算)与方向纠偏。
 */
@Service
public class CeoLoopService {

    private static final Logger log = LoggerFactory.getLogger(CeoLoopService.class);

    /** 单次决策输出上限。决策 JSON 里有 summary + 若干条 decisions,4K 在长清单上会写到一半被截断。 */
    private static final int PLAN_TOKENS = 8000;

    private final EmployeeRepository empRepo;
    private final TaskRepository taskRepo;
    private final ActivityRepository activityRepo;
    private final TokenLogRepository tokenRepo;
    private final GoalRepository goalRepo;
    private final WorkstreamRepository wsRepo;
    private final DeliverableRepository delRepo;
    private final GoalService goalService;
    private final TaskService taskService;
    private final LlmService llm;
    private final SettingsService settings;
    private final com.blankgrid.aione.tool.ToolRegistry toolRegistry;
    private final EventPusher pusher;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong cycleCounter = new AtomicLong(0);
    private volatile Instant lastAutoCycle = null;
    private volatile Instant lastWeekly = null;
    /** 最后一次真正调了模型的巡视时间:用于空转静默,避免闲时也在烧 token */
    private volatile Instant lastRealCycle = null;

    public CeoLoopService(EmployeeRepository empRepo, TaskRepository taskRepo,
                          ActivityRepository activityRepo, TokenLogRepository tokenRepo,
                          GoalRepository goalRepo, WorkstreamRepository wsRepo,
                          DeliverableRepository delRepo, GoalService goalService,
                          TaskService taskService, LlmService llm,
                          SettingsService settings, EventPusher pusher,
                          com.blankgrid.aione.tool.ToolRegistry toolRegistry) {
        this.empRepo = empRepo;
        this.taskRepo = taskRepo;
        this.activityRepo = activityRepo;
        this.tokenRepo = tokenRepo;
        this.goalRepo = goalRepo;
        this.wsRepo = wsRepo;
        this.delRepo = delRepo;
        this.goalService = goalService;
        this.taskService = taskService;
        this.llm = llm;
        this.settings = settings;
        this.pusher = pusher;
        this.toolRegistry = toolRegistry;
    }

    /** 心跳:按设置里的频率触发自动巡视(间隔可随时在设置页调整) */
    @Scheduled(fixedDelay = 60000)
    public void heartbeat() {
        if (running.get()) return;
        // 没有大模型就什么都不做,避免每分钟刷一条失败汇报
        if (!settings.hasProvider()) return;
        int minutes = Math.max(1, settings.get().ceoIntervalMinutes);
        if (lastAutoCycle != null && Instant.now().isBefore(lastAutoCycle.plus(minutes, ChronoUnit.MINUTES))) {
            return;
        }
        lastAutoCycle = Instant.now();
        // token 预算:到顶自动暂停巡视(手动巡视不受限,创始人自己的决定照常执行)
        long budget = settings.get().dailyTokenBudget;
        if (budget > 0) {
            long used = tokenRepo.tokensSince(Instant.now().truncatedTo(ChronoUnit.DAYS));
            if (used >= budget) {
                saveReportAggregated("今日 token 预算已用尽(" + used + "/" + budget + "),自动巡视已暂停。提高预算或等明日重置。",
                        null, Duration.ofHours(4));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("used", used);
                p.put("budget", budget);
                pusher.push("budget-exceeded", p);
                return;
            }
        }
        // 每 7 天自动生成一份 CEO 周报
        boolean weekly = lastWeekly == null || Instant.now().isAfter(lastWeekly.plus(7, ChronoUnit.DAYS));
        // 空转静默:公司没活了(无活跃目标、无待办交付物、无待响应指令)就别让 CEO 每半小时
        // 对着空办公室烧一次完整上下文。真闲了 4 小时才做一次例行评估,周报照走。
        if (!weekly && !hasLiveWork()
                && lastRealCycle != null && Instant.now().isBefore(lastRealCycle.plus(4, ChronoUnit.HOURS))) {
            return;
        }
        if (weekly) lastWeekly = Instant.now();
        runCycle(weekly);
        lastRealCycle = Instant.now();
    }

    /**
     * 公司现在有没有"活"要 CEO 管:创始人有未响应指令、目标在跑、交付物待办/受阻、任务在队列里。
     * 全都没有 = CEO 这轮看了也只能说"暂无进展",这笔钱不值得花。
     */
    private boolean hasLiveWork() {
        List<Activity> opens = activityRepo.findTop20ByTypeInAndStatusOrderByCreatedAtAsc(
                List.of(Activity.GOAL, Activity.FEEDBACK), Activity.OPEN);
        if (!opens.isEmpty()) return true;
        for (Goal g : goalRepo.findAllByOrderByCreatedAtDesc()) {
            if (Goal.ACTIVE.equals(g.getStatus()) || Goal.PLANNING.equals(g.getStatus())) return true;
        }
        if (!delRepo.findByStatusIn(List.of(Deliverable.PENDING, Deliverable.READY, Deliverable.RUNNING,
                Deliverable.WAITING, Deliverable.BLOCKED)).isEmpty()) return true;
        int n = 0;
        for (Task t : taskRepo.findTop200ByOrderByCreatedAtDesc()) {
            if (n++ >= 40) break;
            if (Task.PENDING.equals(t.getStatus()) || Task.RUNNING.equals(t.getStatus())) return true;
        }
        return false;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long cycleCount() {
        return cycleCounter.get();
    }

    /** 执行一轮 CEO 决策循环。返回本轮摘要。weekly=true 时额外附上周报任务。 */
    public Map<String, Object> runCycle() {
        return runCycle(false);
    }

    public Map<String, Object> runCycle(boolean weekly) {
        if (!running.compareAndSet(false, true)) {
            return Map.of("ok", false, "message", "CEO 正在巡视中");
        }
        long cycleNo = cycleCounter.incrementAndGet();
        pushCeoStatus("RUNNING");
        try {
            String reply = askCeo(weekly);
            String summary = executeDecisions(reply, cycleNo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("cycleNo", cycleNo);
            out.put("summary", summary);
            return out;
        } catch (Exception e) {
            log.warn("CEO 周期失败: {}", e.getMessage());
            saveReportAggregated("本轮巡视失败:" + e.getMessage(), cycleNo, Duration.ofHours(1));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("cycleNo", cycleNo);
            out.put("summary", e.getMessage());
            return out;
        } finally {
            running.set(false);
            pushCeoStatus("IDLE");
        }
    }

    /** 组装公司上下文,让 CEO 决策。weekly=true 时附带 7 天汇报摘要,要求输出周报。 */
    private String askCeo(boolean weekly) {
        Employee ceo = empRepo.findByCeoTrue().stream().findFirst().orElse(null);
        String sys = (ceo != null && ceo.getSystemPrompt() != null && !ceo.getSystemPrompt().isBlank())
                ? ceo.getSystemPrompt()
                : "你是空白格的 CEO。";

        StringBuilder sb = new StringBuilder();
        sb.append("你是「无锡空白格人工智能科技有限公司」的 CEO(AI)。创始人不参与日常决策,只提供 token 与方向纠偏;公司整个运营规划、派活、赚钱由你全权负责。\n");
        sb.append("你的职权:评估公司现状、给各岗位派活、要求返工、归档已完成目标、总结汇报。禁止越权:大额支出、对外合同承诺、重大方向调整——这些必须 escalate 上报创始人。\n\n");

        // 公司目标与纠偏(OPEN 的,带 id 可归档)
        List<Activity> opens = activityRepo.findTop20ByTypeInAndStatusOrderByCreatedAtAsc(
                List.of(Activity.GOAL, Activity.FEEDBACK), Activity.OPEN);
        sb.append("【创始人的目标与纠偏(最高优先级,必须响应;完成的用 goal_done 归档)】\n");
        if (opens.isEmpty()) {
            sb.append("(暂无。可自行规划短期目标并推进)\n");
        } else {
            for (Activity a : opens) sb.append("- id=").append(a.getId()).append(" [").append(a.getType()).append("] ").append(clip(a.getContent(), 120)).append("\n");
        }

        // 公司目标体系(目标引擎自动拆解执行,CEO 只需监督与拍板)
        List<Goal> goals = goalRepo.findAllByOrderByCreatedAtDesc();
        sb.append("\n【公司目标体系(由目标引擎自动拆解、派活、评审;你负责监督与解阻)】\n");
        if (goals.isEmpty()) {
            sb.append("(暂无公司级目标。等待创始人下达,或用 report 提出建议。)\n");
        } else {
            for (Goal g : goals) {
                long blocked = 0;
                for (Workstream ws : wsRepo.findByGoalIdOrderBySortOrderAscIdAsc(g.getId())) {
                    blocked += delRepo.countByWorkstreamIdAndStatus(ws.getId(), Deliverable.BLOCKED);
                }
                sb.append("- goalId=").append(g.getId()).append(" 「").append(g.getTitle()).append("」 状态:")
                  .append(g.getStatus()).append(" 进度:").append(g.getProgress()).append("% 均分:")
                  .append(g.getFinalScore()).append(" 受阻:").append(blocked)
                  .append(" 说明:").append(clip(g.getCeoSummary(), 120)).append("\n");
            }
        }

        // 岗位花名册 + 队列深度(队列满勿派活)
        sb.append("\n【在编岗位(employeeId 用于派活;队列=该岗位待处理+执行中,≥2 勿再派)】\n");
        for (Employee e : empRepo.findAll()) {
            if (e.isCeo()) continue;
            if (Employee.STATUS_DISABLED.equals(e.getStatus())) {
                sb.append("- id=").append(e.getId()).append(" ").append(e.getName())
                  .append("(已停用,禁止派活)\n");
                continue;
            }
            long pending = taskRepo.countByEmployeeIdAndStatus(e.getId(), Task.PENDING);
            long runningN = taskRepo.countByEmployeeIdAndStatus(e.getId(), Task.RUNNING);
            sb.append("- id=").append(e.getId()).append(" ").append(e.getName())
              .append("(").append(e.getTitle() == null ? "" : e.getTitle()).append(") 状态:").append(e.getStatus())
              .append(" 层级:").append(e.getLevel())
              .append(" 队列:").append(pending + runningN).append("\n");
        }

        // 近期任务与产出(每个岗位最近一条 DONE 给长产出,便于 CEO 审阅)
        // 从 30 条砍到 12 条:CEO 拍板看的是"哪里卡住了",不是把每个产出通读一遍。
        // 30 条 × 每条最多 600 字,单次巡视光这一块就近万 token,一天几十轮就是纯烧钱。
        sb.append("\n【近期任务(最多 12 条)】\n");
        List<Task> recent = taskRepo.findTop200ByOrderByCreatedAtDesc();
        int n = 0;
        Map<Long, Boolean> longExcerptGiven = new LinkedHashMap<>();
        for (Task t : recent) {
            if (n++ >= 12) break;
            sb.append("- #").append(t.getId()).append(" ").append(t.getEmployee().getName())
              .append(" [").append(t.getStatus()).append("] ").append(clip(t.getInstruction(), 60));
            if (Task.DONE.equals(t.getStatus()) && t.getResult() != null && !t.getResult().isBlank()) {
                Long empId = t.getEmployee().getId();
                boolean longView = longExcerptGiven.putIfAbsent(empId, true) == null;
                sb.append(" ⇒ 产出:").append(clip(t.getResult(), longView ? 240 : 80));
            }
            if (Task.FAILED.equals(t.getStatus()) && t.getError() != null) {
                sb.append(" ⇒ 失败原因:").append(clip(t.getError(), 200));
            }
            sb.append("\n");
        }
        if (n == 0) sb.append("(还没有任何任务)\n");

        String kb = settings.get().knowledgeBase;
        if (kb != null && !kb.isBlank()) {
            sb.append("\n【公司知识库(含历史经验教训)】\n").append(clip(kb, 900)).append("\n");
        }

        // 拍板前先取证。下面这份上下文是"快照",快照会过期、会缺细节(比如现价、成本、
        // 某个竞品到底怎么实现)。让 CEO 自己把不确定的部分查清楚再决策,
        // 比让它对着一段可能过期的摘要凭印象拍板强 —— 这也正是"自主决策"和"按剧本执行"的区别。
        String evidence = investigate(sys, sb.toString());
        if (!evidence.isBlank()) {
            sb.append("\n【你本轮自己查到的证据(工具调用结果,优先于上面的摘要)】\n").append(evidence).append("\n");
        }

        sb.append("\n【你的输出要求】只输出一个 JSON 对象,不要输出任何其他文字:\n");
        sb.append("{\"summary\":\"本轮评估(一两句话)\",\"decisions\":[");
        sb.append("{\"type\":\"dispatch\",\"employeeId\":岗位id,\"instruction\":\"任务指令\",\"contextTaskIds\":[可选,上游任务id数组]},");
        sb.append("{\"type\":\"rework\",\"taskId\":任务id,\"comment\":\"返工意见\"},");
        sb.append("{\"type\":\"goal_done\",\"goalId\":目标id,\"message\":\"完成情况\"},");
        sb.append("{\"type\":\"report\",\"message\":\"向创始人的运营汇报\"},");
        sb.append("{\"type\":\"escalate\",\"message\":\"必须由创始人拍板的事项\"},");
        sb.append("{\"type\":\"goal_start\",\"goalId\":目标id},");
        sb.append("{\"type\":\"retry_workstream\",\"workstreamId\":工作流id},");
        sb.append("{\"type\":\"goal_accept\",\"goalId\":目标id,\"message\":\"复核结论\"}");
        sb.append("]}");
        sb.append(" 派活要有节制:队列满勿派;无必要不派;评估优先。dispatch 可带 contextTaskIds 引用上游任务产出,让岗位基于已有成果工作。");

        if (weekly) {
            // 周报:过去 7 天的汇报摘要
            Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
            List<Activity> week = new ArrayList<>();
            for (Activity a : activityRepo.findTop100ByOrderByCreatedAtDescIdDesc()) {
                if (a.getCreatedAt() != null && a.getCreatedAt().isAfter(since)) week.add(a);
            }
            sb.append("\n\n【特别任务:本周周报】这是本周的运营动态(").append(week.size()).append(" 条):\n");
            int w = 0;
            for (Activity a : week) {
                if (w++ >= 40) break;
                sb.append("- [").append(a.getType()).append("] ").append(clip(a.getContent(), 100)).append("\n");
            }
            if (week.isEmpty()) sb.append("(本周暂无动态)\n");
            sb.append("请在 decisions 里额外加一条 {\"type\":\"report\",\"message\":\"【周报】…\"}:用三五段话向创始人汇报本周经营情况、目标进展、主要问题与下周计划。这是本周最重要的一条汇报。\n");
        }

        List<ChatMessage> messages = List.of(new ChatMessage("system", sys), new ChatMessage("user", sb.toString()));
        // 巡视拍板是公司的决策中枢:走「设置 → 决策模型」独立槽位,没单独配就跟随主用。
        // 决策链路豁免省钱护栏 —— 大脑被换成便宜档,输出照样是像模像样的 JSON,塌了看不出来。
        String cycleModel = settings.decisionModel(null);
        boolean strong = settings.decisionStrong();
        String reply = strong
                ? llm.chatStreamStrong(messages, cycleModel, null, settings.decisionProvider(), false, PLAN_TOKENS)
                : llm.chatStream(messages, cycleModel, null, settings.decisionProvider(), false, PLAN_TOKENS);
        tokenRepo.save(new com.blankgrid.aione.entity.TokenLog(null, (Long) null, "CEO",
                llm.tokensUsed(estimateTokens(sb.length() + sys.length() + reply.length())), llm.lastModel()));
        return reply;
    }

    /**
     * 取证阶段:CEO 在拍板前自己调工具查证据,最多 {@code ceoToolRounds} 轮。
     *
     * <p>只给 {@link com.blankgrid.aione.tool.ToolRegistry#decisionTools()} 里的工具 ——
     * 也就是"只读、查证"这一类。派活、返工、归档这些改状态的动作不允许在取证阶段发生,
     * 它们必须变成 decisions 里的一条,由系统执行并留审计。这条线一松,
     * 事后就分不清"CEO 查了什么"和"CEO 做了什么"。
     *
     * <p>任何一轮出错都只是少一份证据,不能连累整轮巡视 —— 拍板本身仍然要发生。
     */
    private String investigate(String sys, String context) {
        List<com.blankgrid.aione.tool.AiTool> usable = toolRegistry.decisionTools();
        int rounds = settings.ceoToolRounds();
        if (usable.isEmpty() || rounds <= 0) return "";

        List<LlmService.ToolSpec> specs = new ArrayList<>();
        for (com.blankgrid.aione.tool.AiTool t : usable) {
            specs.add(new LlmService.ToolSpec(t.name(), t.description(), t.paramsSchema()));
        }

        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", sys));
        msgs.add(new ChatMessage("user", context
                + "\n\n【拍板前先取证】你是 CEO,上面是公司现状的快照。快照会过期、会缺细节。"
                + "凡是影响本轮决策又拿不准的事实(现价、成本、某个软件/竞品到底怎么实现、某个数算得对不对),"
                + "先用工具查清楚再拍板。只查真正影响决策的,别为了查而查。"
                + "查够了就直接给结论,不要输出 JSON —— 下一步会单独要你的决策。\n"
                + "可用工具:" + String.join("、", specs.stream().map(LlmService.ToolSpec::name).toList())));

        StringBuilder ev = new StringBuilder();
        String model = settings.decisionModel(null);
        boolean strong = settings.decisionStrong();
        int used = 0;
        for (int i = 0; i < rounds; i++) {
            LlmService.Turn turn;
            try {
                turn = llm.chatWithTools(msgs, model, null, settings.decisionProvider(),
                        false, 3000, specs, strong);
            } catch (Exception e) {
                log.warn("CEO 取证阶段中断:{}", e.getMessage());
                break;
            }
            used += context.length() + (turn.content() == null ? 0 : turn.content().length());
            tokenRepo.save(new com.blankgrid.aione.entity.TokenLog(null, (Long) null, "CEO",
                    llm.tokensUsed(estimateTokens(context.length() + 1500)), llm.lastModel()));

            if (!turn.wantsTools()) {
                if (turn.content() != null && !turn.content().isBlank()) {
                    ev.append("【你的初步判断】").append(clip(turn.content(), 1500)).append("\n");
                }
                break;
            }
            msgs.add(new ChatMessage("assistant", turn.content(), turn.toolCalls(), null, null));
            for (LlmService.ToolCall tc : turn.toolCalls()) {
                String out = toolRegistry.invoke(tc.name(), tc.arguments(),
                        new com.blankgrid.aione.tool.ToolContext(null, null, "CEO"), turn.content());
                msgs.add(ChatMessage.toolResult(tc.id(), tc.name(), out));
                ev.append("- ").append(tc.name()).append(" (").append(clip(tc.arguments(), 200)).append(")\n  ")
                  .append(clip(out, 1500)).append("\n");
            }
        }
        if (used > 0) log.info("CEO 取证完成:工具 {} 个,上下文约 {} 字",
                usable.size(), used);
        return ev.toString();
    }

    /** 解析并执行 CEO 决策 */
    private String executeDecisions(String reply, long cycleNo) {
        String json = extractJson(reply);
        String summary = "";
        int dispatched = 0, reported = 0, escalated = 0, reworked = 0, archived = 0, goalOps = 0;
        try {
            JsonNode root = mapper.readTree(json);
            summary = root.path("summary").asText("");
            JsonNode decisions = root.path("decisions");
            if (decisions.isArray()) {
                for (JsonNode d : decisions) {
                    String type = d.path("type").asText("");
                    switch (type) {
                        case "dispatch" -> {
                            long empId = d.path("employeeId").asLong(0);
                            String instruction = d.path("instruction").asText("");
                            if (empId > 0 && !instruction.isBlank()) {
                                try {
                                    List<Long> ctxIds = new ArrayList<>();
                                    JsonNode ctx = d.path("contextTaskIds");
                                    if (ctx.isArray()) {
                                        for (JsonNode c : ctx) {
                                            long id = c.asLong(0);
                                            if (id > 0) ctxIds.add(id);
                                        }
                                    }
                                    Task t = taskService.submit(empId, instruction, ctxIds.isEmpty() ? null : ctxIds);
                                    activityRepo.save(new Activity(Activity.REPORT,
                                            "派活 → " + t.getEmployee().getName() + ":#" + t.getId() + " " + instruction, cycleNo));
                                    dispatched++;
                                } catch (Exception e) {
                                    saveReportAggregated("派活失败:" + e.getMessage(), cycleNo, Duration.ofHours(1));
                                }
                            }
                        }
                        case "rework" -> {
                            long taskId = d.path("taskId").asLong(0);
                            String comment = d.path("comment").asText("");
                            if (taskId > 0 && !comment.isBlank()) {
                                Task origin = taskRepo.findById(taskId).orElse(null);
                                if (origin != null && origin.getEmployee() != null && !origin.getEmployee().isCeo()) {
                                    try {
                                        String instruction = "【返工】关于任务 #" + taskId + ",CEO 意见:" + comment
                                                + "\n请在原产出基础上修改完善,直接输出修订后的完整结果。";
                                        Task t = taskService.submit(origin.getEmployee().getId(), instruction,
                                                List.of(taskId));
                                        activityRepo.save(new Activity(Activity.REPORT,
                                                "返工 → " + t.getEmployee().getName() + ":#" + t.getId() + "(针对 #" + taskId + ")", cycleNo));
                                        reworked++;
                                    } catch (Exception e) {
                                        saveReportAggregated("返工失败:" + e.getMessage(), cycleNo, Duration.ofHours(1));
                                    }
                                }
                            }
                        }
                        case "goal_done" -> {
                            long goalId = d.path("goalId").asLong(0);
                            if (goalId > 0) {
                                Activity g = activityRepo.findById(goalId).orElse(null);
                                if (g != null && Activity.OPEN.equals(g.getStatus())) {
                                    g.setStatus(Activity.ACK);
                                    activityRepo.save(g);
                                    String msg = d.path("message").asText("");
                                    activityRepo.save(new Activity(Activity.REPORT,
                                            "目标已归档:「" + clip(g.getContent(), 60) + "」" + (msg.isBlank() ? "" : " — " + msg), cycleNo));
                                    archived++;
                                }
                            }
                        }
                        case "report" -> {
                            String msg = d.path("message").asText("");
                            if (!msg.isBlank()) {
                                activityRepo.save(new Activity(Activity.REPORT, msg, cycleNo));
                                reported++;
                            }
                        }
                        case "escalate" -> {
                            String msg = d.path("message").asText("");
                            if (!msg.isBlank()) {
                                activityRepo.save(new Activity(Activity.ESCALATION, msg, cycleNo));
                                escalated++;
                                pushEscalation();
                            }
                        }
                        case "goal_start" -> {
                            long goalId = d.path("goalId").asLong(0);
                            if (goalId > 0) {
                                try {
                                    goalService.start(goalId);
                                    activityRepo.save(new Activity(Activity.REPORT,
                                            "CEO 重新推进目标 #" + goalId + "。", cycleNo));
                                    goalOps++;
                                } catch (Exception e) {
                                    saveReportAggregated("推进目标失败:" + e.getMessage(), cycleNo, Duration.ofHours(1));
                                }
                            }
                        }
                        case "retry_workstream" -> {
                            long wsId = d.path("workstreamId").asLong(0);
                            if (wsId > 0) {
                                Workstream ws = wsRepo.findById(wsId).orElse(null);
                                if (ws != null && ws.getGoal() != null) {
                                    try {
                                        goalService.retryWorkstream(ws.getGoalId(), wsId);
                                        activityRepo.save(new Activity(Activity.REPORT,
                                                "CEO 解阻工作流「" + ws.getTitle() + "」。", cycleNo));
                                        goalOps++;
                                    } catch (Exception e) {
                                        saveReportAggregated("解阻失败:" + e.getMessage(), cycleNo, Duration.ofHours(1));
                                    }
                                }
                            }
                        }
                        case "goal_accept" -> {
                            long goalId = d.path("goalId").asLong(0);
                            if (goalId > 0) {
                                try {
                                    goalService.accept(goalId);
                                    goalOps++;
                                } catch (Exception e) {
                                    saveReportAggregated("归档目标失败:" + e.getMessage(), cycleNo, Duration.ofHours(1));
                                }
                            }
                        }
                        default -> { /* 忽略未知类型 */ }
                    }
                }
            }
        } catch (Exception e) {
            summary = "输出解析失败,已存原始回复";
            saveReportAggregated(clip(reply, 2000), cycleNo, Duration.ofHours(1));
        }
        final String s = summary.isBlank() ? "本轮无评估" : summary;
        if (dispatched == 0 && reported == 0 && escalated == 0 && reworked == 0 && archived == 0 && goalOps == 0) {
            saveReportAggregated(s, cycleNo, Duration.ofMinutes(30));
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cycleNo", cycleNo);
        p.put("summary", s);
        pusher.push("ceo-cycle", p);
        return String.format("%s(派活 %d,返工 %d,归档 %d,目标操作 %d,汇报 %d,上报 %d)",
                s, dispatched, reworked, archived, goalOps, reported, escalated);
    }

    /** 聚合汇报:相同内容在窗口期内只记一条,防刷屏 */
    private void saveReportAggregated(String content, Long cycleNo, Duration window) {
        try {
            String clipped = clip(content, 2000);
            Instant since = Instant.now().minus(window);
            for (Activity a : activityRepo.findTop100ByOrderByCreatedAtDescIdDesc()) {
                if (Activity.REPORT.equals(a.getType()) && clipped.equals(a.getContent())
                        && a.getCreatedAt() != null && a.getCreatedAt().isAfter(since)) {
                    return; // 窗口内已有相同汇报,跳过
                }
            }
            activityRepo.save(new Activity(Activity.REPORT, clipped, cycleNo));
        } catch (Exception e) {
            log.warn("汇报写入失败: {}", e.getMessage());
        }
    }

    private static String extractJson(String s) {
        if (s == null) return "{}";
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        return (l >= 0 && r > l) ? s.substring(l, r + 1) : "{}";
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 粗略估算 token:中文约 1.5 字符/token,英文约 4 字符/token,取折中 */
    static long estimateTokens(int chars) {
        return Math.max(1, (long) (chars / 2.2));
    }

    private void pushCeoStatus(String status) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", status);
        p.put("cycleNo", cycleCounter.get());
        pusher.push("ceo-status", p);
    }

    private void pushEscalation() {
        pusher.push("escalation", Map.of("count", activityRepo.countByTypeAndStatus(Activity.ESCALATION, Activity.OPEN)));
    }
}
