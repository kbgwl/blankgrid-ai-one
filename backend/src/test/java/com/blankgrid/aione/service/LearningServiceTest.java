package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 岗位成长档案的写入规则。
 *
 * <p>档案会被拼进每次执行的系统提示,所以它有两条硬约束:
 * 不能无限变长(吃上下文),不能重复(模型会以为那件事特别重要)。
 */
class LearningServiceTest {

    @Test
    @DisplayName("第一条教训写进空档案,带来源标签")
    void addsFirstItem() {
        String out = LearningService.merge("", "评审", "缺少真实销量数据,结论只有定性描述");
        assertTrue(out.startsWith("- [评审] "), out);
        assertTrue(out.contains("缺少真实销量数据"), out);
    }

    @Test
    @DisplayName("同一条教训不会重复记:按前 40 字判重")
    void dedupesByPrefix() {
        String first = LearningService.merge("", "评审", "缺少真实销量数据,结论只有定性描述");
        String again = LearningService.merge(first, "评审", "缺少真实销量数据,结论只有定性描述,下次注意");
        assertEquals(first, again, "同一个坑只该记一次");
    }

    @Test
    @DisplayName("新教训排在最前:最近的错误最相关")
    void newestFirst() {
        String a = LearningService.merge("", "评审", "结构缺少验收标准一节");
        String b = LearningService.merge(a, "复盘", "报价单没写不包含什么");
        String[] lines = b.split("\n");
        assertTrue(lines[0].contains("报价单"), b);
        assertTrue(lines[1].contains("验收标准"), b);
    }

    @Test
    @DisplayName("超过条数上限时丢最老的,不无限长")
    void capsItems() {
        String acc = "";
        for (int i = 0; i < LearningService.MAX_ITEMS + 5; i++) {
            acc = LearningService.merge(acc, "评审", "第 " + i + " 类问题:某个具体环节总是漏掉");
        }
        assertEquals(LearningService.MAX_ITEMS, acc.split("\n").length, acc);
        assertTrue(acc.contains("第 " + (LearningService.MAX_ITEMS + 4) + " 类"), "最新的必须在:" + acc);
        assertFalse(acc.contains("第 0 类"), "最老的必须被挤掉:" + acc);
    }

    @Test
    @DisplayName("多行教训压成一句:列表项在档案里没有意义,只会把提示撑长")
    void collapsesMultiline() {
        String out = LearningService.merge("", "复盘", "1) 没核对型号\n2) 价格没写含税");
        assertEquals(1, out.split("\n").length, out);
        assertTrue(out.contains("没核对型号") && out.contains("价格没写含税"), out);
    }

    @Test
    @DisplayName("单条超长会截断,不会把档案撑爆")
    void capsItemLength() {
        String longText = "问题".repeat(300);
        String out = LearningService.merge("", "评审", longText);
        String body = out.substring(out.indexOf("] ") + 2);
        assertTrue(body.length() <= LearningService.ITEM_CHARS, "单条长度 " + body.length());
        assertTrue(body.endsWith("…"), out);
    }

    @Test
    @DisplayName("太短的内容不记:一句话都说不清的,记了也没用")
    void ignoresTooShort() {
        assertEquals("", LearningService.merge("", "评审", "不行"));
        assertEquals("", LearningService.merge("", "评审", "  "));
    }

    @Test
    @DisplayName("总字数超限时按整行截断,不留半句话")
    void trimsWholeLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("- 第").append(i).append("条:").append("很长的教训描述".repeat(3)).append("\n");
        String out = LearningService.trimTo(sb.toString(), 500);
        assertTrue(out.length() <= 500, "长度 " + out.length());
        for (String line : out.split("\n")) {
            assertTrue(line.endsWith("很长的教训描述") || line.isBlank(), "被切半句了:" + line);
        }
    }

    @Test
    @DisplayName("从评审意见里挑第一条:剥掉序号,跳过空行")
    void firstIssuesPicksTopItem() {
        assertEquals("缺少真实价格数据,只有区间估算",
                CompanyEngineService.firstIssues("\n1. 缺少真实价格数据,只有区间估算\n2. 结构不完整"));
        assertEquals("报价单没有写清不包含什么",
                CompanyEngineService.firstIssues("（1）报价单没有写清不包含什么"));
        assertEquals("", CompanyEngineService.firstIssues(null));
        assertEquals("", CompanyEngineService.firstIssues("   "));
    }

    @Test
    @DisplayName("小标题、引导语不算教训:实测评审第一行就是「【必须修改 · 逐条】」")
    void firstIssuesSkipsHeaders() {
        String notes = "【必须修改 · 逐条】\n1. 验收②模块顺序不通过:设计说明与 HTML 实际顺序不一致\n2. 颜色对比度自相矛盾";
        String got = CompanyEngineService.firstIssues(notes);
        assertTrue(got.contains("模块顺序"), got);
        assertFalse(got.contains("必须修改"), "把标题记成教训了:" + got);

        assertEquals("缺少真实销量数据", CompanyEngineService.firstIssues("具体问题如下：\n缺少真实销量数据"));
    }
}
