package com.blankgrid.aione.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 采购单上的数字算错是事故,所以这个工具必须准。 */
class CalcToolTest {

    private String calc(String expr) throws Exception {
        return new CalcTool().execute("{\"expression\":\"" + expr + "\"}", ToolContext.empty());
    }

    @Test
    @DisplayName("单价换算总价:一箱 5 包 89.9 元,买 100 包")
    void unitPriceTimesQuantity() throws Exception {
        // 89.9/5 = 17.98 一包,×100 = 1798
        assertTrue(calc("89.9/5*100").contains("1798"), calc("89.9/5*100"));
    }

    @Test
    @DisplayName("折扣与百分比:1200 打 87 折")
    void percentage() throws Exception {
        assertTrue(calc("1200*0.87").contains("1044"));
        // 3% 的税
        assertTrue(calc("1200*(1-3%)").contains("1164"));
    }

    @Test
    @DisplayName("浮点陷阱:0.1+0.2 必须精确等于 0.3,不能出现 0.30000000000000004")
    void noFloatingPointGarbage() throws Exception {
        String r = calc("0.1+0.2");
        assertTrue(r.contains("0.3"), r);
        assertFalse(r.contains("0000000004"), r);
    }

    @Test
    @DisplayName("括号优先级与一元负号")
    void precedence() throws Exception {
        assertTrue(calc("(10+20)*3").contains("90"));
        assertTrue(calc("-5+8").contains("3"));
    }

    @Test
    @DisplayName("除零与非法表达式要给人话,不能抛异常炸掉任务")
    void badInput() throws Exception {
        assertTrue(calc("1/0").contains("除数为 0"));
        assertTrue(calc("1+*2").contains("无法计算"));
        assertTrue(new CalcTool().execute("{}", ToolContext.empty()).contains("缺少 expression"));
    }
}
