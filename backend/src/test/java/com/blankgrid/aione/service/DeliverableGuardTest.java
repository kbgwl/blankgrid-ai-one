package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拆解粒度闸门:附属台账不该单独立项,成品不该被误杀。
 */
class DeliverableGuardTest {

    @Test
    @DisplayName("实测过的切片台账都判为附属物")
    void attachments() {
        for (String t : List.of("字体层级规格", "色板与间距规范", "图标命名清单",
                "token 用量核算表", "指标口径字典", "接口字段明细表", "命名索引")) {
            assertTrue(DeliverableGuard.isAttachment(t), "应判为附属物:" + t);
        }
    }

    @Test
    @DisplayName("真正的成品不是附属物")
    void products() {
        for (String t : List.of("品牌视觉方案", "官网首页文案", "产品 PRD 文档",
                "竞品分析报告", "落地页 HTML 代码", "运营手册")) {
            assertFalse(DeliverableGuard.isAttachment(t), "不该判为附属物:" + t);
        }
    }

    @Test
    @DisplayName("长标题豁免:把多件事写在一起的复合任务不能被闸门吃掉")
    void longTitleExempt() {
        assertFalse(DeliverableGuard.isAttachment(
                "输出官网全部页面的字段清单并附上每个字段的取值规则与示例数据说明"), "长标题不做附属物判定");
    }

    @Test
    @DisplayName("并进宿主时,标题与要求都保留,不静默丢内容")
    void mergeNoteKeepsContent() {
        String note = DeliverableGuard.mergeNote("字体层级规格", "标题 28/20/16 三档");
        assertTrue(note.contains("字体层级规格"));
        assertTrue(note.contains("标题 28/20/16 三档"));
        assertTrue(note.contains("不需要单独成篇"));
    }

    @Test
    @DisplayName("宿主优先找同一责任人的交付物")
    void hostPrefersSameOwner() {
        List<String[]> local = new ArrayList<>();
        local.add(new String[]{"11", "品牌视觉方案", "7"});
        local.add(new String[]{"12", "官网首页文案", "9"});

        assertEquals("12", DeliverableGuard.host("字体层级规格", local, 9L)[0]);
        assertEquals("11", DeliverableGuard.host("字体层级规格", local, 7L)[0], "同责任人优先");
    }

    @Test
    @DisplayName("没有同责任人时并到本工作流第一项;本工作流空着就不合并")
    void hostFallback() {
        List<String[]> local = new ArrayList<>();
        local.add(new String[]{"11", "品牌视觉方案", "7"});
        assertEquals("11", DeliverableGuard.host("色板规范", local, 99L)[0]);
        assertNull(DeliverableGuard.host("色板规范", new ArrayList<>(), 99L), "没有宿主就别硬吞");
    }

    @Test
    @DisplayName("查重:剥掉「规格/清单/表」尾巴后同名的算同一件事")
    void dedupIgnoresSuffix() {
        assertTrue(DeliverableDedup.same("品牌视觉设计", "品牌视觉设计规格"));
        assertTrue(DeliverableDedup.same("官网首页文案", "官网首页文案清单"));
        assertFalse(DeliverableDedup.same("官网首页文案", "官网配图说明"),
                "不同产出不能被误合并");
    }

    @Test
    @DisplayName("查重的核心词剥离不会把两字词剥没")
    void coreKeepsMinimum() {
        assertEquals("品牌视觉设计", DeliverableDedup.core("品牌视觉设计规格"));
        assertEquals("规格", DeliverableDedup.core("规格"), "剥到只剩两字就停,不能剥成空");
    }
}
