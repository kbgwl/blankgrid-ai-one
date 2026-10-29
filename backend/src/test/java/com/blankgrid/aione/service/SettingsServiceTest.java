package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置的默认值、密钥掩码与槽位选择。
 * 每个用例用独立临时目录，不碰真实 data/settings.json。
 */
class SettingsServiceTest {

    @TempDir
    Path tempDir;

    private SettingsService fresh() {
        return new SettingsService(tempDir.toString());
    }

    private static SettingsService.Provider provider(String name, String baseUrl, String key, String model, boolean primary) {
        return new SettingsService.Provider(name, baseUrl, key, model, primary);
    }

    private static SettingsService.Settings withProviders(SettingsService.Provider... providers) {
        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = new ArrayList<>(List.of(providers));
        return s;
    }

    @Test
    @DisplayName("默认设置:门槛 85 分、返工 3 轮、并发 4、不启用口令、没有服务商")
    void defaults() {
        SettingsService svc = fresh();
        SettingsService.Settings s = svc.get();

        assertEquals(85, s.reviewThreshold);
        assertEquals(3, s.maxReviewRounds, "返工 3 轮:文案类交付物需要至少三次迭代才稳定");
        assertEquals(4, s.concurrency);
        assertEquals("", s.password);
        assertEquals(0, s.dailyTokenBudget, "0 表示不限,不能默认卡死");
        assertFalse(svc.hasProvider());
    }

    @Test
    @DisplayName("masked() 把密钥和口令换成掩码,但内存里的真身不动")
    void masksSecrets() {
        SettingsService svc = fresh();
        SettingsService.Settings in = withProviders(
                provider("甲", "https://api.example.com/v1", "sk-secret-123", "flash", true));
        in.password = "pa55word";
        svc.save(in);

        SettingsService.Settings out = svc.masked();
        assertEquals("********", out.providers.get(0).apiKey);
        assertEquals("********", out.password);

        assertEquals("sk-secret-123", svc.get().providers.get(0).apiKey, "真身必须保留明文,否则调不通模型");
        assertEquals("pa55word", svc.get().password);
    }

    @Test
    @DisplayName("前端把掩码原样回传时,不会把真密钥覆盖成空或星号")
    void blankKeyKeepsStoredSecret() {
        SettingsService svc = fresh();
        svc.save(withProviders(provider("甲", "https://api.example.com/v1", "sk-secret-123", "flash", true)));

        SettingsService.Settings echo = withProviders(provider("甲", "https://api.example.com/v1", "", "flash", true));
        svc.save(echo);

        assertEquals("sk-secret-123", svc.get().providers.get(0).apiKey,
                "只改模型名不该顺手把密钥抹掉");
    }

    @Test
    @DisplayName("决策模型优先级:独立槽位 > 传入兜底 > 该服务商默认模型")
    void decisionModelPrecedence() {
        SettingsService svc = fresh();
        SettingsService.Settings in = withProviders(
                provider("主用", "https://a.example.com/v1", "k1", "flash", true),
                provider("决策", "https://b.example.com/v1", "k2", "pro", false));
        in.decisionProvider = "决策";
        in.decisionModel = "pro-ultra";
        svc.save(in);

        assertEquals("pro-ultra", svc.decisionModel("fallback"));
        assertEquals("决策", svc.decisionProvider().name);
        assertEquals("主用", svc.primaryProvider().name);

        SettingsService.Settings cleared = new SettingsService.Settings();
        cleared.providers = null;
        cleared.decisionModel = "";
        svc.save(cleared);
        assertEquals("fallback", svc.decisionModel("fallback"));

        SettingsService.Settings orphan = new SettingsService.Settings();
        orphan.providers = null;
        orphan.decisionProvider = "查无此人";
        svc.save(orphan);
        assertEquals("云端兜底", svc.decisionModel("云端兜底"),
                "槽位指向不存在的服务商时应退回主用,而不是抛空");
    }

    @Test
    @DisplayName("没人标 primary 时,取列表第一个当主用")
    void primaryFallsBackToFirst() {
        SettingsService svc = fresh();
        svc.save(withProviders(
                provider("甲", "https://a.example.com/v1", "k1", "m1", false),
                provider("乙", "https://b.example.com/v1", "k2", "m2", false)));

        SettingsService.Provider p = svc.primaryProvider();
        assertNotNull(p);
        assertEquals("甲", p.name);
        assertTrue(svc.hasProvider());
    }

    @Test
    @DisplayName("只有 baseUrl 没模型时,不算已配置")
    void hasProviderNeedsModel() {
        SettingsService svc = fresh();
        svc.save(withProviders(provider("甲", "https://a.example.com/v1", "k1", "", true)));
        assertFalse(svc.hasProvider());

        SettingsService.Settings again = new SettingsService.Settings();
        again.providers = new ArrayList<>();
        svc.save(again);
        assertNull(svc.primaryProvider());
    }

