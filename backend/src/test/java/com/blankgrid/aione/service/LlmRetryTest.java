package com.blankgrid.aione.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游抖动时的重试与熔断。
 *
 * <p>这些用例全走真 socket:重试逻辑的错误几乎都出在"什么时候该停"上,
 * 拿 mock 替掉 HTTP 层就测不到流式中断这类真实形态。
 */
class LlmRetryTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    /** 每个请求要回什么:按顺序取,取完重复最后一个 */
    private final List<Integer> plan = new ArrayList<>();
    private volatile String sseBody;

    @BeforeEach
    void setUp() throws Exception {
        LlmService.backoffBaseMs = 5;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = hits.incrementAndGet();
            int status = plan.get(Math.min(n - 1, plan.size() - 1));
            if (status != 200) {
                byte[] body = ("{\"error\":{\"message\":\"Service is too busy. We advise users to temporarily"
                        + " switch to alternative LLM API service providers.\"}}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            byte[] body = (sseBody == null ? sse("好的") : sseBody).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        LlmService.backoffBaseMs = 1000;
    }

    /** 拼一段最小的 SSE:一个正文帧 + 结束标记 */
    private static String sse(String text) {
        String chunk = "{\"choices\":[{\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}]}";
        return "data: " + chunk + "\n\ndata: [DONE]\n\n";
    }

    private LlmService llm() {
        SettingsService settings = new SettingsService(tempDir.toString());
        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = new ArrayList<>(List.of(new SettingsService.Provider(
                "本地桩", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "k", "m1", true)));
        settings.save(s);
        return new LlmService(settings, tempDir.toString());
    }

    @Test
    @DisplayName("503 两次后恢复正常:这次调用应当成功,而不是把整份稿子判死")
    void retryThenSucceed() {
        plan.addAll(List.of(503, 503, 200));
        String out = llm().chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null);
        assertEquals("好的", out);
        assertEquals(3, hits.get(), "两次失败各重试一次,第三次成功");
    }

    @Test
    @DisplayName("一直 503:试满三次就放弃,并且抛出可识别的服务商故障")
    void giveUpAfterMaxAttempts() {
        plan.add(503);
        LlmService llm = llm();
        LlmService.UnavailableException e = assertThrows(LlmService.UnavailableException.class,
                () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        assertEquals(3, e.attempts(), "上限是三次,不能无线重试");
        assertEquals(3, hits.get());
        assertTrue(e.getMessage().contains("503"), "错误里要留上游原句,否则排查时无从下手");
    }

    @Test
    @DisplayName("参数错误(400)不重试:重试一万次也还是那句话,白等还烧钱")
    void noRetryOnClientError() {
        plan.add(400);
        assertThrows(IllegalStateException.class,
                () -> llm().chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        assertEquals(1, hits.get(), "4xx 一次就够");
    }

    @Test
    @DisplayName("连续失败到阈值就熔断:后续请求直接拒掉,不再朝趴下的服务商拍请求")
    void breakerStopsTheStorm() {
        plan.add(503);
        LlmService llm = llm();
        for (int i = 0; i < 4; i++) {
            assertThrows(LlmService.UnavailableException.class,
                    () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        }
        int before = hits.get();
        assertTrue(llm.breakerOpen(), "第四次失败后应该已经熔断");

        LlmService.UnavailableException e = assertThrows(LlmService.UnavailableException.class,
                () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, null));
        assertEquals(0, e.attempts(), "熔断期间连一次都不该试");
        assertEquals(before, hits.get(), "熔断期间不能再发请求");
        assertEquals(Boolean.TRUE, llm.breakerInfo().get("open"));
    }

    @Test
    @DisplayName("已经吐出去的字不能再重来一遍,否则正文里会出现两个开头")
    void noRetryAfterFirstTokens() throws Exception {
        // 这个桩只发一帧正文,然后直接把连接断掉,模拟读到一半被掐
        HttpServer flaky = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger n = new AtomicInteger();
        flaky.createContext("/v1/chat/completions", ex -> {
            n.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            // 声明 500 字节却只写几十个就断开 —— 客户端会真的读到一次连接中断,
            // 用 chunked 结束是"正常收尾",测不出中断
            ex.sendResponseHeaders(200, 500);
            OutputStream os = ex.getResponseBody();
            os.write(sse("开头这几个字已经发出去了").getBytes(StandardCharsets.UTF_8));
            os.flush();
            ex.close();
        });
        flaky.start();
        try {
            SettingsService settings = new SettingsService(tempDir.toString());
            SettingsService.Settings s = new SettingsService.Settings();
            s.providers = new ArrayList<>(List.of(new SettingsService.Provider(
                    "半截桩", "http://127.0.0.1:" + flaky.getAddress().getPort() + "/v1", "k", "m1", true)));
            settings.save(s);
            LlmService llm = new LlmService(settings, tempDir.toString());

            StringBuilder shown = new StringBuilder();
            // 关键不只是"重试没重试",而是这次失败被归到哪一类:
            // 流中断必须报成服务商故障,否则任务层会拿一句 UncheckedIOException 记岗位一笔失败
            LlmService.UnavailableException e = assertThrows(LlmService.UnavailableException.class,
                    () -> llm.chatStream(List.of(new LlmService.ChatMessage("user", "hi")), null, shown::append));
            assertTrue(shown.length() > 0, "前面那几个字确实已经渲染出去了");
            assertTrue(e.getMessage().contains("中断"), "要说清是被掐断的,不是模型没回:" + e.getMessage());
            assertEquals(1, n.get(), "既然已经吐字,就不该再来一次 —— 否则界面上会出现两遍开头");
        } finally {
            flaky.stop(0);
        }
    }
}
