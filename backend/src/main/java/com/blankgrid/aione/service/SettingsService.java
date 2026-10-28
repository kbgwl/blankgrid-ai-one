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
        /** CEO 决策(巡视拍板 + 目标拆解)专用服务商名;null=未提供,空串=跟随主用 */
        public String decisionProvider;
        /** CEO 决策专用模型名;null=未提供,空串=用该服务商默认模型 */
        public String decisionModel;
        /** API 返回给前端时隐藏密钥 */
        public transient boolean masked = false;
    }

    public static final String DEFAULT_QUALITY_BAR =
            "交付物必须能直接使用,而不是解释你准备怎么做。要求:结构完整、结论具体、论据/数据/示例可核查、步骤可执行;"
            + "对照验收标准逐条满足,不确定处标明假设;禁止空话、套话、正确的废话;宁可少而深,不要多而泛。";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private Settings cache;

    public SettingsService(@Value("${aione.data-dir:./data}") String dataDir) {
        this.file = Path.of(dataDir, "settings.json");
    }

    public synchronized Settings get() {
        if (cache == null) {
            cache = load();
            // 落盘文件里可能没有 providers,统一补成空列表,后续取值不必到处判 null
            if (cache.providers == null) cache.providers = new ArrayList<>();
        }
        return cache;
    }

    public synchronized Settings save(Settings incoming) {
        Settings current = get();
        // 密钥为空字符串时保留原值(前端掩码回传时不覆盖)
        if (incoming.providers != null) {
            for (Provider p : incoming.providers) {
                if (p.apiKey == null || p.apiKey.isBlank()) {
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
        // 决策模型槽位:只在请求真的带了这两个字段时才覆盖(老客户端不传就不会被清空)
        if (incoming.decisionProvider != null) current.decisionProvider = incoming.decisionProvider.trim();
        if (incoming.decisionModel != null) current.decisionModel = incoming.decisionModel.trim();
        // 口令:空或掩码回传则保留原值;__CLEAR__ 表示清除
        if ("__CLEAR__".equals(incoming.password)) {
            current.password = "";
        } else if (incoming.password != null && !incoming.password.isBlank() && !"********".equals(incoming.password)) {
            current.password = incoming.password;
        }
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), current);
        } catch (IOException e) {
            throw new RuntimeException("保存设置失败: " + e.getMessage(), e);
        }
        return current;
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
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), s);
        } catch (IOException e) {
            throw new RuntimeException("知识库写入失败: " + e.getMessage(), e);
        }
    }

    private Settings load() {
        if (Files.exists(file)) {
            try {
                return mapper.readValue(file.toFile(), Settings.class);
            } catch (IOException e) {
                throw new RuntimeException("读取设置失败: " + e.getMessage(), e);
            }
        }
        return new Settings();
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
        return mapper.convertValue(out, Settings.class);
    }
}
