package com.blankgrid.aione.service;

import java.util.List;
import java.util.Locale;

/**
 * 交付物查重:同一件事不要派给两个部门。
 *
 * <p>背景:CEO 会把同一份产出同时派给两个部门(实测「产品页主体文案」被产品部和市场部各派一份),
 * 两份验收标准还不一样,后到的那份往往更严、更容易卡住,并把它下游的交付物一起拖进等待。
 * 重复开工既烧 token 又互相挡住,所以在部门拆活落库前先做一道确定性查重。
 *
 * <p>判定只用标题,不看正文:标题是唯一稳定信号,正文此时还没生成。
 * 判定偏保守——宁可漏合并,也不要把「产品页主体文案」和「产品页配图说明」这类不同产出误合并。
 */
final class DeliverableDedup {

    /** 太短的标题(如「文案」「清单」)不具备区分度,不参与判定 */
    private static final int MIN_LEN = 4;
    /** 一方完全包含另一方时,较短一方至少要有这么长才算同一件事 */
    private static final int CONTAIN_MIN_LEN = 6;
    /** 二元组覆盖率达到此值即视为同一件事 */
    private static final double COVER_RATIO = 0.75;

    private DeliverableDedup() {
    }

    /**
     * 归一化:去掉括号里的补充说明(「(按功能分组)」「（不超过 60 字）」)、
     * 标点、空白与全角符号,只留汉字、字母、数字。
     * 括号内容大多是规格说明,不是产出本体;不剥掉会让同一件事看起来不一样。
     */
    static String norm(String title) {
        if (title == null) return "";
        String s = title.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", "");
        s = s.replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");
        return s;
    }

    /** 两个标题是不是同一件事。 */
    static boolean same(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        if (x.length() < MIN_LEN || y.length() < MIN_LEN) return false;

        // 一方完整出现在另一方里:「产品页主体文案」⊂「产品页主体文案按功能分组」
        if (x.contains(y) && y.length() >= CONTAIN_MIN_LEN) return true;
        if (y.contains(x) && x.length() >= CONTAIN_MIN_LEN) return true;

        return coverage(x, y) >= COVER_RATIO;
    }

    /** 较短一方有多少比例的相邻二字组出现在较长一方里。 */
    private static double coverage(String a, String b) {
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() < 2) return 0;
        int hit = 0;
        int total = shorter.length() - 1;
        for (int i = 0; i < total; i++) {
            if (longer.contains(shorter.substring(i, i + 2))) hit++;
        }
        return (double) hit / total;
    }

    /**
     * 在已有交付物里找重复项。
     *
     * @param title    待判定标题
     * @param existing 每项为 [id, title]
     * @return 命中的 [id, title];没有重复返回 null
     */
    static String[] findDuplicate(String title, List<String[]> existing) {
        if (existing == null || existing.isEmpty()) return null;
        for (String[] e : existing) {
            if (e == null || e.length < 2) continue;
            if (same(title, e[1])) return e;
        }
        return null;
    }
}
