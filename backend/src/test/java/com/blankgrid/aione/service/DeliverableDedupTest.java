package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交付物查重:这些用例全部取自实跑时真实撞到的情况。
 * 目标是「该合并的一定合并,不该合并的一个也别合并」——误合并会让一个部门凭空少一件产出。
 */
class DeliverableDedupTest {

    private static List<String[]> list(String... kv) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) out.add(new String[]{kv[i], kv[i + 1]});
        return out;
    }

    @Test
    @DisplayName("括号里的规格说明不影响同一件事的判定")
    void parenthesisIsIgnored() {
        // 实跑现场:产品部与市场部各派一份,后一份带「(按功能分组)」
        assertTrue(DeliverableDedup.same("产品页主体文案", "产品页主体文案（按功能分组）"));
        assertTrue(DeliverableDedup.same("首屏文案（3 组备选）", "首屏文案"));
    }

    @Test
    @DisplayName("标点、空格、全角半角差异不算不同")
    void punctuationIsIgnored() {
        assertTrue(DeliverableDedup.same("公司介绍，120字以内", "公司介绍 120 字以内"));
        assertTrue(DeliverableDedup.same("Landing Page Hero v2", "landing page hero"));
    }

    @Test
    @DisplayName("只是名字相近的两份不同产出,不能合并")
    void differentDeliverablesAreKeptApart() {
        // 这两个标题都有「产品页」,但一个是正文、一个是总览,合并会让市场部少一件产出
        assertFalse(DeliverableDedup.same("产品页主体文案", "产品页功能总览"));
        assertFalse(DeliverableDedup.same("首屏主标题", "首屏副标题"));
        assertFalse(DeliverableDedup.same("竞品定价分析", "竞品渠道分析"));
    }

    @Test
    @DisplayName("太短的标题不参与判定,免得「文案」把一切都吞了")
    void veryShortTitlesAreNotMatched() {
        assertFalse(DeliverableDedup.same("文案", "配图"));
        assertFalse(DeliverableDedup.same("清单", "文案"));
    }

    @Test
    @DisplayName("findDuplicate 返回命中的已有交付物 id 与原标题")
    void findDuplicateReturnsExisting() {
        List<String[]> existing = list(
                "11", "产品页主体文案（按功能分组）",
                "12", "结尾行动指引");
        String[] hit = DeliverableDedup.findDuplicate("产品页主体文案", existing);
        assertNotNull(hit);
        assertEquals("11", hit[0], "要返回被合并到的那个 id,依赖才能接得上");
        assertEquals("产品页主体文案（按功能分组）", hit[1]);
    }

    @Test
    @DisplayName("没有重复、或清单为空时返回 null")
    void findDuplicateNull() {
        assertNull(DeliverableDedup.findDuplicate("公司介绍", list("11", "首屏文案")));
        assertNull(DeliverableDedup.findDuplicate("公司介绍", List.of()));
        assertNull(DeliverableDedup.findDuplicate("公司介绍", null));
    }

    @Test
    @DisplayName("空标题一律不判定,避免把无标题项互相合并")
    void blankTitlesNeverMatch() {
        assertFalse(DeliverableDedup.same("", "产品页主体文案"));
        assertFalse(DeliverableDedup.same(null, null));
        assertNull(DeliverableDedup.findDuplicate("", list("11", "产品页主体文案")));
    }

    @Test
    @DisplayName("归一化只留实词:去掉括号、标点、空白")
    void normStripsNoise() {
        assertEquals("产品页主体文案", DeliverableDedup.norm("产品页主体文案（按功能分组）"));
        assertEquals("abc123", DeliverableDedup.norm("ABC 123!"));
    }
}
