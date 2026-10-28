package com.blankgrid.aione.service;

import java.util.List;
import java.util.Locale;

/**
 * 拆解粒度闸门:拦「附属物冒充交付物」。
 *
 * <p>实测问题:负责人会把同一件产出拆成一层层台账 ——
 * 「品牌视觉方案」之外再来「字体层级规格」「色板与间距规范」「图标命名清单」,
 * 「产品方案」之外再来「token 用量核算表」「指标口径字典」。
 * 这些内容本身没错,但它们不是独立可验收的成品,单独立案的结果是:
 * 每一条都要派活、执行、评审、返工,烧掉整轮预算,而创始人拿到的还是同一件东西被切碎成几片。
 *
 * <p>判定是确定性的,不靠模型自觉:
 * 标题里有附属物词(规格/规范/台账/清单/核算/口径/字典/明细/对照表…)
 * 且不含成品词(文案/方案/报告/页面/设计/代码/手册…)
 * 且标题不长 —— 三条同时成立才算附属物。
 *
 * <p>命中后不直接扔掉:把标题与要求并进同一责任人(或本工作流第一项)的交付物 brief 里,
 * 内容仍在,只是不再单独占用一条生产线。
 */
final class DeliverableGuard {

    /** 附属物特征词:出现其一,可能是台账而不是成品 */
    private static final List<String> ATTACH = List.of(
            "规格", "规范", "台账", "清单", "明细", "对照表", "核算", "口径", "字典", "索引",
            "汇总表", "汇总", "记录表", "检查表", "自检", "备注表", "编号表", "checklist", "index"
    );

    /** 成品特征词:出现其一就是成品,不再当附属物 */
    private static final List<String> PRODUCT = List.of(
            "文案", "方案", "报告", "页面", "官网", "设计", "代码", "脚本", "演示", "手册", "指南",
            "提案", "策略", "落地", "白皮书", "说明书", "计划书", "演讲稿", "教程", "课程"
    );

    /** 标题超过这个长度,多半是「把 A 和 B 一起做成 C」的复合任务,不该被闸门误杀 */
    private static final int MAX_ATTACH_LEN = 18;

    private DeliverableGuard() {
    }

    /**
     * @return 命中的附属物词;不是附属物返回 null
     */
    static String attachWord(String title) {
        if (title == null) return null;
        String t = title.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return null;
        String norm = DeliverableDedup.norm(title);
        if (norm.length() > MAX_ATTACH_LEN) return null;
        for (String p : PRODUCT) {
            if (t.contains(p)) return null;
        }
        for (String a : ATTACH) {
            if (t.contains(a)) return a;
        }
        return null;
    }

    static boolean isAttachment(String title) {
        return attachWord(title) != null;
    }

    /** 并入目标的挂载文案:写进宿主交付物的 brief,内容不丢 */
    static String mergeNote(String title, String brief) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[附带要求] 同时给出「").append(nz(title)).append("」");
        if (brief != null && !brief.isBlank()) sb.append(":").append(brief.trim());
        sb.append("。它可以作为本交付物的一个章节或附表出现,不需要单独成篇。");
        return sb.toString();
    }

    /**
     * 给附属物找宿主:同一责任人的另一项最合适,否则本工作流已有的第一项。
     * @param existing 每项 [id, title, ownerId];ownerId 可以是 null 字符串
     * @param ownerId  当前附属物想派给的人
     * @return 宿主的 [id, title];找不到返回 null(那就保留,不硬吞)
     */
    static String[] host(String title, List<String[]> existing, Long ownerId) {
        if (existing == null || existing.isEmpty()) return null;
        if (ownerId != null) {
            for (String[] e : existing) {
                if (e == null || e.length < 3) continue;
                if (ownerId.toString().equals(e[2])) return e;
            }
        }
        // 没有同责任人就并到本工作流第一项;跨工作流不合并,避免把设计的东西塞进文案里
        return existing.get(0);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
