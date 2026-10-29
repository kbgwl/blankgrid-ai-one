package com.blankgrid.aione.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具调用的流式解析。
 *
 * <p>这一块错了整个功能就是废的,而且错法很隐蔽:arguments 是**分片**下发的,
 * 只取第一片会得到半截 JSON,工具收到就报"参数不完整";而如果只认 content,
 * 模型"我要调工具"的表达会被当成"模型未返回内容"直接失败。
 * 所以每个分支都用本地假服务走一遍真实 SSE,不联网不花钱。
 */
class LlmToolCallTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String lastBody = "";

    private LlmService svcReturning(String sse) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        SettingsService settings = new SettingsService(tempDir.toString());
        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = new ArrayList<>(List.of(
                new SettingsService.Provider("假服务商", base, "k", "deepseek-flash", true)));
        settings.save(s);
        return new LlmService(settings, tempDir.toString());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static List<LlmService.ToolSpec> specs() {
        return List.of(new LlmService.ToolSpec("ecom_search",
                "搜索商品比价",
                "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}},\"required\":[\"keyword\"]}"));
    }

    @Test
    @DisplayName("arguments 分三片下发时要拼成完整 JSON")
    void mergesArgumentFragments() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"ecom_search","arguments":""}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"keywor"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"d\\":\\"A4打印纸\\",\\"lim"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"it\\":6}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        LlmService llm = svcReturning(sse);
        LlmService.Turn turn = llm.chatWithTools(
                List.of(new LlmService.ChatMessage("user", "帮我买 A4 纸")), null, null, null, false, 8000, specs());

        assertTrue(turn.wantsTools(), "应当识别出工具调用");
        assertEquals(1, turn.toolCalls().size());
        LlmService.ToolCall call = turn.toolCalls().get(0);
        assertEquals("call_abc", call.id());
        assertEquals("ecom_search", call.name());
        // 关键断言:三片拼起来必须是合法且完整的 JSON
        assertEquals("{\"keyword\":\"A4打印纸\",\"limit\":6}", call.arguments());
        assertEquals("tool_calls", turn.finishReason());
    }

    @Test
    @DisplayName("只调工具、正文为空不算失败(这正是之前报「模型未返回内容」的原因)")
    void emptyContentWithToolCallsIsFine() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"ecom_search","arguments":"{\\"keyword\\":\\"抽纸\\"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        LlmService llm = svcReturning(sse);
        LlmService.Turn turn = llm.chatWithTools(
                List.of(new LlmService.ChatMessage("user", "查抽纸")), null, null, null, false, 8000, specs());

        assertEquals("", turn.content());
        assertTrue(turn.wantsTools(), "正文为空但有工具调用,不能抛异常");
    }

    @Test
    @DisplayName("多个工具按 index 归并,不会串味")
    void multipleToolCallsMergedByIndex() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c0","type":"function","function":{"name":"ecom_search","arguments":"{\\"keyword\\":\\"A\\"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"c1","type":"function","function":{"name":"calc","arguments":"{\\"expression\\":\\"1+1\\"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        LlmService llm = svcReturning(sse);
        LlmService.Turn turn = llm.chatWithTools(
                List.of(new LlmService.ChatMessage("user", "x")), null, null, null, false, 8000, specs());

        assertEquals(2, turn.toolCalls().size());
        assertEquals("ecom_search", turn.toolCalls().get(0).name());
        assertEquals("calc", turn.toolCalls().get(1).name());
        assertEquals("{\"expression\":\"1+1\"}", turn.toolCalls().get(1).arguments());
    }

    @Test
    @DisplayName("普通文本回复不受影响:没有工具调用时原样返回")
    void plainTextStillWorks() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"content":"这是结论"}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        LlmService llm = svcReturning(sse);
        LlmService.Turn turn = llm.chatWithTools(
                List.of(new LlmService.ChatMessage("user", "x")), null, null, null, false, 8000, specs());

        assertFalse(turn.wantsTools());
        assertEquals("这是结论", turn.content());
    }

    @Test
    @DisplayName("请求体里真的带上了 tools 声明,且工具模式下不再强塞 response_format")
    void toolsAppearInRequestBody() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        LlmService llm = svcReturning(sse);
        llm.chatWithTools(List.of(new LlmService.ChatMessage("user", "x")), null, null, null, true, 8000, specs());

        assertTrue(lastBody.contains("\"tools\""), "请求体没有 tools:" + lastBody);
        assertTrue(lastBody.contains("ecom_search"), "工具名没带出去:" + lastBody);
        // jsonMode 传了 true,但带工具时应当被关掉 —— 两个"要结构"的指令放一起会互相打架
        assertFalse(lastBody.contains("response_format"), "工具模式下不该同时强制 JSON:" + lastBody);
    }

    @Test
    @DisplayName("回填工具结果时,tool_call_id 必须原样带回")
    void toolResultCarriesCallId() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        LlmService llm = svcReturning(sse);
        List<LlmService.ChatMessage> messages = new ArrayList<>();
        messages.add(new LlmService.ChatMessage("user", "x"));
        messages.add(new LlmService.ChatMessage("assistant", "",
                List.of(new LlmService.ToolCall("call_xyz", "ecom_search", "{\"keyword\":\"纸\"}")), null, null));
        messages.add(LlmService.ChatMessage.toolResult("call_xyz", "ecom_search", "查到 3 条"));

        llm.chatWithTools(messages, null, null, null, false, 8000, null);

        assertTrue(lastBody.contains("\"tool_call_id\":\"call_xyz\""), lastBody);
        assertTrue(lastBody.contains("\"tool_calls\""), "assistant 消息里应保留 tool_calls:" + lastBody);
        assertTrue(lastBody.contains("查到 3 条"), lastBody);
    }
}
