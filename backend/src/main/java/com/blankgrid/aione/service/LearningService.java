package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.repo.EmployeeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位成长档案的唯一写入口。
 *
 * <p>AI 员工的价值不在"这次写完了",而在"下次不会犯同一个错"。这件事只能靠把教训写回去 ——
 * 每次执行前注入岗位自己的经验,是最便宜的一次性投入(几百字,不到一分钱)。
 *
 * <p>但写入口必须收敛,否则档案会烂成流水账:条数无限长、同一句重复十遍、
 * 上下文窗口被它吃掉。三条规则:
 * <ol>
 *   <li><b>只留结论</b>:一条一句话,超过 200 字砍掉 —— 档案是给模型看的提示,不是复盘原文。</li>
 *   <li><b>去重按前缀</b>:新教训前 40 字和已有条目重合就视为同一条(模型表述会飘,但开头通常一致)。</li>
 *   <li><b>新的在前</b>:最近的错误最相关,条数满了先丢最老的。</li>
 * </ol>
 */
@Service
public class LearningService {

    /** 最多留几条。10 条已经覆盖一个岗位反复踩的坑,再多就会稀释注意力。 */
    static final int MAX_ITEMS = 10;
    /** 单条上限。一句话讲不清的教训,模型也执行不了。 */
    static final int ITEM_CHARS = 200;
    /** 总字数上限,对齐 Employee.learnings 的列宽(6000),留出余量。 */
    static final int TOTAL_CHARS = 5600;
    /** 去重时比对的前缀长度。 */
    static final int DEDUP_PREFIX = 40;

    private final EmployeeRepository empRepo;

    public LearningService(EmployeeRepository empRepo) {
        this.empRepo = empRepo;
    }

    /**
     * 记一条教训。
     *
     * @param emp    这个岗位
     * @param source 来源标签(评审 / 复盘 / 人工),会显示在条目前面,便于创始人分辨
     * @param text   教训正文,内部会压缩成一行
     * @return 实际写入后的全文;被判定为重复或空内容时返回原文
     */
    public String add(Employee emp, String source, String text) {
        if (emp == null) return "";
        String merged = merge(emp.getLearnings(), source, text);
        if (!merged.equals(emp.getLearnings() == null ? "" : emp.getLearnings())) {
            emp.setLearnings(merged);
            empRepo.save(emp);
        }
        return merged;
    }

    /** 纯函数版本,便于测试:不碰数据库,只算合并结果。 */
    static String merge(String existing, String source, String text) {
        String line = oneLine(text);
        if (line.length() < 6) return existing == null ? "" : existing;

        String cur = existing == null ? "" : existing;
        String prefix = line.length() > DEDUP_PREFIX ? line.substring(0, DEDUP_PREFIX) : line;
        for (String old : cur.split("\n")) {
            if (old.isBlank()) continue;
            String body = bodyOf(old);
            // 两个方向都判:新句子里含老句子,或老句子里含新句子。
            // 只比一个方向会漏掉最常见的情况 —— 同一个坑,这次多说了一句,
            // 开头一样但结尾更长,结果被当成新教训又记一遍。
            String oldPrefix = body.length() > DEDUP_PREFIX ? body.substring(0, DEDUP_PREFIX) : body;
            if (body.contains(prefix) || line.contains(oldPrefix)) return cur;
        }

        List<String> lines = new ArrayList<>();
        String tag = (source == null || source.isBlank()) ? "" : "[" + source + "] ";
        lines.add("- " + tag + line);
        for (String old : cur.split("\n")) {
            if (old.isBlank()) continue;
            if (lines.size() >= MAX_ITEMS) break;
            lines.add(old.strip());
        }
        return trimTo(String.join("\n", lines), TOTAL_CHARS);
    }

    /**
     * 取出条目正文:去掉行首的「- 」和「[来源] 」。
     *
     * <p>判重必须比正文,不能比整行 —— 否则同一条教训换个来源标签(评审 / 复盘)
     * 就被当成两件事各记一遍,档案很快被同名条目塞满。
     */
    static String bodyOf(String line) {
        String t = line == null ? "" : line.strip();
        if (t.startsWith("- ")) t = t.substring(2).strip();
        if (t.startsWith("[")) {
            int end = t.indexOf(']');
            if (end > 0 && end < 12) t = t.substring(end + 1).strip();
        }
        return t;
    }

    /** 把多行文本压成一句:换行(尤其列表项)在档案里没有意义,只会让注入的提示变长。 */    static String oneLine(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").strip();
        // 去掉模型爱加的前缀,保留结论本身
        while (s.startsWith("-") || s.startsWith("*") || s.startsWith("·")) {
            s = s.substring(1).strip();
        }
        if (s.length() > ITEM_CHARS) {
            s = s.substring(0, ITEM_CHARS - 1) + "…";
        }
        return s;
    }

    /**
     * 超长时从尾部整行截断。
     *
     * <p>不能直接 {@code substring(0, max)} —— 那会把最后一条经验切一半,
     * 注入提示时看起来像模型在说胡话。宁可少一条,也不要留半句。
     */
    static String trimTo(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        List<String> kept = new ArrayList<>();
        int len = 0;
        for (String line : s.split("\n")) {
            if (len + line.length() + 1 > max) break;
            kept.add(line);
            len += line.length() + 1;
        }
        return kept.isEmpty() ? s.substring(0, max) : String.join("\n", kept);
    }
}
