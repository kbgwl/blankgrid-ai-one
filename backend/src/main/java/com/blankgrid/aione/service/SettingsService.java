package com.blankgrid.aione.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置。LLM 密钥存放在本地 JSON 文件(./data/llm-config.json),不进数据库。
 */
@Service
public class SettingsService {

    public static class Provider {
        public String name = "";
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        public boolean primary = false;

        public Provider() {}
        public Provider(String name, String baseUrl, String apiKey, String model, boolean primary) {
            this.name = name; this.baseUrl = baseUrl; this.apiKey = apiKey;
            this.model = model; this.primary = primary;
        }
    }

    /**
     * 电商联盟配置。两家都是 CPS 推广体系:只能查商品、生成带 PID 的购买链接,
     * 没有下单与支付能力 —— 这是平台协议决定的,不是我们没做。
     *
     * <p>密钥和 LLM 的 Key 一样按 AES 加密落盘,内存里才是明文。
     */
    public static class Ecom {
        /** 京东联盟 AppKey / AppSecret(media.jd.com → 开发者管理) */
        public String jdAppKey = "";
        public String jdAppSecret = "";
        /** 淘宝客 AppKey / AppSecret + 推广位 PID(adzone_id,纯数字) */
        public String tbAppKey = "";
        public String tbAppSecret = "";
        public String tbAdzoneId = "";
        /** 只查有券商品。采购场景基本成立:没券的价格通常不是最优 */
        public Boolean couponOnly;
        /** 自定义网关,留空用官方默认 */
        public String jdGateway = "";
        public String tbGateway = "";

        public boolean jdReady() {
            return notBlank(jdAppKey) && notBlank(jdAppSecret);
        }

        public boolean tbReady() {
            return notBlank(tbAppKey) && notBlank(tbAppSecret) && notBlank(tbAdzoneId);
        }

        public boolean anyReady() {
            return jdReady() || tbReady();
        }

        private static boolean notBlank(String v) {
            return v != null && !v.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /** null = 请求没带这个字段(保留原值);空数组 = 明确清空。由 get() 统一补成非 null。 */
        public List<Provider> providers;
        public String knowledgeBase = "";
        public int concurrency = 4;
        /** 每日 token 预算,0 = 不限;到顶自动暂停 CEO 自动巡视 */
        public long dailyTokenBudget = 0;
        /** CEO 自动巡视频率(分钟) */
        public int ceoIntervalMinutes = 15;
        /** 访问口令,空 = 不启用登录 */
        public String password = "";
        /** 公司级质量底线:所有交付物必须达到的标准 */
        public String qualityBar = DEFAULT_QUALITY_BAR;
        /** 评审通过分(0-100) */
        public int reviewThreshold = 85;
        /** 单个交付物最多返工轮次 */
        public int maxReviewRounds = 3;
        /** 目标创建后自动拆解 */
        public boolean autoStartGoals = true;
        /** 成果落盘目录;空 = 程序目录下的 exports/。可填绝对路径(如 E:\交付\空白格) */
        public String exportDir = "";
        /** 评审通过后自动把成果写进落盘目录。null = 请求没带这个字段(保留原值),取值用 autoExportArtifacts() */
        public Boolean autoExportArtifacts;
        /** 电商联盟(京东/淘宝客)。null = 老配置没这个字段 */
        public Ecom ecom;
        /** CEO 决策(巡视拍板 + 目标拆解)专用服务商名;null=未提供,空串=跟随主用 */
        public String decisionProvider;
        /** CEO 决策专用模型名;null=未提供,空串=用该服务商默认模型 */
        public String decisionModel;
        /**
         * 省钱模型:开了省钱模式后,任何贵模型(pro/reasoner/thinking/max…)都会被换成它。
         * 空 = 用内置默认(deepseek-flash)。
         */
        public String cheapModel = "deepseek-flash";
        /** 省钱模式。null = 老配置没这个字段,按开启处理 */
        public Boolean enforceCheap;
        /**
         * 关闭模型思考模式(null = 按开启处理)。思考 token 按输出计费,
         * 而且默认 effort=high,是长任务里最贵也最没必要的一块。
         */
        public Boolean thinkingOff;
        /** 模型单价表(元/百万 token);空 = 用内置参考价。按前缀匹配模型名 */
        public List<Price> pricing;
        /** PDF 导出用的中文字体文件;空 = 自动找系统里的微软雅黑/黑体/Noto */
        public String pdfFontPath = "";
        /** API 返回给前端时隐藏密钥 */
        public transient boolean masked = false;
    }

