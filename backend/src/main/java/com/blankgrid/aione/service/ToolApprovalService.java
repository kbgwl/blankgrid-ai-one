package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.repo.ToolApprovalRepository;
import com.blankgrid.aione.tool.ToolContext;
import com.blankgrid.aione.tool.ToolRegistry;
import com.blankgrid.aione.ws.EventPusher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「需批准」类工具的审批与重放。
 *
 * <p>为什么要有重放:策略设成 confirm 的工具,模型调它的时候往往没人在场
 * (CEO 巡视是定时的、岗位任务是排队跑的)。只回一句"需要批准",这次动作就永久丢了,
 * 下一轮模型看到"上次没查成",只能再试一次 —— 死循环。
 *
 * <p>所以批准不是"允许下次",而是"现在就把它补上":拿原始参数执行一遍,
 * 结果既写回审批单,也落一条公司动态,CEO 下一轮巡视就能看到这份证据。
 */
@Service
public class ToolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalService.class);

    private final ToolApprovalRepository repo;
    private final ToolRegistry tools;
    private final ActivityRepository activityRepo;
    private final EventPusher pusher;

    /** 单线程就够:审批是人点出来的,不会并发,而且串行能避免同时开一堆外部进程 */
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tool-approval");
        t.setDaemon(true);
        return t;
    });

    public ToolApprovalService(ToolApprovalRepository repo, ToolRegistry tools,
                               ActivityRepository activityRepo, EventPusher pusher) {
        this.repo = repo;
        this.tools = tools;
        this.activityRepo = activityRepo;
        this.pusher = pusher;
    }

    public List<ToolApproval> pending() {
        return repo.findByStatusOrderByCreatedAtDesc(ToolApproval.PENDING);
    }

    public List<ToolApproval> recent() {
        return repo.findTop50ByOrderByCreatedAtDescIdDesc();
    }

    public long pendingCount() {
        return repo.countByStatus(ToolApproval.PENDING);
    }

    /** 批准并立即执行。异步跑 —— REA 这类分析可能要几分钟,不能让 HTTP 请求挂在那儿。 */
    public Map<String, Object> approve(Long id) {
        ToolApproval a = repo.findById(id).orElse(null);
        if (a == null) return Map.of("ok", false, "message", "审批单不存在");
        if (!ToolApproval.PENDING.equals(a.getStatus())) {
            return Map.of("ok", false, "message", "这张单已经是 " + a.getStatus() + ",不能重复批准");
        }
        a.setStatus(ToolApproval.RUNNING);
        a.setDecidedAt(Instant.now());
        repo.save(a);
        pushCount();

        runner.submit(() -> {
            long t0 = System.currentTimeMillis();
            String result;
            try {
                ToolContext ctx = new ToolContext(a.getGoalId(), a.getTaskId(), a.getActor());
                result = tools.executeRaw(a.getToolName(), a.getArgsJson(), ctx);
            } catch (Exception e) {
                result = "执行失败:" + e.getMessage();
            }
            long cost = System.currentTimeMillis() - t0;
            ToolApproval fresh = repo.findById(id).orElse(a);
            fresh.setResult(result);
            fresh.setStatus(result != null && (result.contains("失败") || result.contains("不可用"))
                    ? ToolApproval.FAILED : ToolApproval.DONE);
            fresh.setExecutedAt(Instant.now());
            repo.save(fresh);

            // 落一条公司动态:CEO 下一轮巡视读的是 activities，把证据放在它一定会读到的地方。
            try {
                activityRepo.save(new Activity(Activity.REPORT,
                        "【工具已批准执行】" + a.getToolName() + " 用时 " + cost + "ms:" + clip(result, 1200), null));
            } catch (Exception e) {
                log.warn("审批结果写动态失败:{}", e.getMessage());
            }
            log.info("工具审批执行完成:{} #{} {}ms", a.getToolName(), id, cost);
            pushCount();
        });

        return Map.of("ok", true, "message", "已批准,正在执行;结果会出现在公司动态里", "id", id);
    }

    public Map<String, Object> reject(Long id) {
        ToolApproval a = repo.findById(id).orElse(null);
        if (a == null) return Map.of("ok", false, "message", "审批单不存在");
        if (!ToolApproval.PENDING.equals(a.getStatus())) {
            return Map.of("ok", false, "message", "这张单已经是 " + a.getStatus());
        }
        a.setStatus(ToolApproval.REJECTED);
        a.setDecidedAt(Instant.now());
        a.setResult("创始人拒绝");
        repo.save(a);
        pushCount();
        return Map.of("ok", true, "message", "已拒绝;模型下次不会再拿到这个结果,会改用其他方式");
    }

    private void pushCount() {
        try {
            pusher.push("tool-approval", Map.of("pending", pendingCount()));
        } catch (Exception ignored) {
            // 推送失败不影响审批本身
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
