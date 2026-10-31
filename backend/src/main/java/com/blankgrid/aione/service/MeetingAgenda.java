package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.MeetingSegment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 议程覆盖检查:开会前写的议题,现在聊到第几条了。
 *
 * <p><b>为什么值得单独做。</b>议程是开会前写的,会一开起来就没人再看它。
 * 最常见的散场方式是"时间到了,还有两件没聊",而这两件往往是最需要拍板的那两件。
 * 原来界面上只有一句"议程 3 条 —— 收尾前留 5 分钟逐条对一下",这等于什么都没说:
 * 谁都知道要逐条对,问题是哪一条。
 *
 * <p><b>怎么判"聊到了"。</b>没有分词器、也不想为这事引一个。用 2-gram:
 * 把议题切成相邻两字,滤掉带虚词的,只要有一个落在实录里就算提到了。
 * 这个判定是**故意偏宽松**的 —— 假阴性的代价是界面多一条"还没聊到"的提示,
 * 人自己看一眼就否了;假阳性(把真没聊的判成聊了)的代价是那件事根本没人再想起。
 * 所以宁可多说一句。
 *
 * <p>同义词换不掉("华东渠道"和"上海那边的分销"在规则眼里是两件事),
 * 这是规则的天花板,不是 bug。真正需要按语义对的时候是会后,那是模型的活。
 */
public final class MeetingAgenda {

    /** 一条议题:原文、是否被提到、被哪个词命中的(界面可以标出来给人看) */
    public record Item(int index, String text, boolean covered, String hit) {}

    /** 行首的序号/项目符号:"1." "1、" "(1)" "①" "-" "•" */
    private static final java.util.regex.Pattern BULLET = java.util.regex.Pattern.compile(
            "^\\s*(?:\\(?\\d{1,2}\\s*[\\.\\)、）:：]|[①-⑳]|[-*•·])\\s*");

    /**
     * 虚字。2-gram 里只要含一个就丢掉 —— 否则"为什么""怎么""这个"会让
     * 每条议题都命中一切,覆盖检查就成了永远 100%。
     */
    private static final String STOP = "的了着过是在有无不没和与及或对把被为这那有也很就都而且但如果所以因为"
            + "一些个么什怎多少你我他她它们上下里来到去说做要会能可还请让给从向把"
            + "吗呢吧啊呀哦嗯好对行是得地";

    private MeetingAgenda() {}

    /**
     * 把一段议程拆成条目。
     *
     * <p>顺序:先按行拆;如果没有一行带序号、但整段里有分号,再按分号拆
     * —— 有人习惯把三件事写成一行用"；"隔开,那样按行拆会得到一条巨型议题。
     */
    public static List<String> split(String agenda) {
        List<String> out = new ArrayList<>();
        String a = agenda == null ? "" : agenda.trim();
        if (a.isEmpty()) return out;

        List<String> lines = new ArrayList<>();
        for (String line : a.split("\\r?\\n")) {
            if (line != null && !line.isBlank()) lines.add(line.trim());
        }

        boolean numbered = lines.stream().anyMatch(l -> BULLET.matcher(l).find());
        List<String> raw = new ArrayList<>();
        if (!numbered && lines.size() == 1 && (a.contains("；") || a.contains(";"))) {
            for (String part : a.split("[；;]")) {
                if (part != null && !part.isBlank()) raw.add(part.trim());
            }
        } else {
            raw.addAll(lines);
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String s : raw) {
            String t = BULLET.matcher(s).replaceFirst("").trim();
            t = t.replaceAll("[。，,；;]+$", "").trim();
            if (t.length() < 2) continue;
            if (t.length() > 200) t = t.substring(0, 200);
            if (seen.add(t)) out.add(t);
        }
        return out;
    }

    /**
     * 逐条检查覆盖情况。
     *
     * <p>系统标记("会议暂停""会议结束")不参与判定 —— 它们不是人说的话,
     * 拿它们当证据等于自己给自己作证。
     */
    public static List<Item> check(String agenda, List<MeetingSegment> segs) {
        StringBuilder sb = new StringBuilder();
        if (segs != null) {
            for (MeetingSegment s : segs) {
                if (MeetingSegment.SYSTEM.equals(s.getKind())) continue;
                if (s.getText() != null) sb.append(s.getText()).append('\n');
            }
        }
        return checkText(agenda, sb.toString());
    }

    /** 同上,但直接吃拼好的正文 —— 规则调试和测试时省掉造假实体的功夫 */
    public static List<Item> checkText(String agenda, String transcript) {
        List<String> items = split(agenda);
        List<Item> out = new ArrayList<>();
        if (items.isEmpty()) return out;

        String hay = (transcript == null ? "" : transcript).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");

        for (int i = 0; i < items.size(); i++) {
            String text = items.get(i);
            String hit = null;
            List<String> grams = grams(text);
            if (hay.isEmpty()) {
                // 还没开始记,谈不上"没聊到"
                out.add(new Item(i + 1, text, false, null));
                continue;
            }
            if (grams.isEmpty()) {
                // 整条都是虚词,规则判不了;按"已经聊到"处理,别制造假警报
                out.add(new Item(i + 1, text, true, null));
                continue;
            }
            for (String g : grams) {
                if (hay.contains(g)) { hit = g; break; }
            }
            out.add(new Item(i + 1, text, hit != null, hit));
        }
        return out;
    }

    /** 相邻两字,去掉含虚字的 */
    static List<String> grams(String text) {
        List<String> out = new ArrayList<>();
        String t = text == null ? "" : text.replaceAll("[\\s\\p{Punct}，。、；：？！“”‘’（）《》【】]", "");
        for (int i = 0; i + 2 <= t.length(); i++) {
            String g = t.substring(i, i + 2);
            boolean bad = false;
            for (int k = 0; k < 2; k++) {
                if (STOP.indexOf(g.charAt(k)) >= 0) { bad = true; break; }
            }
            if (!bad && !out.contains(g)) out.add(g);
        }
        return out;
    }

    /** 还没聊到的条目原文,给主持人提示用 */
    public static List<String> uncovered(List<Item> items) {
        List<String> out = new ArrayList<>();
        if (items == null) return out;
        for (Item it : items) {
            if (!it.covered()) out.add(it.text());
        }
        return out;
    }
}
