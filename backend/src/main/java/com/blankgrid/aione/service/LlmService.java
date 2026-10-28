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
     * 流式对话。每个增量片段回调 onDelta,返回完整回复。
     */
    public String chatStream(List<ChatMessage> messages, String model, Consumer<String> onDelta) {
        Provider p = settings.primaryProvider();
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
}
