package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.Task;
import com.blankgrid.aione.entity.TaskLog;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.TaskLogRepository;
import com.blankgrid.aione.repo.TaskRepository;
import com.blankgrid.aione.service.LlmService.ChatMessage;
import com.blankgrid.aione.ws.EventPusher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final Map<Long, ExecutorService> queues = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public TaskService(TaskRepository taskRepo, TaskLogRepository logRepo,
                       EmployeeRepository empRepo, LlmService llm,
                       SettingsService settings, EventPusher pusher,
                       com.blankgrid.aione.repo.TokenLogRepository tokenRepo,
                       com.blankgrid.aione.repo.ActivityRepository activityRepo,
                       ApplicationEventPublisher publisher) {
        this.taskRepo = taskRepo;
        this.logRepo = logRepo;
        this.empRepo = empRepo;
        this.llm = llm;
        this.settings = settings;
        this.pusher = pusher;
        this.tokenRepo = tokenRepo;
        this.activityRepo = activityRepo;
        this.publisher = publisher;
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
            String kb = settings.get().knowledgeBase;
            if (kb != null && !kb.isBlank()) {
                sys += "\n\n【公司知识库】\n" + kb;
            }
            // 岗位自己的经验教训(复盘沉淀),放在最后、最近优先
            if (emp.getLearnings() != null && !emp.getLearnings().isBlank()) {
                sys += "\n\n【你自己的经验教训,务必避免重蹈覆辙】\n" + clip(emp.getLearnings(), 1500);
            }
            messages.add(new ChatMessage("system", sys));
            if (task.getContextText() != null && !task.getContextText().isEmpty()) {
                messages.add(new ChatMessage("user", "以下是上游岗位的产出,作为本次任务的参考输入:\n"
                        + task.getContextText() + "\n\n请基于以上材料完成任务。"));
            }
            messages.add(new ChatMessage("user", task.getInstruction()));

            StringBuilder sb = new StringBuilder();
            int promptChars = sys.length() + task.getInstruction().length() + (task.getContextText() == null ? 0 : task.getContextText().length());
            String result = llm.chatStream(messages, task.getModel(), delta -> {
                if (cancelled.contains(taskId)) throw new TaskCancelledException();
                sb.append(delta);
                Map<String, Object> p = new HashMap<>();
                p.put("taskId", taskId);
                p.put("delta", delta);
                pusher.push("task-delta", p);
            });

            tokenRepo.save(new com.blankgrid.aione.entity.TokenLog(taskId, task.getGoalId(), "TASK",
                    CeoLoopService.estimateTokens(promptChars + result.length())));

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

        // 失败 → 自动派复盘任务
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

        // 连续失败熔断:同岗位连续 3 次失败自动叫停,上报创始人
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

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
