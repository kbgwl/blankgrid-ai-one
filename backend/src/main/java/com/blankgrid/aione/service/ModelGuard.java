package com.blankgrid.aione.service;

import java.util.List;
import java.util.Locale;

/**
 * 模型省钱护栏。
 *
 * <p>为什么需要它:模型名散落在服务商配置、员工档案、任务记录、决策槽位四处,
 * 任何一处留了个贵模型(如 deepseek-v4-pro),一次长报告就能烧掉几十次便宜调用的钱,
 * 而账单要等到欠费才被发现。这里在「发请求」这唯一出口统一拦一道,
 * 把所有贵模型名替换成省钱模型,并在响应里如实标记「已降级」。
 *
 * <p>判断按名字做,不依赖服务商白名单 —— 各家命名花样太多,漏一个就是漏钱。
 */
public final class ModelGuard {

    /** 一眼就知道贵的模型名片段(小写匹配)。便宜档(flash/lite/mini/air/tiny)优先排除。 */
    private static final List<String> CHEAP_HINTS = List.of(
            "flash", "lite", "mini", "nano", "air", "tiny", "cheap", "economy"
    );

    private static final List<String> EXPENSIVE_HINTS = List.of(
            "pro", "reasoner", "thinking", "ultra", "opus",
            "o1", "o3", "o4", "gpt-4", "gpt-5", "sonnet",
            "max", "large", "plus", "thinking"
    );

    private ModelGuard() {}

    public static String norm(String model) {
        return model == null ? "" : model.trim();
    }

    /** 这个模型名是不是"贵的那一档"。 */
    public static boolean isExpensive(String model) {
        String m = norm(model).toLowerCase(Locale.ROOT);
        if (m.isEmpty()) return false;
        for (String c : CHEAP_HINTS) {
            if (m.contains(c)) return false;   // gpt-4o-mini / deepseek-v4-flash 先豁免
        }
        for (String e : EXPENSIVE_HINTS) {
            if (m.contains(e)) return true;
        }
        return false;
    }

    /**
     * 拿到真正要用的模型名。
     *
     * @param want   调用方想要的模型(可能为 null/空,表示用服务商默认)
     * @param cheap  省钱模型名(空则无法降级,原样返回)
     * @param enforce 是否开省钱模式
     * @return 实际发给服务商的模型名
     */
    public static String resolve(String want, String cheap, boolean enforce) {
        String w = norm(want);
        String c = norm(cheap);
        // want 为空说明配置本身缺模型名,这时候要保持为空:让上层报「尚未配置默认模型」,
        // 而不是偷偷塞一个别家的模型名过去,变成一条看不懂的 400。
        if (w.isEmpty()) return w;
        if (!enforce || c.isEmpty()) return w;
        return isExpensive(w) ? c : w;
    }

    /** 便于日志/前端展示:true 表示这次调用被降级了。 */
    public static boolean downgraded(String want, String actual) {
        return !norm(want).equalsIgnoreCase(norm(actual));
    }
}
