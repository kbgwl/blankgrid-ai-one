package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 会议进行中的信号提取:决议、待办、风险、数字、时间点、悬而未决。
 *
 * <p><b>为什么不用模型逐句判断。</b>一场两小时的会有一两千条发言,
 * 每条都过一次模型:贵、慢(来不及在"正在说"的时候显示)、而且同一句话
 * 昨天判成待办、今天判成闲聊 —— 复盘时对不上账。所以实时这一层是纯规则、
 * 纯函数:同样的输入永远同样的输出,可以写测试钉住。
 *
 * <p>模型只在会后出场,做那件规则真做不了的事:把两百句"我觉得"压成一段结论。
 * 两条线各干各的,不互相冒充。
 *
 * <p>规则会误判 —— "他说下周给"里没有主语,不知道是谁给。所以每条信号都带
 * {@link Hit#quote 原句},界面上是"这句话像待办",不是"这条待办",由人一眼定夺。
 */
public final class MeetingSignals {

    public static final String DECISION = "DECISION";
    public static final String ACTION = "ACTION";
    public static final String RISK = "RISK";
    public static final String OPEN = "OPEN";
    public static final String TIME = "TIME";
    public static final String NUMBER = "NUMBER";

    /** 一条命中:类别 + 判定依据 + 原句(截断到能看全的长度) */
    public record Hit(String kind, String why, String quote) {}

    /** 一个人的发言统计。会开完最该看的就是这张表 */
    public record SpeakerStat(String speaker, int turns, int chars) {}

    private MeetingSignals() {}

    /** 关键词表。顺序就是优先级:一句话里既有"定了"又有"安排",它是决议不是待办。 */
    private static final Map<String, String[]> RULES = new LinkedHashMap<>();

    static {
        RULES.put(DECISION, new String[]{
                "就这么定", "定下来", "定了", "拍板", "敲定", "决定", "一致同意", "同意",
                "通过", "就按", "结论是", "确认下来", "最终确定", "不再讨论"
        });
        RULES.put(ACTION, new String[]{
                "我来", "我这边", "你去", "让他", "负责", "跟进", "落实", "对接", "整理",
                "发一份", "出一版", "排一下", "准备", "会后", "尽快", "明天给", "下周给",
                "安排一下", "盯一下"
        });
        RULES.put(RISK, new String[]{
                "风险", "担心", "来不及", "不确定", "有问题", "卡在", "隐患", "冲突",
                "做不完", "缺人", "缺口", "延期", "超预算"
        });
        RULES.put(OPEN, new String[]{
                "待确认", "再商量", "回头再说", "还没定", "看情况", "后面再说",
                "需要确认", "再对一下", "先放着", "待定"
        });
    }

    private static final java.util.regex.Pattern TIME_PATTERN = java.util.regex.Pattern.compile(
            "今天|明天|后天|大后天|本周|这周|下周|下下周|下个月|月底|月初|年底|"
                    + "周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]|"
                    + "\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]|\\d{1,2}\\s*[日号]|"
                    + "上午|下午|中午|晚上|早上|凌晨|"
                    + "\\d{1,2}\\s*[:：]\\s*\\d{2}|\\d{1,2}\\s*点(半|\\d{1,2}\\s*分)?");

    private static final java.util.regex.Pattern NUMBER_PATTERN = java.util.regex.Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:万|亿|元|块|%|％|天|人|份|个|条|台|次|小时|分钟|公里|km|G|T|页|篇)");

    /** 句末问句:没有问号的"能不能"也算 */
    private static final java.util.regex.Pattern ASK_PATTERN = java.util.regex.Pattern.compile(
            "吗|呢|是不是|能不能|要不要|行不行|可不可以|怎么|为什么|多少");

    private static final int QUOTE_MAX = 90;
    /** 一句太短的多半是"嗯""对"这类应答,提不出来也不该提 */
    private static final int MIN_LEN = 4;

    /**
     * 扫一段话,返回其中的信号。
     *
     * <p>一句话只出一条:同时命中"待办"和"时间"的句子,当前用不到两条 ——
     * 一条带原句的待办,人自己看得见里面的"下周三"。
     */
    public static List<Hit> scan(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("[。！？!?；;，,\\n\\r]+")) {
            String s = raw.trim();
            if (s.length() < MIN_LEN) continue;
            Hit h = classify(s);
            if (h != null) out.add(h);
        }
        return out;
    }

    private static Hit classify(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : RULES.entrySet()) {
            for (String kw : e.getValue()) {
                if (s.contains(kw)) {
                    // DECISION 里的"同意"太常见(「我同意先这样」也算),要求句子里没有明显的不确定
                    if (DECISION.equals(e.getKey()) && s.contains("不同意")) continue;
                    return new Hit(e.getKey(), kw, clip(s));
                }
            }
        }
        if (ASK_PATTERN.matcher(s).find() && (s.endsWith("?") || s.endsWith("？") || s.contains("是不是")
                || s.contains("能不能") || s.contains("要不要"))) {
            return new Hit(OPEN, "问句", clip(s));
        }
        if (TIME_PATTERN.matcher(s).find() && NUMBER_PATTERN.matcher(s).find()) {
            return new Hit(NUMBER, "带单位数量", clip(s));
        }
        if (TIME_PATTERN.matcher(s).find()) {
            return new Hit(TIME, "时间表达", clip(s));
        }
        if (NUMBER_PATTERN.matcher(s).find()) {
            return new Hit(NUMBER, "带单位数量", clip(s));
        }
        return null;
    }

    private static String clip(String s) {
        return s.length() <= QUOTE_MAX ? s : s.substring(0, QUOTE_MAX) + "…";
    }

    /**
     * 整场会的信号。同一条原句只出一次(批量导入的稿子常有重复行),
     * 按发言顺序返回,界面上就是"越往下越新"。
     */
    public static List<Map<String, Object>> collect(List<MeetingSegment> segs) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (segs == null) return out;
        for (MeetingSegment seg : segs) {
            // 人工速记不是原话,但"他记下了什么"本身是信号,照扫
            for (Hit h : scan(seg.getText())) {
                String key = h.kind() + "|" + h.quote();
                if (!seen.add(key)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", h.kind());
                m.put("why", h.why());
                m.put("quote", h.quote());
                m.put("speaker", seg.getSpeaker());
                m.put("atMs", seg.getAtMs());
                m.put("segmentId", seg.getId());
                out.add(m);
            }
        }
        return out;
    }

    /** 参会人发言统计:谁在说、说了多少,一眼看出"这会是三个人开的还是一个人在讲" */
    public static List<SpeakerStat> speakerStats(List<MeetingSegment> segs) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        if (segs != null) {
            for (MeetingSegment s : segs) {
                if (MeetingSegment.SYSTEM.equals(s.getKind())) continue;
                String who = s.getSpeaker() == null || s.getSpeaker().isBlank() ? "未标注" : s.getSpeaker();
                int[] v = acc.computeIfAbsent(who, k -> new int[2]);
                v[0]++;
                v[1] += s.getText() == null ? 0 : s.getText().length();
            }
        }
        List<SpeakerStat> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new SpeakerStat(k, v[0], v[1])));
        out.sort((a, b) -> Integer.compare(b.chars(), a.chars()));
        return out;
    }

    /**
     * 主持人提示:开会时该被提醒、但人在会里往往顾不上看的东西。
     *
     * <p>{@code now} 显式传进来,不在方法里取当前时间 —— 否则这几条规则没法写测试。
     */
    public static List<String> insights(Meeting m, List<MeetingSegment> segs, Instant now) {
        List<String> out = new ArrayList<>();
        if (m == null || segs == null) return out;
        List<Map<String, Object>> hits = collect(segs);
        long actions = hits.stream().filter(h -> ACTION.equals(h.get("kind"))).count();
        long decisions = hits.stream().filter(h -> DECISION.equals(h.get("kind"))).count();
        long opens = hits.stream().filter(h -> OPEN.equals(h.get("kind"))).count();
        long minutes = MeetingTranscript.effectiveSeconds(m, segs) / 60;

        // 冷场:最后一条发言距今多久。断线、去拿材料、还是真的没人说话,只有现场的人知道
        if (Meeting.LIVE.equals(m.getStatus()) && !segs.isEmpty() && now != null) {
            MeetingSegment last = segs.get(segs.size() - 1);
            Instant lastAt = last.getAt() == null ? now : last.getAt();
            long quiet = Math.max(0, Duration.between(lastAt, now).getSeconds());
            if (quiet >= 300) {
                out.add("已经 " + (quiet / 60) + " 分钟没人出声了 —— 是断线了,还是都在看材料?");
            }
        }

        // 一言堂:占比过半且不止一个人在会里
        List<SpeakerStat> stats = speakerStats(segs);
        int totalChars = stats.stream().mapToInt(SpeakerStat::chars).sum();
        if (stats.size() >= 3 && totalChars > 400) {
            SpeakerStat top = stats.get(0);
            int pct = Math.round(top.chars() * 100f / totalChars);
            if (pct >= 60) {
                out.add(top.speaker() + " 说了 " + pct + "% 的话 —— 其他人可能没机会把意见说出来");
            }
        }

        // 有活没主:待办多但同期决议少,通常意味着"聊完了但没拍"
        if (actions >= 3 && decisions == 0) {
            out.add("已经 " + actions + " 处像待办的说法,但一条决议都没有 —— 这些活最后谁认?");
        }
        if (opens >= 2) {
            out.add(opens + " 处「待确认/再商量」还挂着,散会后就没人再提了");
        }
        if (minutes >= 60 && decisions == 0) {
            out.add("开了 " + minutes + " 分钟,还没有任何一条可执行的决定");
        }
        // 议程是开会前写的,会一开起来就没人再看了。
        // 有用的话不是"记得逐条对一下"(谁都知道),而是"哪一条到现在还没人提"。
        List<MeetingAgenda.Item> agenda = MeetingAgenda.check(m.getAgenda(), segs);
        List<String> miss = MeetingAgenda.uncovered(agenda);
        if (!segs.isEmpty() && !agenda.isEmpty() && !miss.isEmpty()) {
            String list = miss.size() <= 2
                    ? "「" + String.join("」「", miss) + "」"
                    : "其中 " + miss.size() + " 条";
            if (minutes >= 30) {
                out.add("开了 " + minutes + " 分钟,议程还有 " + miss.size() + " 条没听到提到" + list
                        + " —— 收尾前留几分钟过一遍,没提到的散会后基本就没人再提了");
            } else {
                out.add("议程还有 " + miss.size() + " 条没听到提到" + list
                        + " —— 是还没轮到,还是这次打算跳过?");
            }
        }
        return out;
    }
}