    @Test
    @DisplayName("只提交其中一个字段时,不能把服务商配置和密钥一起清空")
    void partialUpdateKeepsProviders() throws Exception {
        SettingsService svc = fresh();
        svc.save(withProviders(provider("甲", "https://a.example.com/v1", "sk-live-1", "m1", true)));

        // 真实场景:前端只改了返工轮次,请求体里没有 providers
        SettingsService.Settings partial = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"maxReviewRounds\":3}", SettingsService.Settings.class);
        svc.save(partial);

        assertEquals(1, svc.get().providers.size(), "少传一个字段就清空服务商,等于把密钥弄丢了");
        assertEquals("sk-live-1", svc.get().providers.get(0).apiKey);
        assertEquals(3, svc.get().maxReviewRounds);
    }

    @Test
    @DisplayName("显式传空数组才表示清空服务商")
    void emptyArrayClearsProviders() {
        SettingsService svc = fresh();
        svc.save(withProviders(provider("甲", "https://a.example.com/v1", "k1", "m1", true)));

        SettingsService.Settings clear = new SettingsService.Settings();
        clear.providers = new ArrayList<>();
        svc.save(clear);

        assertTrue(svc.get().providers.isEmpty());
        assertNull(svc.primaryProvider());
    }

    @Test
    @DisplayName("落盘目录:不填就用程序目录下的 exports/,填了绝对路径就原样用")
    void exportRootResolution() {
        SettingsService svc = fresh();
        Path def = svc.exportRoot();
        assertTrue(def.isAbsolute(), "落盘必须是绝对路径,否则换个工作目录就写丢了");
        assertEquals("exports", def.getFileName().toString());

        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = null;
        s.exportDir = tempDir.resolve("交付").toString();
        svc.save(s);
        assertEquals(tempDir.resolve("交付").toAbsolutePath().normalize(), svc.exportRoot());

        SettingsService.Settings cleared = new SettingsService.Settings();
        cleared.providers = null;
        cleared.exportDir = "";
        svc.save(cleared);
        assertEquals("exports", svc.exportRoot().getFileName().toString(), "清空目录应回到默认值而不是写到盘符根");
    }

    @Test
    @DisplayName("自动落盘开关:请求没带这个字段时不能被翻成 false")
    void autoExportFlagIsNullable() throws Exception {
        SettingsService svc = fresh();
        assertTrue(svc.autoExportArtifacts(), "默认应该自动落盘,成果要能直接拿到");

        SettingsService.Settings off = new SettingsService.Settings();
        off.providers = null;
        off.autoExportArtifacts = false;
        svc.save(off);
        assertFalse(svc.autoExportArtifacts());

        // 前端只改门槛,没带 autoExportArtifacts
        SettingsService.Settings partial = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"reviewThreshold\":90}", SettingsService.Settings.class);
        svc.save(partial);
        assertFalse(svc.autoExportArtifacts(), "改别的字段不该把落盘开关偷偷打开或关掉");
        assertEquals(90, svc.get().reviewThreshold);
    }

    @Test
    @DisplayName("把 masked() 的结果原样存回去,真实密钥不能被星号覆盖")
    void maskedRoundTripKeepsRealKeys() {
        SettingsService svc = fresh();
        svc.save(withProviders(provider("主用", "http://x/v1", "sk-real-key", "m", true)));

        // 前端就是这么干的:GET(masked) → 用户改个别的字段 → 整个对象 PUT 回来。
        // 这里模拟那次 PUT:apiKey 是 "********"。
        SettingsService.Settings masked = svc.masked();
        assertEquals("********", masked.providers.get(0).apiKey);
        svc.save(masked);

        // 真实 Key 必须还在,否则下一次调模型就是 401
        assertEquals("sk-real-key", svc.get().providers.get(0).apiKey);
    }

    @Test
    @DisplayName("电商联盟的 AppSecret 同样遵循掩码不覆盖原值")
    void ecomSecretNotOverwrittenByMask() {
        SettingsService svc = fresh();
        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = new ArrayList<>(List.of(provider("主用", "http://x/v1", "k", "m", true)));
        SettingsService.Ecom e = new SettingsService.Ecom();
        e.jdAppKey = "jdkey";
        e.jdAppSecret = "jd-secret";
        e.tbAppKey = "tbkey";
        e.tbAppSecret = "tb-secret";
        e.tbAdzoneId = "999";
        s.ecom = e;
        svc.save(s);

        SettingsService.Settings masked = svc.masked();
        assertEquals("********", masked.ecom.jdAppSecret);
        assertEquals("********", masked.ecom.tbAppSecret);
        // AppKey 不是秘密,应该照常回显,否则用户看不出自己填的对不对
        assertEquals("jdkey", masked.ecom.jdAppKey);
        svc.save(masked);

        assertEquals("jd-secret", svc.get().ecom.jdAppSecret);
        assertEquals("tb-secret", svc.get().ecom.tbAppSecret);
        assertEquals("jdkey", svc.get().ecom.jdAppKey);
    }

}
