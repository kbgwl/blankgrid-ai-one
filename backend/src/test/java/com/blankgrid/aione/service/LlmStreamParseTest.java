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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式解析的三个要命细节,全部用本地假服务验证,不联网不花钱:
 * 1. usage 帧(choices 为空)要认出来,否则成本表永远是估算;
 * 2. reasoning_content 不能当成正文,但也不能因为正文空就说"模型没返回";
 * 3. 服务商不给 usage 时要能退回估算,而不是记成 0。
 */
class LlmStreamParseTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    /** 假服务收到的最后一个请求体,用来验证我们真的把思考模式关掉了 */
    private String lastBody = "";

    /** 起一个只会吐固定 SSE 的假服务商 */
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

    @Test
    @DisplayName("认得出 usage 帧:真实用量优先于估算")
    void readsUsageFrame() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8}}\n\n"
                + "data: [DONE]\n\n";
        LlmService llm = svcReturning(sse);
        String out = llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null);
        assertEquals("OK", out);
        assertEquals(120, llm.lastUsage()[0]);
        assertEquals(8, llm.lastUsage()[1]);
        // 估算值明显不同:有真实用量就不该用估算
        assertEquals(128, llm.tokensUsed(9999));
    }

    @Test
    @DisplayName("服务商不给 usage 时退回估算,而不是记成 0")
    void fallsBackToEstimate() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                + "data: [DONE]\n\n";
        LlmService llm = svcReturning(sse);
        llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null);
        assertEquals(640, llm.tokensUsed(640));
    }

    @Test
    @DisplayName("只有 reasoning_content 时,报的是「只输出思考过程」而不是含糊的「未返回内容」")
    void reasoningOnlyIsDiagnosed() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想一下这个问题的结构\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                + "data: [DONE]\n\n";
        LlmService llm = svcReturning(sse);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        assertTrue(e.getMessage().contains("只输出了思考过程"), "错误信息要能定位到推理模型截断:" + e.getMessage());
    }

    @Test
    @DisplayName("默认请求里要带 thinking=disabled:思考 token 也按输出计费,不关就是白烧钱")
    void thinkingIsDisabledByDefault() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n";
        LlmService llm = svcReturning(sse);
        llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null);
        assertTrue(lastBody.contains("\"thinking\""), "请求体里没有 thinking 字段:" + lastBody);
        assertTrue(lastBody.contains("disabled"), "思考模式没被关掉:" + lastBody);
        // 关思考的同时仍然要拿 usage,否则成本还是估算
        assertTrue(lastBody.contains("include_usage"), "缺少 usage 请求:" + lastBody);
    }

    @Test
    @DisplayName("省钱模式把贵模型换掉,并在 lastModel 里如实反映实际模型")
    void downgradesModel() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                + "data: [DONE]\n\n";
        LlmService llm = svcReturning(sse);
        llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), "deepseek-v4-pro", null);
        assertEquals("deepseek-flash", llm.lastModel());
        assertTrue(llm.lastDowngraded(), "降级要能被界面和日志看见");
    }
}
