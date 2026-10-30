package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.entity.TaskLog;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.TaskLogRepository;
import com.blankgrid.aione.repo.TaskRepository;
import com.blankgrid.aione.service.LlmService.ChatMessage;
import com.blankgrid.aione.service.LlmService.ToolCall;
import com.blankgrid.aione.service.LlmService.ToolSpec;
import com.blankgrid.aione.service.LlmService.Turn;
import com.blankgrid.aione.tool.ToolContext;
import com.blankgrid.aione.tool.ToolRegistry;
import com.blankgrid.aione.ws.EventPusher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * 任务执行器:每个岗位一条串行队列(同一岗位同时只执行一个任务),
 * 流式记录 LLM 输出并经 WebSocket 推送增量。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepo;
    private final TaskLogRepository logRepo;
    private final EmployeeRepository empRepo;
    private final LlmService llm;
    private final SettingsService settings;
    private final EventPusher pusher;
    private final com.blankgrid.aione.repo.TokenLogRepository tokenRepo;
    private final com.blankgrid.aione.repo.ActivityRepository activityRepo;
    private final ApplicationEventPublisher publisher;
    private final ToolRegistry tools;

    private final Map<Long, ExecutorService> queues = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public TaskService(TaskRepository taskRepo, TaskLogRepository logRepo,
                       EmployeeRepository empRepo, LlmService llm,
                       SettingsService settings, EventPusher pusher,
                       com.blankgrid.aione.repo.TokenLogRepository tokenRepo,
                       com.blankgrid.aione.repo.ActivityRepository activityRepo,
                       ApplicationEventPublisher publisher,
                       ToolRegistry tools) {
        this.taskRepo = taskRepo;
        this.logRepo = logRepo;
        this.empRepo = empRepo;
        this.llm = llm;
        this.settings = settings;
        this.pusher = pusher;
        this.tokenRepo = tokenRepo;
        this.activityRepo = activityRepo;
        this.publisher = publisher;
        this.tools = tools;
    }

    /**
     * 进程重启时,上一轮还在跑的任务其实已经随 JVM 死了,但库里仍写着 RUNNING。
     * 不收拾的话:交付物永远卡在 RUNNING,引擎的补收逻辑也不敢动它,整个目标就此僵住。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        List<Task> running = taskRepo.findByStatus(Task.RUNNING);
        if (running.isEmpty()) return;
        for (Task t : running) {
            t.setStatus(Task.FAILED);
            t.setError("服务重启,执行被中断;已自动重新排队");
            t.setFinishedAt(Instant.now());
            taskRepo.save(t);
            logRepo.save(new TaskLog(t, "warn", t.getError()));
            if (t.getEmployee() != null) {
                Employee e = t.getEmployee();
                if (Employee.STATUS_RUNNING.equals(e.getStatus())) {
                    e.setStatus(Employee.STATUS_IDLE);
                    e.setCurrentTaskSummary("");
                    empRepo.save(e);
                }
            }
            // 交给目标引擎按失败处理:重试或标记受阻,而不是让它无声地停在这里
            publisher.publishEvent(new TaskFinishedEvent(t.getId(), false, t.getError()));
        }
        log.warn("启动恢复:{} 个中断任务已标记失败并重新排队", running.size());
    }

    public Task submit(Long employeeId, String instruction, List<Long> contextTaskIds) {
        Employee emp = empRepo.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在: " + employeeId));
        if (emp.isCeo()) {
                throw new IllegalArgumentException("CEO 由 AI 担任,不接受直接派活。请在看板顶部指挥台下达目标或纠偏");
        }
        if (emp.getStatus() != null && Employee.STATUS_DISABLED.equals(emp.getStatus())) {
            throw new IllegalArgumentException("该岗位已停用");
        }
        Task task = new Task();
        task.setEmployee(emp);
        task.setInstruction(instruction == null ? "" : instruction);
        task.setModel(emp.getModel());
        if (contextTaskIds != null && !contextTaskIds.isEmpty()) {
            StringBuilder ctx = new StringBuilder();
            StringBuilder ids = new StringBuilder();
            for (Long tid : contextTaskIds) {
                taskRepo.findById(tid).ifPresent(t -> {
                    if (ctx.length() > 0) ctx.append("\n\n");
                    ctx.append("【来自 ").append(t.getEmployee().getName())
                       .append(" 的任务 #").append(t.getId()).append(" 产出】\n")
                       .append(t.getResult() == null ? "" : t.getResult());
                    if (ids.length() > 0) ids.append(",");
                    ids.append(t.getId());
                });
            }
            task.setContextText(ctx.toString());
            task.setSourceTaskIds(ids.toString());
        }
        task = taskRepo.save(task);
        logRepo.save(new TaskLog(task, "user", task.getInstruction()));
        if (!task.getContextText().isEmpty()) {
            logRepo.save(new TaskLog(task, "info", "附带上游产出:\n" + task.getContextText()));
        }
        pushTask(task);
        enqueue(task.getId());
        return task;
    }

    /** 目标引擎内部派活:交付、评审、返工,允许携带目标上下文 */
    public Task submitEngineTask(Long employeeId, String instruction, Long goalId, Long workstreamId,
                                 Long deliverableId, String kind, int attempt, String acceptanceCriteria,
                                 String contextText, Long artifactId) {
        Employee emp = empRepo.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在: " + employeeId));
        if (Employee.STATUS_DISABLED.equals(emp.getStatus())) {
            throw new IllegalArgumentException("岗位「" + emp.getName() + "」已停用");
        }
        Task task = new Task();
        task.setEmployee(emp);
        task.setInstruction(instruction == null ? "" : instruction);
        task.setModel(emp.getModel());
        task.setGoalId(goalId);
        task.setWorkstreamId(workstreamId);
        task.setDeliverableId(deliverableId);
        task.setKind(kind);
        task.setAttempt(Math.max(1, attempt));
        task.setAcceptanceCriteria(acceptanceCriteria);
        task.setArtifactId(artifactId);
        task.setContextText(contextText == null ? "" : contextText);
        task = taskRepo.save(task);
        logRepo.save(new TaskLog(task, "user", task.getInstruction()));
        if (!task.getContextText().isBlank()) {
            logRepo.save(new TaskLog(task, "info", "附加上下文:\n" + clip(task.getContextText(), 20000)));
        }
        pushTask(task);
        enqueue(task.getId());
        return task;
    }

    private void enqueue(Long taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            Long empId = task.getEmployee().getId();
            ExecutorService q = queues.computeIfAbsent(empId,
                    k -> Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "ai-emp-" + k);
                        t.setDaemon(true);
                        return t;
                    }));
            q.submit(() -> execute(taskId));
        });
    }

    /** 协作式取消:执行中的任务在下一个输出分片时中止 */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException() { super("任务已手动取消"); }
    }

    private void execute(Long taskId) {
        Optional<Task> opt = taskRepo.findById(taskId);
        if (opt.isEmpty()) return;
        Task task = opt.get();
        if (cancelled.remove(taskId)) return;

        Employee emp = empRepo.findById(task.getEmployee().getId()).orElse(null);
        if (emp == null) return;
        // 已叫停的岗位:队列中残留的任务直接作废,不执行
        if (Employee.STATUS_DISABLED.equals(emp.getStatus()) && !Task.DONE.equals(task.getStatus())) {
            task.setStatus(Task.CANCELLED);
            task.setError("岗位已被叫停,任务作废");
            task.setFinishedAt(Instant.now());
            taskRepo.save(task);
            logRepo.save(new TaskLog(task, "info", "岗位已被叫停,任务作废"));
            pushTask(task);
            return;
        }

        // 每日 token 预算兜底:到顶后不再发起新的模型调用
        long dailyBudget = settings.get().dailyTokenBudget;
        if (dailyBudget > 0 && tokenRepo.tokensSince(Instant.now().truncatedTo(ChronoUnit.DAYS)) >= dailyBudget) {
            task.setStatus(Task.FAILED);
            task.setError("今日 token 预算已用尽,任务未执行。提高预算或等明日重置。");
            task.setFinishedAt(Instant.now());
            taskRepo.save(task);
            logRepo.save(new TaskLog(task, "error", task.getError()));
            emp.setStatus(Employee.STATUS_IDLE);
            emp.setCurrentTaskSummary("");
            empRepo.save(emp);
            pushTask(task);
            pushEmployee(emp);
            publisher.publishEvent(new TaskFinishedEvent(taskId, false, task.getError()));
            return;
        }

        // 状态 → 执行中
        task.setStatus(Task.RUNNING);
        task.setStartedAt(Instant.now());
        task = taskRepo.save(task);
        emp.setStatus(Employee.STATUS_RUNNING);
        emp.setCurrentTaskSummary(truncate(task.getInstruction(), 40));
        empRepo.save(emp);
        pushTask(task);
        pushEmployee(emp);

        try {
            List<ChatMessage> messages = new ArrayList<>();
            String sys = emp.getSystemPrompt() == null ? "" : emp.getSystemPrompt();
            // 知识库会随复盘不断变长,全文塞进每次调用等于给每个任务加一笔固定开销。
            // 900 字够放下全部"经验教训"要点,再长就是重复和寒暄。
            String kb = settings.get().knowledgeBase;
            if (kb != null && !kb.isBlank()) {
                sys += "\n\n【公司知识库】\n" + clip(kb, 900);
            }
            // 岗位自己的经验教训(复盘沉淀),放在最后、最近优先
            if (emp.getLearnings() != null && !emp.getLearnings().isBlank()) {
                sys += "\n\n【你自己的经验教训,务必避免重蹈覆辙】\n" + clip(emp.getLearnings(), 700);
            }
            // 模型即使拿到了工具表,也倾向于"凭记忆作答"—— 尤其训练数据里见过的品类。
            // 不明确要求它先查,产出里就会出现看着像真的、实则编造的价格和销量,
            // 而这种错误比写空话危险得多:客户可能真的照着它去下单。
            List<com.blankgrid.aione.tool.AiTool> usable = tools.usable();
            if (!usable.isEmpty()) {
                StringBuilder tip = new StringBuilder("\n\n【你手上的工具】\n");
                tip.append("你可以调用以下工具获取真实数据,不必只靠已有知识:\n");
                for (com.blankgrid.aione.tool.AiTool t : usable) {
                    tip.append("- ").append(t.name()).append(":").append(firstLine(t.description())).append("\n");
                }
                tip.append("\n硬性要求:凡涉及**价格、销量、库存、店铺、链接**等外部事实,必须先调用工具查证。")
                   .append("查不到就如实写「未查到」,并在文中说明建议人工核实 —— 绝对不要编造数字、")
                   .append("不要用『约』『大概』来掩盖没有数据。客户会拿着这份东西去下单。");
                // 比价单最容易出的不是数字错,是"型号对不上、风险没提、发票没交代"。
                // 这三件事到了付款环节才被发现,代价就是退不了货。
                if (usable.stream().anyMatch(t -> "ecom_search".equals(t.name()))) {
                    tip.append("\n\n做采购/比价类任务时,额外必须做到:\n")
                       .append("- 报价要写全型号(容量/版本/颜色)和到手价,不同规格绝不能合成一个价;\n")
                       .append("- 结果里带 ⚠ 的(港版/美版/翻新/官换/合约/二手)不能作为推荐款;")
                       .append("价格确实更低可以列出来对比,但必须写明风险,不能让读者以为那是划算;\n")
                       .append("- 批量采购要交代清楚:店铺能否开增值税专票、退换货政策、批量价是否需另谈、运费与到货周期;\n")
                       .append("- 每个推荐款都必须单独给出完整商品链接(原样照抄工具返回的「下单」地址),")
                       .append("不要写成「可在京东搜索」或只给商品名 —— 读者要能点一下就进到那一款的购买页;\n")
                       .append("- 手机/数码类普遍「激活或拆封后不支持 7 天无理由」,这一点要主动写进结论,不要等人问;\n")
                       .append("- 你只提供信息与链接,不代下单、不代付款、不承担售后。结尾要提醒由人自行核实后下单。");
                }
                sys += tip.toString();
            }
            messages.add(new ChatMessage("system", sys));
            if (task.getContextText() != null && !task.getContextText().isEmpty()) {
                // 上游产出全量透传时,一个任务的输入能有几万字,而其中大部分与本次任务无关。
                // 超长就保留头尾(结论与收尾常在两端),中间标注省略 —— 既省钱又不丢主线。
                messages.add(new ChatMessage("user", "以下是上游岗位的产出,作为本次任务的参考输入:\n"
                        + clipMiddle(task.getContextText(), 6000) + "\n\n请基于以上材料完成任务。"));
            }
            messages.add(new ChatMessage("user", task.getInstruction()));

            StringBuilder sb = new StringBuilder();
            int promptChars = sys.length() + task.getInstruction().length() + (task.getContextText() == null ? 0 : task.getContextText().length());
            // 记账要记清是哪个模型烧的:不同模型差十倍价,只记 token 数算不出钱
            String usedModel = (task.getModel() != null && !task.getModel().isBlank())
                    ? task.getModel() : settings.defaultModel();
            // 评审任务要的是结构化结论(score/pass/notes) —— 服务端直接强制 JSON 输出,
            // 免得评审员写成一段散文,引擎解析不到分数只能判 0 分反复返工。
            boolean jsonTask = Task.KIND_REVIEW.equals(task.getKind());
            java.util.function.Consumer<String> onDelta = delta -> {
                if (cancelled.contains(taskId)) throw new TaskCancelledException();
                sb.append(delta);
                Map<String, Object> p = new HashMap<>();
                p.put("taskId", taskId);
                p.put("delta", delta);
                pusher.push("task-delta", p);
            };
            // 评审员要的是结构化结论,给了工具反而容易让它去查资料、忘了打分。
            // 只有干活的岗位才配工具。
            String result = jsonTask
                    ? llm.chatStream(messages, task.getModel(), onDelta, null, true, EXEC_MAX_TOKENS)
                    : converse(messages, task, emp, onDelta, taskId);
            // 评审任务要的是结构化 JSON,截断续写只会把 JSON 拼坏,宁可让它整份重来
            if (!jsonTask) {
                result = continueIfTruncated(messages, task, onDelta, taskId, result);
            }
            tokenRepo.save(new com.blankgrid.aione.entity.TokenLog(taskId, task.getGoalId(), "TASK",
                    llm.tokensUsed(CeoLoopService.estimateTokens(promptChars + result.length())),
                    llm.lastModel()));

            // 复盘任务完成:教训沉淀进全员知识库 + 岗位自己的成长档案(自学习闭环)
            if (task.getInstruction() != null && task.getInstruction().startsWith(RETRO_TAG)) {
                try {
                    settings.appendKnowledge(clip(result, 300));
                    String add = clip(result.replace('\n', ' '), 200);
                    String cur = emp.getLearnings() == null ? "" : emp.getLearnings();
                    // 保留最近 10 条,新的在前
                    String[] lines = cur.isBlank() ? new String[0] : cur.split("\n");
                    StringBuilder merged = new StringBuilder("- " + add);
                    int kept = 0;
                    for (String line : lines) {
                        if (line.isBlank() || merged.toString().contains(line)) continue;
                        if (kept++ < 9) merged.append("\n").append(line);
                    }
                    emp.setLearnings(merged.toString());
                    empRepo.save(emp);
                    logRepo.save(new TaskLog(task, "info", "本次复盘已沉淀进全员知识库与岗位成长档案"));
                } catch (Exception ignored) {}
            }

            task.setResult(result);
            task.setStatus(Task.DONE);
            task.setFinishedAt(Instant.now());
            taskRepo.save(task);
            logRepo.save(new TaskLog(task, "assistant", result));

            emp.setStatus(Employee.STATUS_IDLE);
            emp.setCurrentTaskSummary("");
            empRepo.save(emp);
            pushTask(task);
            pushEmployee(emp);
            publisher.publishEvent(new TaskFinishedEvent(taskId, true, null));
        } catch (TaskCancelledException ce) {
            task.setStatus(Task.CANCELLED);
            task.setError(ce.getMessage());
            task.setFinishedAt(Instant.now());
            taskRepo.save(task);
            logRepo.save(new TaskLog(task, "info", ce.getMessage()));
            emp.setStatus(Employee.STATUS_IDLE);
            emp.setCurrentTaskSummary("");
            empRepo.save(emp);
            pushTask(task);
            pushEmployee(emp);
            publisher.publishEvent(new TaskFinishedEvent(taskId, false, ce.getMessage()));
        } catch (Exception e) {
            task.setStatus(Task.FAILED);
            task.setError(e.getMessage());
            task.setFinishedAt(Instant.now());
            taskRepo.save(task);
            logRepo.save(new TaskLog(task, "error", e.getMessage()));
            emp.setStatus(Employee.STATUS_ERROR);
            emp.setCurrentTaskSummary("");
            empRepo.save(emp);
            pushTask(task);
            pushEmployee(emp);

            // 目标体系内的任务由目标引擎按验收/返工规则处理,避免与自动复盘互相抢占队列
            if (task.getGoalId() == null) selfLearning(task, emp);
            publisher.publishEvent(new TaskFinishedEvent(taskId, false, e.getMessage()));
        }
    }

    /** 自学习:失败任务自动派复盘任务(哪里有问题/为什么/怎么避免);连续失败触发熔断并上报创始人 */
    private void selfLearning(Task failedTask, Employee emp) {
        if (!settings.hasProvider()) return;
        String instruction = failedTask.getInstruction() == null ? "" : failedTask.getInstruction();
        boolean isRetro = instruction.startsWith(RETRO_TAG);
        if (isRetro) return; // 复盘本身失败不再套娃

        // 失败 → 自动派复盘任务。但复盘本身也是一次模型调用:
        // 同一个岗位 30 分钟内只复盘一次,否则"失败→复盘→再失败"会变成连环烧钱。
        if (retroRecent(emp, Duration.ofMinutes(30))) {
            log.info("自学习: 岗位{} 30 分钟内已复盘过,跳过本轮复盘", emp.getName());
            checkCircuit(emp, failedTask);
            return;
        }
        try {
            Task retro = new Task();
            retro.setEmployee(emp);
            retro.setInstruction(RETRO_TAG + "你刚执行的任务 #" + failedTask.getId() + " 失败了(指令:" + clip(instruction, 80) + ")。" +
                    "请复盘:1) 哪里有问题 2) 为什么会有这个问题 3) 后面怎么避免。只输出简洁结论,三条各一行。");
            retro.setSourceTaskIds(String.valueOf(failedTask.getId()));
            retro.setContextText("【失败任务现场】\n指令:" + instruction + "\n失败原因:" + failedTask.getError());
            retro.setModel(emp.getModel());
            retro = taskRepo.save(retro);
            logRepo.save(new TaskLog(retro, "info", "自学习机制:失败自动触发复盘"));
            enqueue(retro.getId());
        } catch (Exception ignored) {}

        checkCircuit(emp, failedTask);
    }

    /**
     * 带工具的多轮对话循环。
     *
     * <p>这是「AI 员工只会写字」和「AI 员工能干活」的分界线:模型说要查什么,
     * 我们真的去查,把一手数据塞回上下文,再让它接着写。对模型来说这些数字和它自己
     * 编出来的没有区别 —— 但对客户来说,一个是空的,一个是能直接拿去下单的。
     */
    private String converse(List<ChatMessage> messages, Task task, Employee emp,
                            java.util.function.Consumer<String> onDelta, Long taskId) {
        List<ToolSpec> specs = new ArrayList<>();
        for (com.blankgrid.aione.tool.AiTool t : tools.usable()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.paramsSchema()));
        }
        // 一个工具都没配(新装的库、或密钥还没填):退回纯文本,别白发一段空工具声明
        if (specs.isEmpty()) {
            return llm.chatStream(messages, task.getModel(), onDelta, null, false, EXEC_MAX_TOKENS);
        }

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            Turn turn = llm.chatWithTools(messages, task.getModel(), onDelta, null, false, EXEC_MAX_TOKENS, specs);
            if (!turn.wantsTools()) {
                return turn.content();
            }
            // 模型要调工具:先把"我想调"这句记进历史。少这一步,下一轮它看不懂
            // 那条 tool 消息是在回应谁,会重新问一遍同样的问题。
            messages.add(new ChatMessage("assistant", turn.content(), turn.toolCalls(), null, null));

            for (ToolCall tc : turn.toolCalls()) {
                if (cancelled.contains(taskId)) throw new TaskCancelledException();
                pushToolCall(taskId, tc.name());
                String out = tools.invoke(tc.name(), tc.arguments(),
                        new ToolContext(task.getGoalId(), taskId, emp.getName()));
                messages.add(ChatMessage.toolResult(tc.id(), tc.name(), out));
                // 工具调用写进任务日志:出了问题要能回答"这个数字是哪来的"
                logRepo.save(new TaskLog(task, "info",
                        "调用 " + tc.name() + " → " + clip(out.replace('\n', ' '), 160)));
            }

            if (round == MAX_TOOL_ROUNDS) {
                // 最后一轮撤掉工具表:手里没工具,它就只能基于已查到的材料组织结论。
                // 留着工具还在,模型会继续搜,永远不交卷。
                messages.add(new ChatMessage("user",
                        "工具调用轮次已到上限,不要再查了。请直接基于上面已经拿到的数据给出最终成果;"
                                + "确实缺的数据在文中注明「未查到」,不要编造。")); 
                Turn last = llm.chatWithTools(messages, task.getModel(), onDelta, null, false, EXEC_MAX_TOKENS, null);
                return last.content();
            }
        }
        return "";
    }

    /** 告诉前端"它正在查什么"。模型安静调工具时界面不能看上去像卡死。 */
    private void pushToolCall(Long taskId, String toolName) {
        Map<String, Object> p = new HashMap<>();
        p.put("taskId", taskId);
        p.put("tool", toolName);
        pusher.push("task-tool", p);
    }

    // ==================== 长交付物续写 ====================

    /** 续写最多几段。到顶就停,宁可不完整也不能无限烧下去。 */
    static final int MAX_CONTINUE_SEGMENTS = 4;
    /** 续写后总字数上限。四万字已经远超任何一份正常交付物,再多就是模型在兜圈子。 */
    static final int CONTINUE_MAX_CHARS = 40000;
    /** 发回给模型的已写内容尾部长度。只要够它接上话,不需要看全文 —— 全文会让每段续写的成本翻倍。 */
    static final int CONTINUE_TAIL_CHARS = 1200;

    static final String CONTINUE_PROMPT = """
            你的上一段输出到达单次输出上限被截断了,内容还没写完。
            请**紧接最后一个字符继续写**:不要重写开头,不要总结前文,不要加「续上」「接上文」这类过渡语,
            不要重复任何已经写过的内容。直接从下一个字符开始输出剩下的部分。
            如果内容确实已经写完,只输出 [[DONE]] 这四个字符,不要输出别的东西。""";

    /** 模型接话时爱加的废话前缀。留着会印在交付物中间,评审一眼就看出是拼出来的。 */
    private static final String[] CONTINUE_NOISE = {
            "（接上文）", "(接上文)", "（续）", "(续)", "（继续）", "(继续)",
            "接上文：", "接上文:", "续上：", "续上:", "继续：", "继续:"
    };

    /**
     * 交付物被输出上限截断时,自动接着写。
     *
     * <p>这是当前最烧钱也最致命的坑:8000 token 装不下一份整站代码或一份完整 PRD,
     * 而模型不会报错,它会老老实实写一句「此处省略 5826 字」然后收尾 —— 评审看到的是残稿,
     * 打回返工,再写一次还是截断。目标 #6 的官网就死在这个循环里:24 万 token,72 分,正文缺四节附录。
     *
     * <p>解法不是把上限调大(那是拿钱硬顶,而且总有更大的交付物),是让它接着写。
     * 每段只回传尾部一小段,上下文不膨胀,所以续写的单价比首段还低。
     */
    private String continueIfTruncated(List<ChatMessage> messages, Task task,
                                       java.util.function.Consumer<String> onDelta,
                                       Long taskId, String first) {
        if (first == null || first.isBlank() || !llm.lastTruncated()) return first;

        StringBuilder all = new StringBuilder(first);
        log.info("任务 {} 正文被截断({} 字),进入续写", taskId, all.length());

        for (int seg = 1; seg <= MAX_CONTINUE_SEGMENTS; seg++) {
            if (cancelled.contains(taskId)) throw new TaskCancelledException();
            if (all.length() >= CONTINUE_MAX_CHARS) {
                log.warn("任务 {} 续写达到 {} 字上限,停止", taskId, CONTINUE_MAX_CHARS);
                break;
            }
            List<ChatMessage> cont = new ArrayList<>(messages);
            cont.add(new ChatMessage("user", "【已写内容共 " + all.length() + " 字,以下是它的结尾】\n"
                    + tail(all.toString(), CONTINUE_TAIL_CHARS) + "\n\n" + CONTINUE_PROMPT));

            String more;
            try {
                more = llm.chatStream(cont, task.getModel(), onDelta, null, false, EXEC_MAX_TOKENS);
            } catch (Exception ex) {
                // 续写失败不能毁掉已有的半份成果:拿到多少算多少,评审至少还有东西看
                log.warn("任务 {} 续写第 {} 段失败:{}", taskId, seg, ex.getMessage());
                break;
            }
            // 每段单独记账。tokensUsed 只反映最后一次调用,不单独记就会把续写的钱算丢
            tokenRepo.save(new com.blankgrid.aione.entity.TokenLog(taskId, task.getGoalId(), "TASK",
                    llm.tokensUsed(CeoLoopService.estimateTokens(more == null ? 0 : more.length())),
                    llm.lastModel()));

            if (more == null || more.isBlank()) break;
            if (more.contains("[[DONE]]")) {
                log.info("任务 {} 续写第 {} 段宣告写完", taskId, seg);
                break;
            }
            int added = appendWithOverlap(all, more);
            logRepo.save(new TaskLog(task, "info",
                    "正文被输出上限截断,已自动续写第 " + seg + " 段(+" + added + " 字)"));
            if (added <= 0) break;              // 没有新内容,再问也是重复
            if (!llm.lastTruncated()) break;    // 这次写完了,不用再续
        }
        return all.toString();
    }

    /**
     * 把续写内容接到已有内容后面,并去掉接缝处的重复。
     *
     * <p>模型被要求"接着写"时,十有八九会把上一段最后一句重抄一遍再往下走。
     * 不去重,交付物中间就会出现一整段重复文字 —— 评审会当成模型在凑字数。
     */
    static int appendWithOverlap(StringBuilder all, String more) {
        if (more == null || more.isEmpty()) return 0;
        String add = stripContinueNoise(more);
        if (add.isEmpty()) return 0;

        String head = all.toString();
        int probe = Math.min(160, head.length());
        if (probe > 0) {
            // 先试最严格的一种:接缝整段被原样重抄
            String seam = head.substring(head.length() - probe);
            int at = add.indexOf(seam);
            if (at >= 0) {
                String rest = add.substring(at + probe);
                // 剩下那截如果本来就整段在原文里,说明模型只是把同一段又写了一遍。
                // 不认这种情况,续写循环会一路空转到段数上限,白烧四轮钱。
                if (!rest.isBlank() && head.contains(rest.strip())) return 0;
                all.append(rest);
                return rest.length();
            }
            // 再退一步:找最长的一截「我结尾 = 你开头」。
            // 下限取 6 字:中文一个短句就是这个长度,再严就会漏掉最常见的"重抄半句";
            // 上限卡在续写内容的一半,避免一口气把整段都当成重复剪光。
            int max = Math.min(200, Math.min(head.length(), Math.max(6, add.length() / 2)));
            for (int k = max; k >= 6; k--) {
                if (head.regionMatches(head.length() - k, add, 0, k)) {
                    String rest = add.substring(k);
                    if (!rest.isBlank() && head.contains(rest.strip())) return 0;
                    all.append(rest);
                    return rest.length();
                }
            }
        }
        all.append(add);
        return add.length();
    }

    private static String stripContinueNoise(String s) {
        String t = s.stripLeading();
        for (String noise : CONTINUE_NOISE) {
            if (t.startsWith(noise)) {
                t = t.substring(noise.length()).stripLeading();
                break;
            }
        }
        return t;
    }

    /** 取尾部若干字。续写只需要看到"话说到哪儿了",不需要看全文。 */
    static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /** 连续失败熔断:同岗位连续 3 次失败自动叫停,上报创始人 */
    private void checkCircuit(Employee emp, Task failedTask) {
        try {
            List<Task> recent = new ArrayList<>();
            for (Task t : taskRepo.findTop200ByOrderByCreatedAtDesc()) {
                if (t.getEmployee() != null && emp.getId().equals(t.getEmployee().getId())) {
                    recent.add(t);
                    if (recent.size() >= 20) break;
                }
            }
            int consecutive = 0;
            for (Task t : recent) {
                // 跳过复盘任务与未决任务,只看真实执行结果
                if (t.getInstruction() != null && t.getInstruction().startsWith(RETRO_TAG)) continue;
                if (Task.PENDING.equals(t.getStatus()) || Task.RUNNING.equals(t.getStatus())
                        || Task.CANCELLED.equals(t.getStatus())) continue;
                if (Task.FAILED.equals(t.getStatus())) consecutive++;
                else break;
            }
            log.info("自学习: 岗位{} 最近连续失败 {} 次 (检查了 {} 条)", emp.getName(), consecutive, recent.size());
            if (consecutive >= 3 && !Employee.STATUS_DISABLED.equals(emp.getStatus())) {
                emp.setStatus(Employee.STATUS_DISABLED);
                empRepo.save(emp);
                activityRepo.save(new com.blankgrid.aione.entity.Activity(
                        com.blankgrid.aione.entity.Activity.ESCALATION,
                        "岗位「" + emp.getName() + "」连续 " + consecutive + " 次任务失败,已自动叫停。最近失败原因:" + clip(failedTask.getError(), 150) +
                                "。复盘完成后请创始人复核复岗。",
                        null));
                Map<String, Object> p = new HashMap<>();
                p.put("count", activityRepo.countByTypeAndStatus(com.blankgrid.aione.entity.Activity.ESCALATION,
                        com.blankgrid.aione.entity.Activity.OPEN));
                pusher.push("escalation", p);
                pushEmployee(emp);
                log.warn("熔断触发:岗位 {} 已自动叫停", emp.getName());
            }
        } catch (Exception e) {
            log.error("熔断检查失败", e);
        }
    }

    static final String RETRO_TAG = "【复盘】";

    /** 单次任务的输出上限。不传的话服务商默认常是 4K,长文案写到一半就断,返工一次等于双倍花钱。 */
    static final int EXEC_MAX_TOKENS = 8000;

    /**
     * 一次任务最多让模型调几轮工具。
     *
     * <p>比价这类活一两轮就够(搜一次、看一眼结果)。设这个上限不是为了限功能,
     * 是为了防死循环:模型可能反复用同一个关键词搜索,每轮都在烧 token 却毫无进展。
     * 到顶就撤掉工具表逼它交卷,拿到半成品也比无限烧下去强。
     */
    static final int MAX_TOOL_ROUNDS = 5;

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 该岗位最近 window 内是否已经派过复盘任务。 */
    private boolean retroRecent(Employee emp, Duration window) {
        Instant since = Instant.now().minus(window);
        int n = 0;
        for (Task t : taskRepo.findTop200ByOrderByCreatedAtDesc()) {
            if (n++ >= 50) break;
            if (t.getEmployee() == null || !emp.getId().equals(t.getEmployee().getId())) continue;
            if (t.getInstruction() == null || !t.getInstruction().startsWith(RETRO_TAG)) continue;
            if (t.getCreatedAt() != null && t.getCreatedAt().isAfter(since)) return true;
        }
        return false;
    }

    /** 取一段说明的第一句,塞进系统提示时不占太多篇幅。 */
    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "";
        int cut = s.indexOf('\n');
        String one = cut > 0 ? s.substring(0, cut) : s;
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    /** 超长文本保留头尾各一半,中间标记省略 —— 比只截前面更不容易丢掉结论。 */
    static String clipMiddle(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int keep = Math.max(200, (max - 40) / 2);
        int omitted = s.length() - keep * 2;
        return s.substring(0, keep) + "\n\n…(此处省略 " + omitted + " 字,与本次任务无关的内容已裁掉)…\n\n"
                + s.substring(s.length() - keep);
    }

    @Transactional
    public boolean cancel(Long taskId) {
        Optional<Task> opt = taskRepo.findById(taskId);
        if (opt.isEmpty()) return false;
        Task t = opt.get();
        if (Task.PENDING.equals(t.getStatus())) {
            cancelled.add(taskId);
            t.setStatus(Task.CANCELLED);
            t.setFinishedAt(Instant.now());
            taskRepo.save(t);
            logRepo.save(new TaskLog(t, "info", "任务已取消"));
            pushTask(t);
            publisher.publishEvent(new TaskFinishedEvent(taskId, false, "任务已取消"));
            return true;
        }
        if (Task.RUNNING.equals(t.getStatus())) {
            // 标记取消,执行线程在下一个输出分片时中止
            cancelled.add(taskId);
            logRepo.save(new TaskLog(t, "info", "取消请求已发出……"));
            pushTask(t);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<Task> recentTasks() {
        return taskRepo.findTop200ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Task> tasksOf(Long employeeId) {
        return taskRepo.findTop100ByEmployeeIdOrderByCreatedAtDesc(employeeId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> taskDetail(Long taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        List<TaskLog> logs = logRepo.findByTaskIdOrderByCreatedAtAscIdAsc(taskId);
        // 协作链:解析上游任务摘要
        List<Map<String, Object>> upstream = new ArrayList<>();
        if (t.getSourceTaskIds() != null && !t.getSourceTaskIds().isBlank()) {
            for (String s : t.getSourceTaskIds().split(",")) {
                try {
                    taskRepo.findById(Long.parseLong(s.trim())).ifPresent(src -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", src.getId());
                        m.put("employeeName", src.getEmployee() == null ? "" : src.getEmployee().getName());
                        m.put("instruction", src.getInstruction());
                        m.put("status", src.getStatus());
                        upstream.add(m);
                    });
                } catch (NumberFormatException ignored) {}
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", t);
        m.put("logs", logs);
        m.put("upstream", upstream);
        return m;
    }

    private void pushTask(Task t) {
        Map<String, Object> p = new HashMap<>();
        p.put("task", t);
        pusher.push("task-update", p);
    }

    private void pushEmployee(Employee e) {
        Map<String, Object> p = new HashMap<>();
        p.put("employeeId", e.getId());
        p.put("status", e.getStatus());
        p.put("currentTaskSummary", e.getCurrentTaskSummary());
        pusher.push("employee-status", p);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @PreDestroy
    void shutdown() {
        queues.values().forEach(ExecutorService::shutdownNow);
    }
}
