package com.blankgrid.aione.service;

import com.blankgrid.aione.service.SettingsService.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议调用(SSE 流式)。兼容 GLM / OpenAI / DeepSeek 等。
 */
@Service
public class LlmService {

    public static class ChatMessage {
        public final String role;
        public final String content;
        public ChatMessage(String role, String content) { this.role = role; this.content = content; }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsService settings;

    public LlmService(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * 流式对话。每个增量片段回调 onDelta,返回完整回复。走主用服务商。
     */
    public String chatStream(List<ChatMessage> messages, String model, Consumer<String> onDelta) {
        return chatStream(messages, model, onDelta, null);
    }

    /**
     * 流式对话,可指定服务商 —— CEO 决策走独立槽位,岗位执行仍走主用,两者互不干扰。
     */
    public String chatStream(List<ChatMessage> messages, String model, Consumer<String> onDelta, Provider forced) {
        Provider p = forced != null ? forced : settings.primaryProvider();
        if (p == null || p.baseUrl == null || p.baseUrl.isBlank()) {
            throw new IllegalStateException("尚未配置大模型 API,请在「设置」中填写服务商信息");
        }
        String useModel = (model != null && !model.isBlank()) ? model : p.model;
        if (useModel == null || useModel.isBlank()) {
            throw new IllegalStateException("尚未配置默认模型,请在「设置」中填写");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);
        body.put("stream", true);
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role);
            n.put("content", m.content);
        }

        String url = p.baseUrl.replaceAll("/+$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (p.apiKey == null ? "" : p.apiKey))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        StringBuilder full = new StringBuilder();
        try {
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                String errBody = resp.body().collect(java.util.stream.Collectors.joining("\n"));
                throw new IllegalStateException("API 返回 " + resp.statusCode() + ": " + truncate(errBody, 500));
            }
            resp.body().forEach(line -> {
                if (!line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonNode root = mapper.readTree(data);
                    JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String piece = delta.asText();
                        full.append(piece);
                        if (onDelta != null) onDelta.accept(piece);
                    }
                } catch (IOException ignored) {
                    // 跳过无法解析的行
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断");
        } catch (IOException e) {
            throw new IllegalStateException("连接大模型 API 失败: " + e.getMessage(), e);
        }
        if (full.isEmpty()) {
            throw new IllegalStateException("模型未返回内容");
        }
        return full.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    /**
     * 服务端连通自检:真发一条最短请求,验证 baseUrl / 密钥 / 模型名三者确实可用。
     * 只回结论与耗时,密钥和上游错误体都不外传。
     */
    public Map<String, Object> test(Provider p, String model) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        out.put("ok", false);
        if (p == null) {
            out.put("message", "还没有可测的服务商,请先在「设置 → 模型服务」填写");
            return out;
        }
        String useModel = (model != null && !model.isBlank()) ? model : p.model;
        out.put("provider", p.name);
        out.put("model", useModel);
        if (p.baseUrl == null || p.baseUrl.isBlank()) { out.put("message", "该服务商没有填 Base URL"); return out; }
        if (useModel == null || useModel.isBlank()) { out.put("message", "该服务商没有填默认模型名"); return out; }
        try {
            String reply = chatStream(List.of(new ChatMessage("user", "只回复两个字母:OK")), useModel, null, p);
            out.put("ok", true);
            out.put("message", "模型已应答 · " + truncate(reply.trim(), 40));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 上游错误体里可能带请求头回显,一律截断
            out.put("message", truncate(msg, 160));
        }
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
    }
}
