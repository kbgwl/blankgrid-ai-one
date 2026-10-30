package com.blankgrid.aione.tool;

import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.repo.ToolApprovalRepository;
import com.blankgrid.aione.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 工具注册表:所有 AiTool Bean 在启动时自动登记。
 *
 * <p>加一个新平台只要写一个 AiTool 实现 + @Component,不用动主流程 ——
 * 淘宝、京东、1688、飞书、公司内部系统,对执行循环来说都是同一个东西。
 *
 * <p>「能不能自动调」不写死在调用点上:每次调用都过一遍
 * {@link SettingsService#toolPolicyFor},结论只有 auto / confirm / off 三种。
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 单次工具结果最多回给模型多少字符。超了截断 —— 工具返回一屏 JSON 是烧 token 的重灾区。 */
    static final int MAX_RESULT_CHARS = 6000;

    /** 默认墙钟上限;工具可以用 {@link AiTool#timeoutSeconds()} 覆盖。 */
    static final int TIMEOUT_SECONDS = 40;

    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tool-exec");
        t.setDaemon(true);
        return t;
    });

    private final SettingsService settings;
    private final ToolApprovalRepository approvals;

    /** 最近一轮任务里各工具被调用的次数与耗时,给界面显示"它到底干了什么" */
    private final Map<String, Map<String, Object>> lastRoundStats = new ConcurrentHashMap<>();

    public ToolRegistry(List<AiTool> discovered, SettingsService settings,
                        ToolApprovalRepository approvals) {
        this.settings = settings;
        this.approvals = approvals;
        for (AiTool t : discovered) {
            AiTool prev = tools.put(t.name(), t);
            if (prev != null) {
                log.warn("工具名重复:{} 被 {} 覆盖", t.name(), t.getClass().getSimpleName());
            }
        }
        log.info("工具注册完成:{} 个 [{}]", tools.size(), String.join(", ", tools.keySet()));
    }

    /** 当前真正可用的工具(没配密钥的不出现)。 */
    public List<AiTool> usable() {
        List<AiTool> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            if (t.available()) out.add(t);
        }
        return out;
    }

    /**
     * 决策层能看到的工具:可用 + 决策安全 + 策略为 auto。
     *
     * <p>confirm 的工具不给决策层:CEO 巡视是定时跑的,人不在场,
     * 给了它也只能拿到"等批准",白白多烧一轮上下文。
     */
    public List<AiTool> decisionTools() {
        List<AiTool> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            if (!t.available() || !t.decisionSafe()) continue;
            if (!SettingsService.POLICY_AUTO.equals(policyOf(t))) continue;
            out.add(t);
        }
        return out;
    }

    /** 全部工具,含不可用的,设置页用来告诉用户"差什么才能启用"。 */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("category", t.category());
            m.put("available", t.available());
            m.put("reason", t.unavailableReason());
            m.put("defaultPolicy", t.defaultPolicy());
            m.put("policy", policyOf(t));
            m.put("decisionSafe", t.decisionSafe());
            m.put("timeoutSeconds", t.timeoutSeconds());
            out.add(m);
        }
        return out;
    }

    /** 这个工具当前生效的策略。 */
    public String policyOf(AiTool t) {
        return settings.toolPolicyFor(t.name(), t.category(), t.defaultPolicy());
    }

    /**
     * 执行一次工具调用。策略在这里统一裁决:
     * off 直接拒、confirm 落一条待批单、auto 放行。
     *
     * <p>这个方法<b>永不抛异常</b>。工具失败是 agent 循环里的正常分支 ——
     * 模型拿到"搜索接口返回 403,可能是 Key 没权限"之后可以换个关键词或换平台,
     * 而一次异常冒到外层就等于整条任务失败,前面烧的 token 全白费。
     */
    public String invoke(String name, String argsJson, ToolContext ctx) {
        return invoke(name, argsJson, ctx, null);
    }

    /**
     * @param reason 模型调用前的说明,作为审批上下文;可空
     */
    public String invoke(String name, String argsJson, ToolContext ctx, String reason) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            return "没有名为 " + name + " 的工具。可用工具:" + String.join("、", names());
        }
        if (!tool.available()) {
            return "工具 " + name + " 当前不可用:" + tool.unavailableReason() + "。请改用其他方式或直接说明缺少该能力。";
        }

        String policy = policyOf(tool);
        if (SettingsService.POLICY_OFF.equals(policy)) {
            return "工具 " + name + " 已被创始人关闭,不要再尝试调用。请用其他方式回答,或直接说明缺少该能力。";
        }
        if (SettingsService.POLICY_CONFIRM.equals(policy)) {
            return queueForApproval(tool, argsJson, reason, ctx);
        }
        return executeRaw(tool, argsJson, ctx);
    }

    /** 不看策略,直接执行。批准后重放走这里 —— 人已经点了头,不需要再过一遍策略。 */
    public String executeRaw(String name, String argsJson, ToolContext ctx) {
        AiTool tool = tools.get(name);
        if (tool == null) return "没有名为 " + name + " 的工具";
        return executeRaw(tool, argsJson, ctx);
    }

    private String executeRaw(AiTool tool, String argsJson, ToolContext ctx) {
        String name = tool.name();
        long t0 = System.currentTimeMillis();
        int timeout = Math.max(5, tool.timeoutSeconds());
        Future<String> f = pool.submit(() -> tool.execute(argsJson, ctx));
        String result;
        try {
            result = f.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            result = "工具 " + name + " 超过 " + timeout + " 秒未返回,已放弃。可以缩小查询范围后重试。";
            log.warn("工具超时:{} task={}", name, ctx == null ? null : ctx.taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            result = "工具调用被中断";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            result = "工具 " + name + " 执行失败:" + msg;
            log.warn("工具失败:{} -> {}", name, msg);
        } catch (Exception e) {
            result = "工具 " + name + " 执行失败:" + e.getMessage();
        }

        long cost = System.currentTimeMillis() - t0;
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("name", name);
        stat.put("ms", cost);
        stat.put("ok", !result.startsWith("工具 ") || !result.contains("失败"));
        stat.put("chars", result == null ? 0 : result.length());
        lastRoundStats.put(name + "-" + cost, stat);

        if (result == null || result.isBlank()) return "工具没有返回任何内容。";
        return result.length() <= MAX_RESULT_CHARS
                ? result
                : result.substring(0, MAX_RESULT_CHARS) + "\n…(结果过长已截断,请缩小查询范围)";
    }

    /** 落一条待批单,并回给模型一句它读得懂的话(别让它以为失败了反复重试)。 */
    private String queueForApproval(AiTool tool, String argsJson, String reason, ToolContext ctx) {
        String args = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        try {
            if (approvals.existsByToolNameAndArgsJsonAndStatus(tool.name(), args, ToolApproval.PENDING)) {
                return "工具 " + tool.name() + " 需要创始人批准,同样的请求已在待批队列里,不要重复提交。"
                        + "本轮请基于已有信息继续,或说明需要这项能力。";
            }
            ToolApproval a = new ToolApproval(tool.name(), tool.category(), args,
                    reason == null ? "" : reason,
                    ctx == null ? null : ctx.goalId, ctx == null ? null : ctx.taskId,
                    ctx == null ? "" : ctx.employeeName);
            approvals.save(a);
            log.info("工具待批:{} #{} args={}", tool.name(), a.getId(), args);
            return "工具 " + tool.name() + " 属于「需创始人批准」的工具,已提交审批单 #" + a.getId()
                    + ",创始人批准后结果会作为公司动态出现。本轮不要重复提交,继续用其他信息推进。";
        } catch (Exception e) {
            log.warn("待批单写入失败:{}", e.getMessage());
            return "工具 " + tool.name() + " 需要创始人批准,但审批单写入失败:" + e.getMessage();
        }
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    /** 清空本轮的调用统计,每轮任务开始前调一次。 */
    public void resetStats() {
        lastRoundStats.clear();
    }

    /** 本轮工具调用记录,给界面展示。 */
    public List<Map<String, Object>> stats() {
        return new ArrayList<>(lastRoundStats.values());
    }
}
