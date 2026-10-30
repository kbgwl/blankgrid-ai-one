package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具授权策略的解析优先级。
 *
 * <p>这条链路只允许有一个答案:工具名 > 分类 > 全局 > 全局默认 > 工具自带默认。
 * 顺序错了就会出现"设置里关了、实际还在跑",而且没有任何报错,只有事后翻日志才发现。
 */
class ToolPolicyTest {

    @TempDir
    Path tempDir;

    private SettingsService fresh() {
        return new SettingsService(tempDir.toString());
    }

    @Test
    @DisplayName("没配策略时用工具自己声明的默认:只读工具 auto,逆向工具 confirm")
    void fallsBackToToolDefault() {
        SettingsService svc = fresh();
        assertEquals("auto", svc.toolPolicyFor("calc", "utility", "auto"));
        assertEquals("confirm", svc.toolPolicyFor("rea_reverse", "reverse", "confirm"));
        assertEquals("auto", svc.toolPolicyFor("unknown", "general", null), "没声明默认的一律按自动");
    }

    @Test
    @DisplayName("工具名 > 分类 > 全局:越具体越优先")
    void precedence() {
        SettingsService svc = fresh();
        SettingsService.Settings in = new SettingsService.Settings();
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("*", "off");
        policy.put("category:reverse", "confirm");
        policy.put("rea_reverse", "auto");
        in.toolPolicy = policy;
        svc.save(in);

        assertEquals("auto", svc.toolPolicyFor("rea_reverse", "reverse", "confirm"), "点名到工具,最高优先");
        assertEquals("confirm", svc.toolPolicyFor("other_reverse", "reverse", "auto"), "分类命中");
        assertEquals("off", svc.toolPolicyFor("calc", "utility", "auto"), "只剩全局兜底");
    }

    @Test
    @DisplayName("全局默认策略:没单独配的工具走它")
    void globalDefault() {
        SettingsService svc = fresh();
        SettingsService.Settings in = new SettingsService.Settings();
        in.toolPolicyDefault = "confirm";
        svc.save(in);
        assertEquals("confirm", svc.toolPolicyFor("calc", "utility", "auto"));
    }

    @Test
    @DisplayName("非法策略值被忽略,不会把工具变成不可解释的状态")
    void invalidValuesIgnored() {
        SettingsService svc = fresh();
        SettingsService.Settings in = new SettingsService.Settings();
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("calc", "AUTO");           // 大小写不敏感,归一化后合法
        policy.put("ecom_search", "maybe");   // 非法值 → 丢掉这条覆盖
        policy.put("", "off");                // 空 key → 丢掉
        in.toolPolicy = policy;
        svc.save(in);

        assertEquals("auto", svc.toolPolicyFor("calc", "utility", "confirm"));
        assertEquals("confirm", svc.toolPolicyFor("ecom_search", "ecommerce", "confirm"),
                "非法值应当退回工具默认,而不是当成 off");
        assertFalse(svc.get().toolPolicy.containsKey(""), "空 key 不该进策略表");
    }

    @Test
    @DisplayName("自主调用的三个开关:默认开、轮数收在 1~6")
    void autonomyDefaults() {
        SettingsService svc = fresh();
        assertTrue(svc.ceoToolsEnabled(), "新装默认允许 CEO 自查");
        assertTrue(svc.decisionStrong(), "决策默认不被省钱降级");
        assertEquals(2, svc.ceoToolRounds());

        SettingsService.Settings in = new SettingsService.Settings();
        in.ceoToolRounds = 99;
        svc.save(in);
        assertEquals(6, svc.ceoToolRounds(), "轮数上限压在 6,防止一次巡视烧穿预算");

        SettingsService.Settings off = new SettingsService.Settings();
        off.ceoAutoTools = false;
        off.decisionStrongModel = false;
        svc.save(off);
        assertFalse(svc.ceoToolsEnabled());
        assertFalse(svc.decisionStrong());
    }

    @Test
    @DisplayName("REA 配置:没启用或没工作区都算没配好")
    void reaReadiness() {
        SettingsService svc = fresh();
        assertFalse(svc.rea().ready(), "默认不启用");

        SettingsService.Settings in = new SettingsService.Settings();
        in.rea = new SettingsService.Rea();
        in.rea.enabled = true;
        svc.save(in);
        assertFalse(svc.rea().ready(), "启用了但没工作区仍不可用 —— 不允许全盘扫描");

        SettingsService.Settings withDir = new SettingsService.Settings();
        withDir.rea = new SettingsService.Rea();
        withDir.rea.enabled = true;
        withDir.rea.workdir = tempDir.toString();
        svc.save(withDir);
        assertTrue(svc.rea().ready());

        SettingsService.Settings cleared = new SettingsService.Settings();
        cleared.rea = new SettingsService.Rea();
        cleared.rea.enabled = true;
        cleared.rea.command = "";
        svc.save(cleared);
        assertEquals("npx -y rea-agents@latest", svc.rea().command,
                "命令留空 = 用默认命令,不该被抹成空串(空命令会让工具永久不可用)");
        assertEquals("", svc.rea().workdir,
                "工作区是显式安全配置,提交空串就是要清空");
    }
}
