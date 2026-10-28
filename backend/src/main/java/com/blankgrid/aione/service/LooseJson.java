package com.blankgrid.aione.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 模型输出的 JSON 兜底解析。
 *
 * 大模型不是序列化器。同一段提示词,它这次给你标准 JSON,下次就给你:
 * 包在 ```json 里、前后带一句"好的,以下是拆解"、用全角冒号、「」当引号、
 * 末尾多一个逗号,或者最要命的 —— 写到一半被 max_tokens 截断。
 * 只要其中任意一种发生,整条生产线就停在第一步,创始人只能看着"拆解失败"。
 *
 * 这里按"破坏程度从轻到重"依次尝试:
 *   原样 → 掐头去尾 → 补齐缺失的括号/引号 → 再顺手修全角标点。
 * 能救回来就救,救不回来返回 null,由调用方决定重试还是报错。
 */
public final class LooseJson {

    private LooseJson() {}

    /** 能解析出对象/数组就返回,否则 null。 */
    public static JsonNode parse(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (String candidate : candidates(raw)) {
            try {
                JsonNode n = mapper.readTree(candidate);
                if (n != null && (n.isObject() || n.isArray())) return n;
            } catch (Exception ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    /** 输出是不是明显没写完(右括号/右引号对不上)。用来决定要不要重试。 */
    public static boolean looksTruncated(String raw) {
        String s = clean(raw);
        int l = s.indexOf('{');
        if (l < 0) return true;
        String tail = s.substring(l).trim();
        return !(tail.endsWith("}") || tail.endsWith("]"));
    }

    static List<String> candidates(String raw) {
        List<String> out = new ArrayList<>();
        String s = clean(raw);
        if (s.isBlank()) return out;
        out.add(s);

        int l = s.indexOf('{');
        if (l < 0) l = s.indexOf('[');
        if (l < 0) return out;
        String fromFirst = s.substring(l);
        if (!fromFirst.equals(s)) out.add(fromFirst);

        // 掐头去尾:第一个 { 到最后一个 }(或 ])。模型在 JSON 后面又补一段解说时用得上。
        int r = Math.max(fromFirst.lastIndexOf('}'), fromFirst.lastIndexOf(']'));
        if (r > 0) out.add(fromFirst.substring(0, r + 1));

        // 到这儿上面都失败了,说明 JSON 本身是坏的。先按"内容最多"的方式补:从第一个 { 一直到全文末尾。
        // 截断的情况下最后一个 } 其实是半截对象的收尾,按它切反而会丢条目。
        out.add(balance(fromFirst));
        if (r > 0) {
            String sliced = fromFirst.substring(0, r + 1);
            out.add(balance(sliced));
            out.add(balance(structuralQuotes(sliced)));
        }
        out.add(balance(structuralQuotes(fromFirst)));
        return out;
    }

    /** 去掉代码块围栏与 BOM;围栏标记行整行丢弃。 */
    static String clean(String raw) {
        String s = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 补齐结构:未闭合的字符串补引号,未闭合的括号按栈补右括号,
     * 字符串里裸换行转义,右括号前多余的逗号去掉。
     */
    static String balance(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        Deque<Character> stack = new ArrayDeque<>();
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    sb.append(c);
                    esc = false;
                } else if (c == '\\') {
                    sb.append(c);
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                    sb.append(c);
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c < 0x20) {
                    // 其它控制字符一律丢掉,它们进不了合法 JSON 字符串
                } else {
                    sb.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> { inStr = true; sb.append(c); }
                case '{', '[' -> { stack.push(c); sb.append(c); }
                case '}', ']' -> {
                    if (!stack.isEmpty()) {
                        char open = stack.pop();
                        stripTail(sb);
                        sb.append(open == '{' ? '}' : ']');
                    }
                    // 栈空时的多余右括号直接丢掉
                }
                default -> sb.append(c);
            }
        }
        if (inStr) {
            if (esc) sb.setLength(sb.length() - 1);  // 结尾悬着的反斜杠
            sb.append('"');
        }
        while (!stack.isEmpty()) sb.append(stack.pop() == '{' ? '}' : ']');
        return sb.toString();
    }

    /** 右括号前不许有逗号:去掉尾部的空白与逗号。 */
    private static void stripTail(StringBuilder sb) {
        int i = sb.length();
        while (i > 0 && Character.isWhitespace(sb.charAt(i - 1))) i--;
        if (i > 0 && sb.charAt(i - 1) == ',') i--;
        sb.setLength(i);
    }

    /**
     * 中文标点归位。只动"结构位置"上的全角符号:
     * 「key」: 这种把中文引号当 JSON 引号用的写法,以及 "key"： 这种全角冒号。
     * 正文里的「」保留原样,不然中文内容会被改得面目全非。
     */
    static String structuralQuotes(String s) {
        String t = s.replace('“', '"').replace('”', '"').replace('＂', '"');
        // 「key」:
        t = t.replaceAll("「([^「」]{1,60})」\\s*[:：]", "\"$1\":");
        // : 「value」
        t = t.replaceAll("[:：]\\s*「([^「」]*?)」(?=\\s*[,，}\\]])", ":\"$1\"");
        // 」,「
        t = t.replaceAll("」\\s*[,，]\\s*(?=「)", "\",\"");
        // "key"：
        t = t.replaceAll("\"\\s*：", "\":");
        // 结构位置的全角逗号
        t = t.replaceAll("([}\\]\"])\\s*[,，]\\s*(?=[\"{\\[])", "$1,");
        return t;
    }
}
