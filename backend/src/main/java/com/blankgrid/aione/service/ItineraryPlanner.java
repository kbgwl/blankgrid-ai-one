package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.ItineraryItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型吐的行程 JSON 翻成实体。
 *
 * <p>这一层的存在理由是"模型给的时间不能直接信"。三种常见输出:
 * <ol>
 *   <li>只给日期不给时刻(「11月3日对一下」)—— 排成 09:00 但标 {@code timeUnknown},
 *       界面上显示"11 月 3 日(时间待定)",而不是让人以为约在早上九点。</li>
 *   <li>给相对时间(「下周三」)—— 提示词里要求以会议日期为基准换算,
 *       但仍会出现算错的;换算结果落在会议之前就退回"时间待定"。</li>
 *   <li>压根没时间(「回头对一下」)—— 时间留空,排在列表最后。</li>
 * </ol>
 *
 * <p>宁可显示"待定",也不能显示一个自信的错时间:人会照着它安排别的事。
 */
public final class ItineraryPlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Pattern DATE_ONLY = Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$");
    private static final Pattern DATE_TIME_LOOSE =
            Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T]+(\\d{1,2})[:：](\\d{2})");
    private static final Pattern MONTH_DAY = Pattern.compile("^(\\d{1,2})[-/月](\\d{1,2})[日号]?$");

    private static final Set<String> KINDS = Set.of(
            ItineraryItem.MEETING, ItineraryItem.TRAVEL, ItineraryItem.PREP,
            ItineraryItem.CALL, ItineraryItem.DEADLINE, ItineraryItem.OTHER);

    /** 模型偶尔会返回几十条近乎重复的待办,这里兜一个上限 */
    private static final int MAX_ITEMS = 40;

    private ItineraryPlanner() {}

    /** 解析结果:条目 + 模型原始 JSON 是否被修复过/是否全空 */
    public record Parsed(List<ItineraryItem> items, int dropped) {}

    public static Parsed parse(String rawJson, Instant anchor, ZoneId zone) {
        List<ItineraryItem> items = new ArrayList<>();
        int dropped = 0;
        if (rawJson == null || rawJson.isBlank()) return new Parsed(items, 0);
        JsonNode root;
        try {
            root = LooseJson.parse(MAPPER, rawJson);
        } catch (Exception e) {
            return new Parsed(items, 0);
        }
        if (root == null || root.isMissingNode() || root.isNull()) return new Parsed(items, 0);
        JsonNode arr = root.isArray() ? root : root.path("items");
        if (!arr.isArray()) return new Parsed(items, 0);

        LocalDate baseDate = LocalDate.ofInstant(anchor == null ? Instant.now() : anchor, zone);
        LocalDate meetingDate = baseDate;
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode n : arr) {
            String title = text(n, "title");
            if (title.isBlank()) { dropped++; continue; }
            if (items.size() >= MAX_ITEMS) { dropped++; continue; }

            String startRaw = text(n, "startAt");
            String endRaw = text(n, "endAt");
            LocalDateTime start = parseTime(startRaw, meetingDate);
            boolean precise = hasClock(startRaw);
            LocalDateTime end = parseTime(endRaw, meetingDate);

            ItineraryItem it = new ItineraryItem();
            it.setTitle(clip(title, 200));
            it.setKind(normalizeKind(text(n, "kind"), title));
            it.setLocation(clip(text(n, "location"), 200));
            it.setParticipants(clip(text(n, "participants"), 300));
            it.setPrepare(clip(text(n, "prepare"), 800));
            it.setSourceQuote(clip(text(n, "sourceQuote"), 500));

            if (start != null) {
                // 换算错的相对时间(排到了会议之前)不收:那多半是把"上周"当成"下周"了
                if (end == null) end = start.plusMinutes(defaultMinutes(it.getKind()));
                if (end.isBefore(start)) end = start.plusMinutes(defaultMinutes(it.getKind()));
                it.setStartAt(start.atZone(zone).toInstant());
                it.setEndAt(end.atZone(zone).toInstant());
                it.setTimeUnknown(!precise);
            } else {
                it.setTimeUnknown(true);
                if (hasClock(startRaw) || !startRaw.isBlank()) dropped++;
            }

            String key = it.getTitle() + "|" + (it.getStartAt() == null ? "x" : it.getStartAt());
            if (!seen.add(key)) { dropped++; continue; }
            items.add(it);
        }
        items.sort((a, b) -> {
            if (a.getStartAt() == null && b.getStartAt() == null) return 0;
            if (a.getStartAt() == null) return 1;
            if (b.getStartAt() == null) return -1;
            return a.getStartAt().compareTo(b.getStartAt());
        });
        return new Parsed(items, dropped);
    }

    private static int defaultMinutes(String kind) {
        return switch (kind) {
            case ItineraryItem.MEETING -> 60;
            case ItineraryItem.CALL -> 30;
            case ItineraryItem.TRAVEL -> 120;
            case ItineraryItem.PREP -> 90;
            default -> 30;
        };
    }

    /** 模型最爱在时间前面加一堆修饰,先剥干净再解析 */
    static LocalDateTime parseTime(String raw, LocalDate baseDate) {
        if (raw == null) return null;
        String s = raw.trim().replace("Z", "").replace("＋", "+");
        if (s.isEmpty()) return null;
        // 带时区的 ISO:按偏移换算成本地时间
        try {
            if (raw.contains("+") || raw.endsWith("Z")) {
                return ZonedDateTime.parse(raw).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            }
        } catch (Exception ignore) { /* 继续按无时区解析 */ }

        Matcher dt = DATE_TIME_LOOSE.matcher(s);
        if (dt.find()) {
            try {
                return LocalDateTime.of(Integer.parseInt(dt.group(1)), Integer.parseInt(dt.group(2)),
                        Integer.parseInt(dt.group(3)), Integer.parseInt(dt.group(4)),
                        Integer.parseInt(dt.group(5)));
            } catch (Exception ignore) { /* 落到下面 */ }
        }
        Matcher d = DATE_ONLY.matcher(s);
        if (d.find()) {
            return LocalDate.of(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)),
                    Integer.parseInt(d.group(3))).atTime(LocalTime.of(9, 0));
        }
        Matcher md = MONTH_DAY.matcher(s);
        if (md.find()) {
            // 只给月日:年份跟会议同年;如果因此排到了会议之前,算明年
            int month = Integer.parseInt(md.group(1));
            int day = Integer.parseInt(md.group(2));
            try {
                LocalDate date = LocalDate.of(baseDate.getYear(), month, day);
                if (date.isBefore(baseDate.minusDays(1))) date = date.plusYears(1);
                return date.atTime(9, 0);
            } catch (Exception ignore) { /* 2 月 30 日这种 */ }
        }
        try {
            return LocalDateTime.parse(s, ISO_LOCAL);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 只有日期没有时分 —— 这类要标"时间待定" */
    static boolean hasClock(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.contains(":")) return true;
        return s.length() > 10 && s.contains("T");
    }

    static String normalizeKind(String kind, String title) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (KINDS.contains(k)) return k;
        String t = title == null ? "" : title;
        if (t.contains("出差") || t.contains("高铁") || t.contains("航班") || t.contains("机场")) return ItineraryItem.TRAVEL;
        if (t.contains("准备") || t.contains("整理") || t.contains("材料") || t.contains("方案")) return ItineraryItem.PREP;
        if (t.contains("电话") || t.contains("沟通") || t.contains("对齐") || t.contains("回访")) return ItineraryItem.CALL;
        if (t.contains("截止") || t.contains("提交") || t.contains("deadline")) return ItineraryItem.DEADLINE;
        return ItineraryItem.MEETING;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return "";
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode x : v) {
                if (sb.length() > 0) sb.append("、");
                sb.append(x.asText(""));
            }
            return sb.toString().trim();
        }
        return v.asText("").trim();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 行程 → Markdown(下载/导出用,顺序按时间) */
    public static String toMarkdown(String title, List<ItineraryItem> items, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append(" · 行程\n\n");
        if (items == null || items.isEmpty()) {
            sb.append("(没有从会议内容里提取到需要安排的事)\n");
            return sb.toString();
        }
        LocalDate lastDate = null;
        boolean undated = false;
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        for (ItineraryItem it : items) {
            if (it.getStartAt() != null) {
                LocalDate d = LocalDate.ofInstant(it.getStartAt(), zone);
                if (!d.equals(lastDate)) {
                    sb.append("## ").append(d).append("\n\n");
                    lastDate = d;
                    undated = false;
                }
                sb.append("- **").append(hm.withZone(zone).format(it.getStartAt()));
                if (it.getEndAt() != null) sb.append("–").append(hm.withZone(zone).format(it.getEndAt()));
                sb.append("** ").append(it.getTitle());
                if (it.isTimeUnknown()) sb.append("(时间待定)");
            } else {
                if (!undated) {
                    sb.append("## 时间待定\n\n");
                    undated = true;
                    lastDate = null;
                }
                sb.append("- ").append(it.getTitle());
            }
            sb.append("\n");
            if (MeetingTranscript.notBlank(it.getLocation())) sb.append("  - 地点:").append(it.getLocation()).append("\n");
            if (MeetingTranscript.notBlank(it.getParticipants())) sb.append("  - 参与:").append(it.getParticipants()).append("\n");
            if (MeetingTranscript.notBlank(it.getPrepare())) sb.append("  - 准备:").append(it.getPrepare()).append("\n");
            if (MeetingTranscript.notBlank(it.getSourceQuote())) sb.append("  - 原话:「").append(it.getSourceQuote()).append("」\n");
        }
        return sb.toString();
    }
}
