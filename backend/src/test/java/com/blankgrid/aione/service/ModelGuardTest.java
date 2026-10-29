package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 省钱护栏:贵模型必须被换成便宜模型,便宜模型不能被误伤。
 * 这条规则直接决定账单,写错了就是真金白银,所以单独测。
 */
class ModelGuardTest {

    @Test
    @DisplayName("pro / reasoner / thinking 一律降级到省钱模型")
    void expensiveGetsDowngraded() {
        String cheap = "deepseek-flash";
        assertEquals(cheap, ModelGuard.resolve("deepseek-v4-pro", cheap, true));
        assertEquals(cheap, ModelGuard.resolve("deepseek-reasoner", cheap, true));
        assertEquals(cheap, ModelGuard.resolve("gpt-5-thinking", cheap, true));
        assertEquals(cheap, ModelGuard.resolve("claude-3-opus", cheap, true));
        assertEquals(cheap, ModelGuard.resolve("qwen-max", cheap, true));
    }

    @Test
    @DisplayName("便宜档不能被误伤:flash / mini / lite 原样保留")
    void cheapStays() {
        assertEquals("deepseek-flash", ModelGuard.resolve("deepseek-flash", "deepseek-flash", true));
        assertEquals("gpt-4o-mini", ModelGuard.resolve("gpt-4o-mini", "deepseek-flash", true));
        assertEquals("glm-4-flash", ModelGuard.resolve("glm-4-flash", "deepseek-flash", true));
        assertEquals("deepseek-chat", ModelGuard.resolve("deepseek-chat", "deepseek-flash", true));
    }

    @Test
    @DisplayName("关掉省钱模式时按用户填的原样走")
    void disabledKeepsAsIs() {
        assertEquals("deepseek-v4-pro", ModelGuard.resolve("deepseek-v4-pro", "deepseek-flash", false));
    }

    @Test
    @DisplayName("没填模型名时保持为空,让上层报配置缺失而不是偷偷换别家模型")
    void blankStaysBlank() {
        assertEquals("", ModelGuard.resolve(null, "deepseek-flash", true));
        assertEquals("", ModelGuard.resolve("  ", "deepseek-flash", true));
    }

    @Test
    @DisplayName("降级判断用于日志与界面提示")
    void downgradedFlag() {
        assertTrue(ModelGuard.downgraded("deepseek-v4-pro", "deepseek-flash"));
        assertFalse(ModelGuard.downgraded("deepseek-flash", "deepseek-flash"));
        assertFalse(ModelGuard.downgraded("", ""));
    }
}
