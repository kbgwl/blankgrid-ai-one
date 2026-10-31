package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Meeting;
import com.blankgrid.aione.entity.MeetingSegment;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会议的两种"成品"里,由本地确定性地生成的那一种:原话实录。
 *
 * <p><b>原话版不经过模型,这是刻意的。</b>会议纪要的价值在于"准确知道我答应过什么",
 * 一份被润色过的"原话"比没有更糟 —— 它会让人以为对方真的这么说过。
 * 所以这里只做三件不改语义的事:排序、去重、补上时间戳与说话人。
 *
 * <p>另一个用途是给总结版兜底:模型挂了、余额没了、上游 503,
 * 会议照样要有一份能看的东西 —— {@link #fallbackSummary} 用规则把信号压成一页。
 */
public final class MeetingTranscript {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MeetingTranscript() {}

    /** 毫秒 → 01:23:45。超过一小时的会不能显示成 83:45 */
    public static String stamp(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    /**
     * 有效时长。
     *
     * <p>不能只看时钟:导入的转写稿带着 3 分钟的时间戳,但落库只花了一秒 ——
     * 界面上就会显示"开了 0 分钟,12 条发言",模型也会在纪要里写一句
     * "时长与实录不符,需核实"。谁的记录更长就以谁为准。
     */
    public static long effectiveSeconds(Meeting m, List<MeetingSegment> segs) {
        long base = m == null ? 0 : m.elapsedSeconds();
        if (segs == null || segs.isEmpty()) return base;
        long last = 0;
        for (MeetingSegment s : segs) last = Math.max(last, s.getAtMs());
        return Math.max(base, last / 1000);
    }

    public static String localTime(Instant at) {
        return at == null ? "" : FULL.withZone(ZoneId.systemDefault()).format(at);
    }

    /**
     * 原话实录(马克down)。标题、基本信息、逐条发言。
     *
     * <p>说话人为空时标成「未标注」而不是把两个人的话接在一起 ——
     * 接在一起之后就再也分不开谁说的了。
     */
    public static String verbatim(Meeting m, List<MeetingSegment> segs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(nz(m.getTitle())).append(" · 原话实录\n\n");
        sb.append("- 时间:").append(localTime(m.getStartedAt()));
        if (m.getEndedAt() != null) {
            sb.append(" – ").append(HM.withZone(ZoneId.systemDefault()).format(m.getEndedAt()));
        }
        long minutes = effectiveSeconds(m, segs) / 60;
        if (minutes > 0) sb.append("(有效 ").append(minutes).append(" 分钟)");
        sb.append("\n");
        if (notBlank(m.getLocation())) sb.append("- 地点:").append(m.getLocation()).append("\n");
        if (notBlank(m.getParticipants())) sb.append("- 参会:").append(m.getParticipants()).append("\n");
        sb.append("- 来源:").append(sourceName(m.getSource())).append("\n");
        sb.append("- 条目:").append(segs == null ? 0 : segs.size()).append(" 条\n\n");
        sb.append("> 本文件按时间顺序保留每一条发言的原始措辞,只做了分段与标点整理,"
                + "未改写、未删减、未归纳。需要结论请看总结版。\n\n");

        if (segs == null || segs.isEmpty()) {
            sb.append("(本次会议没有记录到发言)\n");
            return sb.toString();
        }
        long lastAt = -1;
        for (MeetingSegment s : segs) {
            // 同一分钟内的连续发言不重复打时间,读起来才不像日志
            String head = s.getAtMs() / 60000 == lastAt / 60000 ? "" : "[" + stamp(s.getAtMs()) + "] ";
            lastAt = s.getAtMs();
            sb.append("**").append(head)
              .append(s.getSpeaker() == null || s.getSpeaker().isBlank() ? "未标注" : s.getSpeaker());
            if (MeetingSegment.NOTE.equals(s.getKind())) sb.append("(速记)");
            if (s.isMarked()) sb.append(" ★");
            sb.append("**\n");
            sb.append(nz(s.getText())).append("\n\n");
        }
        return sb.toString();
    }

    /** 转录成给模型的纯文本:时间戳 + 说话人 + 原话,不要 markdown 干扰 */
    public static String plain(List<MeetingSegment> segs) {
        StringBuilder sb = new StringBuilder();
        if (segs == null) return "";
        for (MeetingSegment s : segs) {
            // 系统插入的「会议暂停/继续」是给时间轴看的,模型不需要,留着只会让它总结出"会议中断"
            if (MeetingSegment.SYSTEM.equals(s.getKind())) continue;
            sb.append("[").append(stamp(s.getAtMs())).append("] ")
              .append(s.getSpeaker() == null || s.getSpeaker().isBlank() ? "未标注" : s.getSpeaker())
              .append(":").append(nz(s.getText())).append("\n");
        }
        return sb.toString();
    }

    /**
     * 超长会议分块。两小时的会正文能到三四万字,一次性塞给模型要么超上限、
     * 要么模型只看开头结尾。切成块先各自压要点,再合成一版。
     */
    public static List<String> chunks(List<MeetingSegment> segs, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        if (segs == null) return out;
        for (MeetingSegment s : segs) {
            String line = "[" + stamp(s.getAtMs()) + "] "
                    + (s.getSpeaker() == null || s.getSpeaker().isBlank() ? "未标注" : s.getSpeaker())
                    + ":" + nz(s.getText()) + "\n";
            if (cur.length() + line.length() > maxChars && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * 规则版纪要:模型没跑成时的兜底。
     *
     * <p>它不是"次一等的总结",而是另一件东西:一页能照着开会的话。
     * 所以明确写清"这是速记版、没有经过归纳",而不是冒充一份完整纪要。
     */
    public static String fallbackSummary(Meeting m, List<MeetingSegment> segs, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(nz(m.getTitle())).append("\n\n");
        sb.append("> 这一版是**规则速记**,不是模型归纳的纪要 —— 原因:")
          .append(notBlank(reason) ? reason : "模型暂时不可用").append("。\n")
          .append("> 它只做了分类摘录,没有判断哪句更重要。人工补一句结论,比等模型恢复快。\n\n");

        sb.append("## 会议信息\n\n");
        sb.append("- 时间:").append(localTime(m.getStartedAt()));
        if (m.getEndedAt() != null) {
            sb.append(" – ").append(HM.withZone(ZoneId.systemDefault()).format(m.getEndedAt()));
        }
        sb.append("\n");
        if (notBlank(m.getLocation())) sb.append("- 地点:").append(m.getLocation()).append("\n");
        if (notBlank(m.getParticipants())) sb.append("- 参会:").append(m.getParticipants()).append("\n");
        sb.append("- 有效时长:").append(effectiveSeconds(m, segs) / 60).append(" 分钟 / ")
          .append(segs == null ? 0 : segs.size()).append(" 条发言\n");

        List<Map<String, Object>> hits = MeetingSignals.collect(segs);
        appendHits(sb, "已定的事(原句照录)", hits, MeetingSignals.DECISION);
        appendHits(sb, "像待办的话(原句照录)", hits, MeetingSignals.ACTION);
        appendHits(sb, "风险与分歧(原句照录)", hits, MeetingSignals.RISK);
        appendHits(sb, "悬而未决", hits, MeetingSignals.OPEN);

        List<MeetingSignals.SpeakerStat> stats = MeetingSignals.speakerStats(segs);
        if (!stats.isEmpty()) {
            sb.append("## 谁说了多少\n\n");
            for (MeetingSignals.SpeakerStat s : stats) {
                sb.append("- ").append(s.speaker()).append(":").append(s.turns()).append(" 次 / ")
                  .append(s.chars()).append(" 字\n");
            }
            sb.append("\n");
        }
        if (notBlank(m.getAgenda())) {
            sb.append("## 议题(会议前写的,请人工对照)\n\n").append(m.getAgenda()).append("\n");
        }
        return sb.toString();
    }

    private static void appendHits(StringBuilder sb, String title, List<Map<String, Object>> hits, String kind) {
        List<Map<String, Object>> picked = hits.stream().filter(h -> kind.equals(h.get("kind"))).toList();
        if (picked.isEmpty()) return;
        sb.append("## ").append(title).append("\n\n");
        for (Map<String, Object> h : picked) {
            sb.append("- [").append(stamp(((Number) h.get("atMs")).longValue())).append("] ")
              .append(h.get("speaker")).append(":「").append(h.get("quote")).append("」\n");
        }
        sb.append("\n");
    }

    /** "开了多久"的人话:73 分钟 / 1 小时 13 分钟 */
    public static String humanDuration(long seconds) {
        if (seconds <= 0) return "0 分钟";
        long h = seconds / 3600;
        long min = (seconds % 3600) / 60;
        if (h == 0) return (min == 0 ? "不到 1" : min + "") + " 分钟";
        return h + " 小时" + (min == 0 ? "" : " " + min + " 分钟");
    }

    public static String sourceName(String src) {
        if (Meeting.SRC_BROWSER.equals(src)) return "浏览器实时转写";
        if (Meeting.SRC_IMPORT.equals(src)) return "转写稿导入";
        if (Meeting.SRC_PUSH.equals(src)) return "外部 ASR 推送";
        return "手工速记";
    }

    // ==================== 转写稿解析 ====================

    /** 一行解析结果。atMs < 0 表示这一行没带时间戳 */
    public record Line(String speaker, String text, long atMs) {}

    /** [00:01:02] 张三: 内容 / 00:01:02 张三：内容 / 张三：内容 */
    private static final java.util.regex.Pattern LINE_WITH_SPEAKER = java.util.regex.Pattern.compile(
            "^\\s*(?:\\[?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\]?\\s*)?(.{1,20}?)\\s*[:：]\\s*(.+)$");

    private static final java.util.regex.Pattern LINE_WITH_STAMP = java.util.regex.Pattern.compile(
            "^\\s*\\[?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\]?\\s+(.+)$");

    /**
     * 有些"说话人"其实是半句话(「关于预算：我们需要 30 万」)。
     * 这类前缀里带虚词的一律不当人,整行算内容 —— 宁可"未标注",也不要把
     * 一句话的前半截当成一个人的名字记一辈子。
     */
    private static final String[] NOT_A_NAME = {
            "我们", "你们", "他们", "关于", "如果", "因为", "所以", "这个", "那个",
            "需要", "问题", "结论", "备注", "说明", "注意", "另外", "然后", "但是"
    };

    /**
     * 把粘贴进来的转写稿拆成行。
     *
     * <p>识别三种写法(Tencent Meeting / 飞书妙记 / 通义听悟导出的格式各不相同),
     * 认不出的一律整行当内容。不智能识别"谁在说" —— 猜错的代价比"未标注"大得多。
     */
    public static List<Line> parseLines(String transcript) {
        List<Line> out = new ArrayList<>();
        if (transcript == null) return out;
        for (String raw : transcript.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            java.util.regex.Matcher m = LINE_WITH_SPEAKER.matcher(s);
            if (m.matches() && looksLikeName(m.group(2))) {
                out.add(new Line(m.group(2).trim(), m.group(3).trim(), parseStamp(m.group(1))));
                continue;
            }
            java.util.regex.Matcher t = LINE_WITH_STAMP.matcher(s);
            if (t.matches()) {
                out.add(new Line("", t.group(2).trim(), parseStamp(t.group(1))));
                continue;
            }
            out.add(new Line("", s, -1));
        }
        return out;
    }

    private static boolean looksLikeName(String candidate) {
        if (candidate == null) return false;
        String c = candidate.trim();
        if (c.isEmpty() || c.length() > 12) return false;
        if (c.chars().anyMatch(Character::isWhitespace)) return false;
        for (String bad : NOT_A_NAME) {
            if (c.contains(bad)) return false;
        }
        // 「张三说」这种把动词也算进名字了
        return !c.endsWith("说") && !c.endsWith("问") && !c.endsWith("答");
    }

    /** 01:02 → 1 分 02 秒;1:02:03 → 1 小时 02 分 03 秒 */
    static long parseStamp(String s) {
        if (s == null || s.isBlank()) return -1;
        String[] parts = s.trim().split(":");
        try {
            if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2])) * 1000;
            }
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            }
        } catch (NumberFormatException ignore) { /* 落到 -1 */ }
        return -1;
    }

    public static String nz(String s) { return s == null ? "" : s; }
    public static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** 会议实际跨度(不是有效时长):用于给行程排"会议日期" */
    public static Instant anchor(Meeting m) {
        if (m.getStartedAt() != null) return m.getStartedAt();
        if (m.getScheduledAt() != null) return m.getScheduledAt();
        return m.getCreatedAt() == null ? Instant.now() : m.getCreatedAt();
    }

    public static long minutesBetween(Instant a, Instant b) {
        return a == null || b == null ? 0 : Math.max(0, Duration.between(a, b).toMinutes());
    }
}
