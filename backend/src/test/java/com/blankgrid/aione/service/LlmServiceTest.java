package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型调用的前置校验与连通自检的失败分支。
 * 全部不联网:只覆盖「配置不全时给什么提示」,真调模型由人工联调负责。
 */
class LlmServiceTest {

    @TempDir
    Path tempDir;

    private LlmService svcWith(SettingsService.Provider... providers) {
        SettingsService settings = new SettingsService(tempDir.toString());
        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = new ArrayList<>(List.of(providers));
        settings.save(s);
        return new LlmService(settings, tempDir.toString());
    }

    @Test
    @DisplayName("没有任何服务商时,调用前就拦住并提示去设置页")
    void noProvider() {
        LlmService llm = svcWith();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        assertTrue(e.getMessage().contains("尚未配置大模型 API"), "错误提示要指路,不能是空指针");
    }

    @Test
    @DisplayName("有 baseUrl 但没有模型名时,报的是缺模型而不是网络错误")
    void missingModel() {
        LlmService llm = svcWith(new SettingsService.Provider("甲", "https://api.example.com/v1", "k", "", true));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        assertTrue(e.getMessage().contains("尚未配置默认模型"));
    }

    @Test
    @DisplayName("自检:没有可测服务商时返回 ok=false 并说明原因")
    void selfCheckWithoutProvider() {
        Map<String, Object> r = svcWith().test(null, null);
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("服务商"));
        assertFalse(String.valueOf(r.get("message")).isBlank(), "失败必须给出可读原因,不能只有 ok=false");
    }

    @Test
    @DisplayName("自检:缺 Base URL 时直接判定失败,不发无效请求")
    void selfCheckWithoutBaseUrl() {
        SettingsService.Provider p = new SettingsService.Provider("甲", "", "k", "m1", true);
        Map<String, Object> r = svcWith(p).test(p, null);
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("Base URL"));
        assertEquals("甲", r.get("provider"));
    }

    @Test
    @DisplayName("自检:缺模型名时判定失败")
    void selfCheckWithoutModel() {
        SettingsService.Provider p = new SettingsService.Provider("甲", "https://api.example.com/v1", "k", "", true);
        Map<String, Object> r = svcWith(p).test(p, null);
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("message")).contains("模型"));
        assertFalse(String.valueOf(r.get("message")).contains("k"), "错误信息里不能回显密钥");
    }
}