    /** 一个模型的计价:输入/输出分别多少钱(人民币元 / 百万 token) */
    public static class Price {
        /** 模型名前缀,不区分大小写,如 deepseek-flash */
        public String model = "";
        public double input = 0;
        public double output = 0;

        public Price() {}
        public Price(String model, double input, double output) {
            this.model = model; this.input = input; this.output = output;
        }
    }

    /** 省钱模式默认用的模型:便宜十倍,写方案/写文案够用 */
    public static final String DEFAULT_CHEAP_MODEL = "deepseek-flash";

    /**
     * 内置参考价(元 / 百万 token)。只是量级参考,真实账单以服务商为准;
     * 设置页可以改,改了就按改的算。
     */
    static final List<Price> REFERENCE_PRICES = List.of(
            new Price("deepseek-flash", 0.5, 1.5),
            new Price("deepseek-chat", 2.0, 8.0),
            new Price("deepseek-v4", 2.0, 8.0),
            new Price("deepseek-reasoner", 4.0, 16.0),
            new Price("deepseek-v4-pro", 4.0, 16.0),
            new Price("glm-4-flash", 0.0, 0.0),
            new Price("glm-4", 5.0, 5.0),
            new Price("gpt-4o-mini", 1.1, 4.3),
            new Price("gpt-4o", 18.0, 72.0),
            new Price("qwen-plus", 0.8, 2.0),
            new Price("qwen-max", 20.0, 60.0)
    );

    /** 认不出来的模型按这一档估,避免成本列一直是 0 */
    private static final Price FALLBACK_PRICE = new Price("*", 2.0, 8.0);

    public static final String DEFAULT_QUALITY_BAR =
            "交付物必须能直接使用,而不是解释你准备怎么做。要求:结构完整、结论具体、论据/数据/示例可核查、步骤可执行;"
            + "对照验收标准逐条满足,不确定处标明假设;禁止空话、套话、正确的废话;宁可少而深,不要多而泛。";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box;
    private Settings cache;

    public SettingsService(@Value("${aione.data-dir:./data}") String dataDir) {
        this.file = Path.of(dataDir, "settings.json");
        this.box = new SecretBox(Path.of(dataDir));
    }

    public synchronized Settings get() {
        if (cache == null) {
            cache = load();
            // 落盘文件里可能没有 providers,统一补成空列表,后续取值不必到处判 null
            if (cache.providers == null) cache.providers = new ArrayList<>();
            // 老配置文件没有这个字段:默认开着,别让升级把落盘功能悄悄关掉
            if (cache.autoExportArtifacts == null) cache.autoExportArtifacts = Boolean.TRUE;
            if (cache.enforceCheap == null) cache.enforceCheap = Boolean.TRUE;
            if (cache.thinkingOff == null) cache.thinkingOff = Boolean.TRUE;
            if (cache.cheapModel == null || cache.cheapModel.isBlank()) cache.cheapModel = DEFAULT_CHEAP_MODEL;
            if (cache.ecom == null) cache.ecom = new Ecom();
        }
        return cache;
    }

