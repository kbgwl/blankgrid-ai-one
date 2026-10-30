package com.blankgrid.aione.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 交付物正文净化。
 *
 * <p>模型偶尔会把不该出现在交付物里的东西留在正文里:控制符 {@code [[DONE]]}(续写协议的约定)、
 * 推理阶段漏出来的 {@code <summary>}/{@code </thinking>} 标签、整份内容被一对 ``` 包起来。
 * 这些都不是"写得不好",而是**产物被污染**:评审会看到一段莫名其妙的东西并据此扣分,
 * 导出成 Word 时标签会原样印在文档里,创始人得手动擦掉。
 *
 * <p>净化只做确定性清理,不改写语义 —— 不删段落、不调整顺序、不润色措辞。
 * 尤其小心两件事:
 * <ul>
 *   <li>代码块里的内容一律不动。交付物可能就是一段 HTML,{@code <details><summary>} 是合法写法。</li>
 *   <li>文档里出现过 {@code <details} 时整体放行:这份就是 HTML,标签是它的正文。</li>
 * </ul>
 */
public final class OutputSanitizer {

    private OutputSanitizer() {}

    /** 续写协议用的完成标记,不能印在交付物里。 */
    private static final String DONE_MARK = "[[DONE]]";

    /** 模型接话时爱加的过渡语,只在开头出现时剥掉。 */
    private static final String[] LEADING_NOISE = {
            "（接上文）", "(接上文)", "（续）", "(续)", "（继续）", "(继续)",
            "接上文：", "接上文:", "续上：", "续上:", "继续：", "继续:"
    };

    /**
     * 推理标签。只在两种情况下删:整行只有标签,或标签出现在正文里且整份文档不是 HTML。
     * {@code <summary>} 单独出现毫无意义 —— 它是 {@code <details>} 的子元素,
     * 没有 {@code <details>} 就说明是从思考过程里漏出来的。
     */
    private static final String[] LEAK_TAGS = {
            "<summary>", "</summary>", "<thinking>", "</thinking>",
            "<analysis>", "</analysis>", "<scratchpad>", "</scratchpad>",
            "<reasoning>", "</reasoning>"
    };

    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;

        s = s.replace(DONE_MARK, "");
        s = stripLeadingNoise(s);
        s = unwrapWholeFence(s);
        s = stripLeakedTags(s);
        s = collapseBlankLines(s);
        return s.strip();
    }

    /** 去掉开头的「（接上文）」这类话。只处理开头,正文中间出现是正常叙述。 */
    private static String stripLeadingNoise(String s) {
        String t = s.stripLeading();
        for (String noise : LEADING_NOISE) {
            if (t.startsWith(noise)) {
                t = t.substring(noise.length()).stripLeading();
                break;
            }
        }
        return t;
    }

    /**
     * 整份内容被一对 ``` 包着时把它解开。
     *
     * <p>模型很爱把整篇 Markdown 塞进 ```markdown 里。渲染出来是一整块灰底代码,
     * 导出成 Word 更糟 —— 满屏等宽字体。
     *
     * <p>只解"散文语言"的围栏(markdown / text / 无标注)。{@code ```html}、{@code ```java}
     * 这种是交付物本身就是代码,外面那层围栏必须留着:去掉之后代码会按段落渲染,
     * 缩进全丢,复制出来就是坏的。
     */
    static String unwrapWholeFence(String s) {
        String t = s.strip();
        if (!t.startsWith("```")) return s;
        int fences = countOf(t, "```");
        if (fences != 2) return s;
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) return s;
        String lang = t.substring(3, firstNl).strip().toLowerCase();
        if (!lang.isEmpty() && !PROSE_LANGS.contains(lang)) return s;
        // 结尾必须是一行只有 ``` (允许前后空白)
        int lastFence = t.lastIndexOf("```");
        if (lastFence <= firstNl) return s;
        if (!t.substring(lastFence + 3).isBlank()) return s;
        String body = t.substring(firstNl + 1, lastFence);
        return body.isBlank() ? s : body;
    }

    /** 只有这些围栏标注代表"整份是散文",可以解包。 */
    private static final java.util.Set<String> PROSE_LANGS =
            java.util.Set.of("markdown", "md", "text", "txt");

    /**
     * 删掉漏出来的推理标签。
     *
     * <p>按行扫描并跟踪代码围栏状态:围栏内的行原样保留。
     * 整份文档含 {@code <details} 时视为 HTML 交付物,整体放行。
     */
    static String stripLeakedTags(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.contains("<details")) return s;

        String[] lines = s.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inFence = false;
        boolean touched = false;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                out.add(line);
                continue;
            }
            if (inFence) {
                out.add(line);
                continue;
            }
            String replaced = line;
            for (String tag : LEAK_TAGS) {
                if (replaced.contains(tag)) {
                    replaced = replaced.replace(tag, "");
                    touched = true;
                }
            }
            if (touched && replaced.isBlank() && !line.isBlank()) {
                // 整行只有标签:连这行一起丢掉,别留一行空白
                continue;
            }
            out.add(replaced);
        }
        if (!touched) return s;
        return String.join("\n", out);
    }

    /** 连续空行压成一行。模型分段时爱空一大片,导出后是半页白。 */
    static String collapseBlankLines(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int blanks = 0;
        for (String line : s.split("\n", -1)) {
            if (line.isBlank()) {
                blanks++;
                if (blanks > 1) continue;
            } else {
                blanks = 0;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private static int countOf(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
