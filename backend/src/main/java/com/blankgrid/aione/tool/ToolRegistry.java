package com.blankgrid.aione.tool;

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
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 单次工具结果最多回给模型多少字符。超了截断 —— 工具返回一屏 JSON 是烧 token 的重灾区。 */
    static final int MAX_RESULT_CHARS = 6000;

    /** 单个工具调用的墙钟上限。外部接口卡住不能把整条生产线拖死。 */
    static final int TIMEOUT_SECONDS = 40;

    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tool-exec");
        t.setDaemon(true);
        return t;
    });

    /** 最近一轮任务里各工具被调用的次数与耗时,给界面显示"它到底干了什么" */
    private final Map<String, Map<String, Object>> lastRoundStats = new ConcurrentHashMap<>();

    public ToolRegistry(List<AiTool> discovered) {
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

    /** 全部工具,含不可用的,设置页用来告诉用户"差什么才能启用"。 */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("category", t.category());
            m.put("available", t.available());
            m.put("reason", t.unavailableReason());
            out.add(m);
        }
        return out;
    }

    /**
     * 执行一次工具调用。
     *
     * <p>这个方法<b>永不抛异常</b>。工具失败是 agent 循环里的正常分支 ——
     * 模型拿到"搜索接口返回 403,可能是 Key 没权限"之后可以换个关键词或换平台,
     * 而一次异常冒到外层就等于整条任务失败,前面烧的 token 全白费。
     */
    public String invoke(String name, String argsJson, ToolContext ctx) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            return "没有名为 " + name + " 的工具。可用工具:" + String.join("、", names());
        }
        if (!tool.available()) {
            return "工具 " + name + " 当前不可用:" + tool.unavailableReason() + "。请改用其他方式或直接说明缺少该能力。";
        }

        long t0 = System.currentTimeMillis();
        Future<String> f = pool.submit(() -> tool.execute(argsJson, ctx));
        String result;
        try {
            result = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            result = "工具 " + name + " 超过 " + TIMEOUT_SECONDS + " 秒未返回,已放弃。可以缩小查询范围后重试。";
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