    public synchronized Settings save(Settings incoming) {
        Settings current = get();
        // 密钥为空或掩码时保留原值。
        // 掩码那两个分支是必须的:GET 返回的是 "********",前端原样 PUT 回来,
        // 只判 isBlank 的话就会把真实 Key 覆盖成八个星号 —— 表现为"改了个无关设置,
        // 模型突然全 401",而且看不出是自己把 Key 弄丢的。
        if (incoming.providers != null) {
            for (Provider p : incoming.providers) {
                if (p.apiKey == null || p.apiKey.isBlank() || "********".equals(p.apiKey)) {
                    current.providers.stream()
                            .filter(c -> c.name.equals(p.name))
                            .findFirst()
                            .ifPresent(c -> p.apiKey = c.apiKey);
                }
            }
            current.providers = incoming.providers;
        }
        if (incoming.knowledgeBase != null) current.knowledgeBase = incoming.knowledgeBase;
        current.concurrency = incoming.concurrency;
        current.dailyTokenBudget = incoming.dailyTokenBudget;
        current.ceoIntervalMinutes = incoming.ceoIntervalMinutes;
        if (incoming.qualityBar != null && !incoming.qualityBar.isBlank()) current.qualityBar = incoming.qualityBar;
        if (incoming.reviewThreshold > 0) current.reviewThreshold = Math.min(100, incoming.reviewThreshold);
        if (incoming.maxReviewRounds > 0) current.maxReviewRounds = Math.min(5, incoming.maxReviewRounds);
        current.autoStartGoals = incoming.autoStartGoals;
        if (incoming.exportDir != null) current.exportDir = incoming.exportDir.trim();
        if (incoming.autoExportArtifacts != null) current.autoExportArtifacts = incoming.autoExportArtifacts;
        // 决策模型槽位:只在请求真的带了这两个字段时才覆盖(老客户端不传就不会被清空)
        if (incoming.decisionProvider != null) current.decisionProvider = incoming.decisionProvider.trim();
        if (incoming.decisionModel != null) current.decisionModel = incoming.decisionModel.trim();
        // 单价表与 PDF 字体:同样只在请求真带了的时候覆盖,免得老客户端把它清空
        if (incoming.pricing != null) current.pricing = incoming.pricing;
        if (incoming.cheapModel != null) {
            current.cheapModel = incoming.cheapModel.isBlank() ? DEFAULT_CHEAP_MODEL : incoming.cheapModel.trim();
        }
        if (incoming.enforceCheap != null) current.enforceCheap = incoming.enforceCheap;
        if (incoming.thinkingOff != null) current.thinkingOff = incoming.thinkingOff;
        if (incoming.pdfFontPath != null) current.pdfFontPath = incoming.pdfFontPath.trim();
        // 电商联盟:secret 同样遵守"空或掩码回传 = 保留原值",否则前端每存一次设置
        // 就会把已经保存好的 AppSecret 抹成星号,表现为"配置看着在、一调就 403"。
        if (incoming.ecom != null) {
            if (current.ecom == null) current.ecom = new Ecom();
            Ecom in = incoming.ecom;
            if (in.jdAppKey != null) current.ecom.jdAppKey = in.jdAppKey.trim();
            if (in.jdAppSecret != null) {
                if ("__CLEAR__".equals(in.jdAppSecret)) current.ecom.jdAppSecret = "";
                else if (isRealSecret(in.jdAppSecret)) current.ecom.jdAppSecret = in.jdAppSecret.trim();
            }
            if (in.tbAppKey != null) current.ecom.tbAppKey = in.tbAppKey.trim();
            if (in.tbAppSecret != null) {
                if ("__CLEAR__".equals(in.tbAppSecret)) current.ecom.tbAppSecret = "";
                else if (isRealSecret(in.tbAppSecret)) current.ecom.tbAppSecret = in.tbAppSecret.trim();
            }
            if (in.tbAdzoneId != null) current.ecom.tbAdzoneId = in.tbAdzoneId.trim();
            if (in.couponOnly != null) current.ecom.couponOnly = in.couponOnly;
            if (in.jdGateway != null) current.ecom.jdGateway = in.jdGateway.trim();
            if (in.tbGateway != null) current.ecom.tbGateway = in.tbGateway.trim();
        }
        // 口令:空或掩码回传则保留原值;__CLEAR__ 表示清除
        if ("__CLEAR__".equals(incoming.password)) {
            current.password = "";
        } else if (incoming.password != null && !incoming.password.isBlank() && !"********".equals(incoming.password)) {
            current.password = incoming.password;
        }
        persist(current);
        return current;
    }

