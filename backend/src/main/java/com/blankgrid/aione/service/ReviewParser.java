package com.blankgrid.aione.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评审输出的解析。
 *
 * <p>单独拎出来,是因为它回答一个必须回答的问题:<b>这次是"成果不行",还是"评审没说清楚"?</b>
 *
 * <p>原来的实现两者不分:评审返回空、被输出上限截断、或者干脆在讲客套话,
 * 解析都得到 {@code score=0, pass=false} —— 于是一条本来合格的成果被记成 0 分,
 * 扣掉一次返工轮次,几轮之后彻底 BLOCKED,备注只剩一句「评审未通过(0 分)」。
 * 实测两个卡死的交付物就是这么来的:执行了 4 轮和 7 轮,分数全是 0,
 * 而评审正文一个字都没有。
 *
 * <p>所以这里多返回一个 {@code usable}:结论不可用时,上层不该把它当成评分,
 * 而该重排一次评审(不扣轮次),连试几次都不行才算"评审环节坏了"。
 */
public final class ReviewParser {

    /** @param usable 这份输出能不能当评审结论用 */
    public record Result(int score, boolean pass, String summary, String notes,
                         JsonNode needsInput, boolean usable) {}

    private static final Pattern SCORE = Pattern.compile("\"?score\"?\\s*[:：]\\s*(\\d{1,3})");
    private static final Pattern PASS = Pattern.compile("\"?pass\"?\\s*[:：]\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private ReviewParser() {}

    public static Result parse(ObjectMapper mapper, String raw) {
        String text = raw == null ? "" : raw.trim();
        // 空输出是最常见的坏情况:模型被限流、被截断,任务层却把它当"成功"
        if (text.isEmpty()) {
            return new Result(0, false, "评审没有返回任何内容", "", null, false);
        }
        try {
            String json = extractJson(text);
            // 截断的输出里可能连一个完整的 {} 都没有,extractJson 会退回 "{}" ——
            // 那不是"空评审",是"坏掉的评审",要交给下面的文本兜底去抠分数
            if ("{}".equals(json) && !"{}".equals(text)) throw new IllegalArgumentException("没有完整的对象");
            JsonNode n = mapper.readTree(json);
            if (n == null || n.isMissingNode() || !n.isObject()) throw new IllegalArgumentException("不是对象");
            boolean hasScore = n.has("score");
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
            // 解析成功但没有 score 字段:这不是一份评审,只是碰巧是 JSON
            boolean usable = hasScore;
            return new Result(score, pass, usable ? summary : "评审输出里没有分数",
                    usable ? notes.toString() : text, n.path("needsInput"), usable);
        } catch (Exception e) {
            Matcher sm = SCORE.matcher(text);
            boolean hasScore = sm.find();
            int score = hasScore ? Math.max(0, Math.min(100, Integer.parseInt(sm.group(1)))) : 0;
            Matcher pm = PASS.matcher(text);
            boolean pass = pm.find() && Boolean.parseBoolean(pm.group(1).toLowerCase(Locale.ROOT));
            if (hasScore) {
                // 能从坏格式里抠出分数:当它能用,备注保留原文,别把信息丢了
                return new Result(score, pass, "评审输出未按 JSON 返回(已从文本中取分)",
                        clip(text, 3000), null, true);
            }
            return new Result(0, false, "评审输出无法识别", clip(text, 3000), null, false);
        }
    }

    static String extractJson(String s) {
        if (s == null) return "{}";
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        return (l >= 0 && r > l) ? s.substring(l, r + 1) : "{}";
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
