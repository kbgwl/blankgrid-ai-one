package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 续写的难点不在"再问一次模型",在接缝。
 *
 * <p>模型被要求接着写时,几乎一定会把上一段最后一句重抄一遍再往下走。
 * 不去重,交付物正中间就会出现一整段重复 —— 评审看到的是"模型在凑字数",
 * 而这个印象比内容本身缺一块更致命。
 */
class TaskContinueTest {

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    @Test
    @DisplayName("没有重叠:原样追加")
    void appendsCleanly() {
        StringBuilder all = new StringBuilder("第一段内容到此结束。");
        int added = TaskService.appendWithOverlap(all, "第二段从此开始。");
        assertEquals("第一段内容到此结束。第二段从此开始。", all.toString());
        assertEquals("第二段从此开始。".length(), added);
    }

    @Test
    @DisplayName("接缝整段被重抄:重复的那截要剪掉,只留新增部分")
    void cutsFullyRepeatedSeam() {
        String head = repeat("甲", 300) + "结尾这句会被重抄。";
        String seam = head.substring(head.length() - 160);
        StringBuilder all = new StringBuilder(head);

        int added = TaskService.appendWithOverlap(all, seam + "真正的新内容。");

        assertEquals(head + "真正的新内容。", all.toString());
        assertEquals("真正的新内容。".length(), added);
    }

    @Test
    @DisplayName("只重抄了一小段开头:按最长公共接缝剪掉")
    void cutsPartialOverlap() {
        String head = "这是一段已经写好的正文,结尾是这句话。";
        StringBuilder all = new StringBuilder(head);

        // 模型从中间某句接上,重复了 20 字
        String repeated = "结尾是这句话。后面才是新的。";
        int added = TaskService.appendWithOverlap(all, repeated);

        assertEquals("这是一段已经写好的正文,结尾是这句话。后面才是新的。", all.toString());
        assertEquals("后面才是新的。".length(), added);
    }

    @Test
    @DisplayName("续写带了「（接上文）」这类废话:要剥掉,不能印在交付物里")
    void stripsNoisePrefix() {
        StringBuilder all = new StringBuilder("正文第一部分。");
        TaskService.appendWithOverlap(all, "（接上文）正文第二部分。");
        assertEquals("正文第一部分。正文第二部分。", all.toString());
    }

    @Test
    @DisplayName("续写内容与已有内容完全重复:判定为无新增,循环必须能停下来")
    void detectsNoProgress() {
        String head = repeat("乙", 200);
        StringBuilder all = new StringBuilder(head);

        int added = TaskService.appendWithOverlap(all, head);

        assertEquals(0, added, "完全重复应当返回 0,否则续写循环会空转到段数上限");
        assertEquals(head, all.toString(), "原文不能被改坏");
    }

    @Test
    @DisplayName("空续写:不改变原文,返回 0")
    void emptyAddition() {
        StringBuilder all = new StringBuilder("原文。");
        assertEquals(0, TaskService.appendWithOverlap(all, ""));
        assertEquals(0, TaskService.appendWithOverlap(all, null));
        assertEquals("原文。", all.toString());
    }

    @Test
    @DisplayName("极短正文(比接缝窗口还短)也不能出错")
    void veryShortBase() {
        StringBuilder all = new StringBuilder("短。");
        int added = TaskService.appendWithOverlap(all, "短。后面。");
        assertEquals("短。后面。", all.toString());
        assertTrue(added > 0);
    }

    @Test
    @DisplayName("tail:取尾部,不超长就原样返回")
    void tailWorks() {
        assertEquals("abc", TaskService.tail("abc", 10));
        assertEquals("cdef", TaskService.tail("abcdef", 4));
        assertEquals("", TaskService.tail(null, 4));
        assertEquals("", TaskService.tail("", 4));
    }
}