    /**
     * 落盘。密钥与口令写成密文(AES-GCM),内存里的 cache 仍是明文 ——
     * 调模型要用真 Key,但磁盘上不该留明文。
     */
    private void persist(Settings s) {
        Settings disk = mapper.convertValue(s, Settings.class);
        disk.masked = false;
        if (disk.providers != null) {
            for (Provider p : disk.providers) p.apiKey = box.encrypt(p.apiKey);
        }
        if (disk.password != null) disk.password = box.encrypt(disk.password);
        if (disk.ecom != null) {
            disk.ecom.jdAppSecret = box.encrypt(disk.ecom.jdAppSecret);
            disk.ecom.tbAppSecret = box.encrypt(disk.ecom.tbAppSecret);
        }
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), disk);
        } catch (IOException e) {
            throw new RuntimeException("保存设置失败: " + e.getMessage(), e);
        }
    }

    /** 前端回传的 secret 是不是真的值(而不是掩码或空)。 */
    private static boolean isRealSecret(String v) {
        return v != null && !v.isBlank() && !"********".equals(v);
    }

    public Provider primaryProvider() {
        return get().providers.stream().filter(p -> p.primary).findFirst()
                .orElse(get().providers.isEmpty() ? null : get().providers.get(0));
    }

    /** CEO 决策走哪个服务商:槽位点了名就用它,没点或找不到就退回主用 */
    public Provider decisionProvider() {
        Provider hit = providerByName(get().decisionProvider);
        return hit != null ? hit : primaryProvider();
    }

    /** 按名称取服务商(名称是用户自己起的,不含密钥) */
    public Provider providerByName(String name) {
        if (name == null || name.isBlank() || get().providers == null) return null;
        return get().providers.stream().filter(p -> name.equals(p.name)).findFirst().orElse(null);
    }

    /** CEO 决策用的模型名:槽位 > 传入的兜底 > 该服务商默认 */
    public String decisionModel(String fallback) {
        String m = get().decisionModel;
        if (m != null && !m.isBlank()) return m;
        if (fallback != null && !fallback.isBlank()) return fallback;
        Provider p = decisionProvider();
        return p == null ? null : p.model;
    }

    /** 是否有可用模型配置(不检查 key,兼容本地 Ollama 等无鉴权服务) */
    public boolean hasProvider() {
        Provider p = primaryProvider();
        return p != null && p.baseUrl != null && !p.baseUrl.isBlank()
                && p.model != null && !p.model.isBlank();
    }

    /** 成果落盘目录:配置为空时用程序目录下的 exports/,填了相对路径也按程序目录展开 */
    public Path exportRoot() {
        String raw = get().exportDir;
        Path p = (raw == null || raw.isBlank()) ? Path.of("exports") : Path.of(raw.trim());
        return p.toAbsolutePath().normalize();
    }

    /** 评审通过后是否自动落盘 */
    public boolean autoExportArtifacts() {
        return !Boolean.FALSE.equals(get().autoExportArtifacts);
    }

    /** 自学习沉淀:把复盘教训追加进全员知识库(去重,保留最近 20 条) */
    public synchronized void appendKnowledge(String entry) {
        if (entry == null || entry.isBlank()) return;
        Settings s = get();
        String kb = s.knowledgeBase == null ? "" : s.knowledgeBase;
        if (kb.contains(entry)) return; // 完全相同的教训不重复沉淀
        String section = "【经验教训】";
        int idx = kb.indexOf(section);
        String entryLine = "- " + entry;
        if (idx < 0) {
            kb = kb + (kb.isBlank() ? "" : "\n\n") + section + "\n" + entryLine;
        } else {
            // 在经验教训段落头部插入,截断到 20 条
            String head = kb.substring(0, idx + section.length());
            String tail = kb.substring(idx + section.length()).trim();
            String[] lines = tail.isEmpty() ? new String[0] : tail.split("\n");
            StringBuilder merged = new StringBuilder(head).append("\n").append(entryLine);
            int kept = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                if (kept++ < 19) merged.append("\n").append(line);
            }
            kb = merged.toString();
        }
        s.knowledgeBase = kb;
        persist(s);
    }

    private Settings load() {
        if (!Files.exists(file)) return new Settings();
        Settings s;
        try {
            s = mapper.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            throw new RuntimeException("读取设置失败: " + e.getMessage(), e);
        }
        // 老版本明文密钥:照常解出真身,顺手把文件改成密文,不需要人工迁移
        boolean plainFound = false;
        if (s.providers != null) {
            for (Provider p : s.providers) {
                if (p.apiKey == null || p.apiKey.isEmpty()) continue;
                if (!box.isEncrypted(p.apiKey)) plainFound = true;
                p.apiKey = box.decrypt(p.apiKey);
            }
        }
        if (s.password != null && !s.password.isEmpty()) {
            if (!box.isEncrypted(s.password)) plainFound = true;
            s.password = box.decrypt(s.password);
        }
        if (s.ecom != null) {
            if (s.ecom.jdAppSecret != null && !s.ecom.jdAppSecret.isEmpty()) {
                if (!box.isEncrypted(s.ecom.jdAppSecret)) plainFound = true;
                s.ecom.jdAppSecret = box.decrypt(s.ecom.jdAppSecret);
            }
            if (s.ecom.tbAppSecret != null && !s.ecom.tbAppSecret.isEmpty()) {
                if (!box.isEncrypted(s.ecom.tbAppSecret)) plainFound = true;
                s.ecom.tbAppSecret = box.decrypt(s.ecom.tbAppSecret);
            }
        }
        if (plainFound) {
            try {
                persist(s);
            } catch (RuntimeException ignored) {
                // 迁移失败不影响启动,下次保存还会再试一次
            }
        }
        return s;
    }

    // ==================== 计价 ====================

    /** 某个模型的单价:用户配置优先(前缀匹配),其次内置参考价,最后兜底。 */
    public Price priceOf(String model) {
        String m = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        List<Price> userPriced = get().pricing;
        if (userPriced != null) {
            for (Price p : userPriced) {
                if (p != null && p.model != null && !p.model.isBlank() && m.startsWith(p.model.trim().toLowerCase(java.util.Locale.ROOT))) {
                    return p;
                }
            }
        }
        for (Price p : REFERENCE_PRICES) {
            if (m.startsWith(p.model)) return p;
        }
        return FALLBACK_PRICE;
    }

    /**
     * 估算花费(元)。token 账本只记总量、不区分输入输出,
     * 这里按「输入:输出 = 3:1」的常见比例折算,数量级够用,不做伪精确。
     */
    public double costOf(long tokens, String model) {
        if (tokens <= 0) return 0;
        Price p = priceOf(model);
        double in = tokens * 0.75 / 1_000_000.0 * p.input;
        double out = tokens * 0.25 / 1_000_000.0 * p.output;
        return in + out;
    }

    /** 执行岗位默认用哪个模型名(成本折算时当缺省值) */
    public String defaultModel() {
        Provider p = primaryProvider();
        return p == null ? "" : (p.model == null ? "" : p.model);
    }

    // ==================== 省钱模式 ====================

    /** 是否关闭模型思考模式。默认关思考:省下的钱比多出来的推理值钱。 */
    public boolean thinkingOff() {
        return !Boolean.FALSE.equals(get().thinkingOff);
    }

    /** 是否强制所有调用都避开贵模型。默认开。 */
    public boolean enforceCheap() {
        return !Boolean.FALSE.equals(get().enforceCheap);
    }

    /** 省钱模型名(空则退回内置默认)。 */
    public String cheapModel() {
        String m = get().cheapModel;
        return (m == null || m.isBlank()) ? DEFAULT_CHEAP_MODEL : m.trim();
    }

    /**
     * 一次调用的最终模型名:调用方想要的 → 省钱护栏 → 服务商默认。
     * 这是全系统唯一的"发请求前定模型"入口,别在别处再自己拼。
     */
    public String resolveModel(String want) {
        String actual = ModelGuard.resolve(want, cheapModel(), enforceCheap());
        if (actual == null || actual.isBlank()) actual = defaultModel();
        return actual;
    }

    /** 当前单价表(内置 + 用户覆盖),给设置页和前端展示 */
    public List<Price> effectivePrices() {
        List<Price> out = new ArrayList<>();
        List<Price> userPriced = get().pricing;
        if (userPriced != null) out.addAll(userPriced);
        for (Price p : REFERENCE_PRICES) {
            boolean overridden = out.stream().anyMatch(u -> u != null && u.model != null && u.model.equalsIgnoreCase(p.model));
            if (!overridden) out.add(p);
        }
        return out;
    }

    /** 返回给前端的副本,密钥掩码 */
    public Settings masked() {
        Settings s = get();
        Map<String, Object> raw = new LinkedHashMap<>();
        try {
            raw = mapper.convertValue(s, mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IllegalArgumentException ignored) {}
        List<Map<String, Object>> providers = new ArrayList<>();
        if (s.providers != null) {
            for (Provider p : s.providers) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.name);
                m.put("baseUrl", p.baseUrl);
                m.put("model", p.model);
                m.put("primary", p.primary);
                m.put("apiKey", (p.apiKey == null || p.apiKey.isEmpty()) ? "" : "********");
                providers.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.put("providers", providers);
        out.put("apiKeyMasked", true);
        out.put("password", (s.password == null || s.password.isEmpty()) ? "" : "********");
        Settings maskedSettings = mapper.convertValue(out, Settings.class);
        if (maskedSettings.ecom != null) {
            if (maskedSettings.ecom.jdAppSecret != null && !maskedSettings.ecom.jdAppSecret.isEmpty()) {
                maskedSettings.ecom.jdAppSecret = "********";
            }
            if (maskedSettings.ecom.tbAppSecret != null && !maskedSettings.ecom.tbAppSecret.isEmpty()) {
                maskedSettings.ecom.tbAppSecret = "********";
            }
        }
        return maskedSettings;
    }
}
