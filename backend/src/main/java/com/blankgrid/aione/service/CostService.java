package com.blankgrid.aione.service;

import com.blankgrid.aione.repo.TokenLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * token → 钱。
 *
 * <p>创始人看「今日 token 60.9 万」是没法决策的,看「¥4.20」才知道这条生产线贵不贵。
 * 账本里只记总量不区分输入输出,所以按输入:输出 = 3:1 折算,单价取设置里的单价表
 * (没配就用内置参考价)。这是量级估算,不是对账单 —— 界面上也这么写。
 */
@Service
public class CostService {

    private final TokenLogRepository tokenRepo;
    private final SettingsService settings;

    public CostService(TokenLogRepository tokenRepo, SettingsService settings) {
        this.tokenRepo = tokenRepo;
        this.settings = settings;
    }

    /** 把「[model, tokens]」列表折成金额;模型为空的老账按当前主用模型算 */
    public double sum(List<Object[]> rows) {
        double total = 0;
        String fallback = settings.defaultModel();
        for (Object[] row : rows) {
            String model = row[0] == null ? fallback : String.valueOf(row[0]);
            long tokens = row[1] == null ? 0 : ((Number) row[1]).longValue();
            total += settings.costOf(tokens, model);
        }
        return round(total);
    }

    public double total() {
        return sum(tokenRepo.tokensGroupByModel());
    }

    public double today() {
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return sum(tokenRepo.tokensGroupByModelSince(dayStart));
    }

    public double ofGoal(Long goalId) {
        if (goalId == null) return 0;
        return sum(tokenRepo.tokensGroupByModelOfGoal(goalId));
    }

    /** 按模型拆开:今日与累计,前端画一张小表就够 */
    public List<Map<String, Object>> breakdown() {
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Map<String, Long> todayRows = toMap(tokenRepo.tokensGroupByModelSince(dayStart));
        Map<String, Long> allRows = toMap(tokenRepo.tokensGroupByModel());
        String fallback = settings.defaultModel();

        // 老账本的 model 是空的,折算时要落到当前主用模型上;
        // 按「解析后的名字」合并,否则同一个模型会分成两行(一行 null 一行真名)
        Map<String, long[]> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : allRows.entrySet()) {
            String model = resolve(e.getKey(), fallback);
            long[] acc = merged.computeIfAbsent(model, k -> new long[2]);
            acc[0] += e.getValue() == null ? 0 : e.getValue();
        }
        for (Map.Entry<String, Long> e : todayRows.entrySet()) {
            String model = resolve(e.getKey(), fallback);
            long[] acc = merged.computeIfAbsent(model, k -> new long[2]);
            acc[1] += e.getValue() == null ? 0 : e.getValue();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : merged.entrySet()) {
            String model = e.getKey();
            long all = e.getValue()[0];
            long today = e.getValue()[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", model);
            m.put("tokensTotal", all);
            m.put("tokensToday", today);
            m.put("costTotal", round(settings.costOf(all, model)));
            m.put("costToday", round(settings.costOf(today, model)));
            SettingsService.Price p = settings.priceOf(model);
            m.put("priceIn", p.input);
            m.put("priceOut", p.output);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare((Double) b.get("costTotal"), (Double) a.get("costTotal")));
        return out;
    }

    private static String resolve(String model, String fallback) {
        return (model == null || model.isBlank())
                ? (fallback == null || fallback.isBlank() ? "未知模型" : fallback)
                : model;
    }

    /** 单价表(给设置页展示「现在按什么价在算」) */
    public List<Map<String, Object>> prices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettingsService.Price p : settings.effectivePrices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", p.model);
            m.put("input", p.input);
            m.put("output", p.output);
            out.add(m);
        }
        return out;
    }

    private Map<String, Long> toMap(List<Object[]> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0] == null ? null : String.valueOf(r[0]);
            m.put(key, r[1] == null ? 0L : ((Number) r[1]).longValue());
        }
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
