package com.blankgrid.aione.tool;

import com.blankgrid.aione.entity.ToolApproval;
import com.blankgrid.aione.entity.ToolCallLog;
import com.blankgrid.aione.repo.ToolApprovalRepository;
import com.blankgrid.aione.repo.ToolCallLogRepository;
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
    private final ToolCallLogRepository callLogs;

    /** 最近一轮任务里各工具被调用的次数与耗时,给界面显示"它到底干了什么" */
    private final Map<String, Map<String, Object>> lastRoundStats = new ConcurrentHashMap<>();

    public ToolRegistry(List<AiTool> discovered, SettingsService settings,
                        ToolApprovalRepository approvals, ToolCallLogRepository callLogs) {
        this.settings = settings;
        this.approvals = approvals;
        this.callLogs = callLogs;
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
     * 决策层能看到的工具:可用,且没被关掉。
     *
     * <p>早先这里的条件是「只读 + 策略 auto」,理由是 CEO 巡视时人不在场,
     * 给 confirm 工具也只能拿回一句"等批准",白烧一轮上下文。
     *
     * <p>但那个取舍带来一个更糟的后果:<b>CEO 连"我能不能申请"都不知道</b>。
     * 它永远不会说出"这个安装包我要用 REA 拆开看,请批准",因为工具声明里根本没有 rea。
     * 只有岗位在跑任务时撞上 confirm 才会落单 —— 也就是说,需要授权的动作
     * 永远是被动触发的,不是 CEO 判断出来的。这样"自主决策"就只剩"做被预先授权过的事"。
     *
     * <p>现在它能看见全部可用工具,区别落在执行路径上({@link #invokeForDecision}):
     * 只读工具按策略走,有副作用的工具一律只能申请。
     */
    public List<AiTool> decisionTools() {
        List<AiTool> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            if (!t.available()) continue;
            if (SettingsService.POLICY_OFF.equals(policyOf(t))) continue;
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
        return executeRaw(tool, argsJson, ctx, ToolCallLog.SOURCE_TASK);
    }

    /**
     * 决策层专用入口。和 {@link #invoke} 的差别只有一条,但很关键:
     *
     * <p><b>有副作用的工具,不管策略怎么设,决策层都只能申请、不能执行。</b>
     * 否则把某个写操作工具设成 auto,CEO 就能在"拍板前先取证"这个名义下改状态 ——
     * 事后翻记录只会看到"CEO 查了一下",而实际上它已经动了东西。
     *
     * <p>只读工具仍按策略走:auto 直接查,confirm 变成一张待批单。
     */
    public String invokeForDecision(String name, String argsJson, ToolContext ctx, String reason) {
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
        if (!tool.decisionSafe() || SettingsService.POLICY_CONFIRM.equals(policy)) {
            return queueForApproval(tool, argsJson, reason, ctx);
        }
        return executeRaw(tool, argsJson, ctx, ToolCallLog.SOURCE_DECISION);
    }

    /** 不看策略,直接执行。批准后重放走这里 —— 人已经点了头,不需要再过一遍策略。 */
    public String executeRaw(String name, String argsJson, ToolContext ctx) {
        return executeRaw(name, argsJson, ctx, ToolCallLog.SOURCE_APPROVAL);
    }

    public String executeRaw(String name, String argsJson, ToolContext ctx, String source) {
        AiTool tool = tools.get(name);
        if (tool == null) return "没有名为 " + name + " 的工具";
        return executeRaw(tool, argsJson, ctx, source);
    }

    private String executeRaw(AiTool tool, String argsJson, ToolContext ctx, String source) {
        String name = tool.name();
        long t0 = System.currentTimeMillis();
        int timeout = Math.max(5, tool.timeoutSeconds());
        Future<String> f = pool.submit(() -> tool.execute(argsJson, ctx));
        String result;
        boolean ok = true;
        try {
            result = f.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            ok = false;
            result = "工具 " + name + " 超过 " + timeout + " 秒未返回,已放弃。可以缩小查询范围后重试。";
            log.warn("工具超时:{} task={}", name, ctx == null ? null : ctx.taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            ok = false;
            result = "工具调用被中断";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            ok = false;
            result = "工具 " + name + " 执行失败:" + msg;
            log.warn("工具失败:{} -> {}", name, msg);
        } catch (Exception e) {
            ok = false;
            result = "工具 " + name + " 执行失败:" + e.getMessage();
        }
        if (result == null || result.isBlank()) {
            ok = false;
            result = "工具没有返回任何内容。";
        }

        long cost = System.currentTimeMillis() - t0;
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("name", name);
        stat.put("ms", cost);
        stat.put("ok", ok);
        stat.put("chars", result.length());
        lastRoundStats.put(name + "-" + cost, stat);
        audit(tool, argsJson, ctx, source, ok, cost, result);

        if (result.isBlank()) return "工具没有返回任何内容。";
        return result.length() <= MAX_RESULT_CHARS
                ? result
                : result.substring(0, MAX_RESULT_CHARS) + "\n…(结果过长已截断,请缩小查询范围)";
    }

    /**
     * 写一条审计流水。
     *
     * <p>一律吞异常:审计是旁路。要是它写不进去就把一次已经成功的工具调用变成失败,
     * 那等于让记账问题拖垮业务。
     */
    private void audit(AiTool tool, String argsJson, ToolContext ctx, String source,
                       boolean ok, long cost, String result) {
        if (callLogs == null) return;
        try {
            callLogs.save(new ToolCallLog(tool.name(), tool.category(), source,
                    ctx == null ? "" : ctx.employeeName,
                    ctx == null ? null : ctx.goalId,
                    ctx == null ? null : ctx.taskId,
                    clip(argsJson == null ? "" : argsJson, 3900),
                    ok, cost, result.length(), clip(result.replaceAll("\\s+", " "), 480)));
        } catch (Exception e) {
            log.debug("工具审计写入失败:{} {}", tool.name(), e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
