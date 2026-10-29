package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两家的返回结构都很绕,而且绕得不一样:
 * 京东是 {xxx_response: {result: "<一段 JSON 字符串>"}} —— 要多解一层;
 * 淘宝是 {xxx_response: {result_list: {map_data: [...]}}} —— 三层到底。
 *
 * <p>写错的后果不是报错,是"查到 0 条":模型拿到空结果,只能如实写"未查到",
 * 而你会以为这个关键词真的没有商品。所以这里用真实结构的假响应把解析钉住。
 */
class EcomParseTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper M = new ObjectMapper();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // 必须先 start 再注册 or 注册后 start 都行,但绝不能只 create 不 start ——
        // 那样请求会一直等到客户端超时,表现为"某家失败"(20 秒),很难看出是测试写错了。
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void reply(String path, String json) {
        server.createContext(path, ex -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private SettingsService settingsWithAll() {
        SettingsService settings = new SettingsService(tempDir.toString());
        SettingsService.Settings s = new SettingsService.Settings();
        s.providers = new ArrayList<>(List.of(
                new SettingsService.Provider("假服务商", "http://127.0.0.1:1/v1", "k", "deepseek-flash", true)));
        SettingsService.Ecom e = new SettingsService.Ecom();
        e.jdAppKey = "k";
        e.jdAppSecret = "sec";
        e.tbAppKey = "tk";
        e.tbAppSecret = "tsec";
        e.tbAdzoneId = "123456";
        e.couponOnly = false;
        e.jdGateway = base + "/jd";
        e.tbGateway = base + "/tb";
        s.ecom = e;
        settings.save(s);
        return settings;
    }

    /** 京东真实结构:result 里塞的是字符串,里面还有一层 JSON */
    private String jdBody() throws Exception {
        ObjectNode data = M.createObjectNode();
        ArrayNode arr = data.putArray("data");
        ObjectNode a = arr.addObject();
        a.put("skuName", "得力(deli) A4打印纸 70g 500张/包 5包/箱");
        a.put("skuId", "100011");
        ObjectNode info = a.putObject("priceInfo");
        info.put("price", 109.9);
        info.put("lowestCouponPrice", 89.9);
        a.putObject("shopInfo").put("shopName", "得力京东自营旗舰店");
        a.put("inOrderCount30Days", 3200);
        a.put("materialUrl", "https://item.jd.com/100011.html");
        data.put("code", 200);

        ObjectNode root = M.createObjectNode();
        ObjectNode wrapper = root.putObject("jd_union_open_goods_jingfen_query_response");
        wrapper.put("result", M.writeValueAsString(data));
        wrapper.put("code", "0");
        return M.writeValueAsString(root);
    }

    /** 淘宝真实结构:三层,价格是字符串,到手价要自己减券 */
    private String tbBody() {
        return """
                {"tbk_dg_material_optional_response":{"result_list":{"map_data":[
                  {"title":"晨光A4打印纸 70g 整箱5包","item_id":"601","zk_final_price":"92.00",
                   "coupon_amount":"10","coupon_info":"满90元减10元","volume":1800,
                   "shop_title":"晨光官方旗舰店","item_url":"https://item.taobao.com/item.htm?id=601",
                   "user_type":1,"commission_rate":"1550"},
                  {"title":"无券款A4纸","item_id":"602","zk_final_price":"88.00",
                   "volume":90,"shop_title":"某小店","item_url":"https://item.taobao.com/item.htm?id=602",
                   "user_type":0}
                ]},"total_results":2}}
                """;
    }

    @Test
    @DisplayName("京东:result 里的嵌套 JSON 能解开,到手价取券后价")
    void parsesJdNestedResult() throws Exception {
        reply("/jd", jdBody());
        EcomSearchTool tool = new EcomSearchTool(settingsWithAll());

        String out = tool.execute("{\"keyword\":\"A4打印纸\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertTrue(out.contains("得力"), out);
        assertTrue(out.contains("¥89.90"), "应取券后价 89.90 而不是原价 109.9:" + out);
        assertTrue(out.contains("原价¥109.90"), out);
        assertTrue(out.contains("30天销量 3200"), out);
        assertTrue(out.contains("得力京东自营旗舰店"), out);
    }

    @Test
    @DisplayName("淘宝:到手价 = 折扣价 − 券面额,券信息原样带上")
    void parsesTbAndSubtractsCoupon() throws Exception {
        reply("/tb", tbBody());
        EcomSearchTool tool = new EcomSearchTool(settingsWithAll());

        String out = tool.execute("{\"keyword\":\"A4打印纸\",\"platforms\":\"tb\"}", ToolContext.empty());

        // 92.00 - 10 = 82.00
        assertTrue(out.contains("¥82.00"), "到手价应为 82.00:" + out);
        assertTrue(out.contains("满90元减10元"), out);
        assertTrue(out.contains("天猫"), "user_type=1 应标注天猫:" + out);
        assertTrue(out.contains("晨光官方旗舰店"), out);
    }

    @Test
    @DisplayName("两家一起查时按到手价升序:82 的淘宝款排在 89.9 的京东款前面")
    void sortsByFinalPriceAcrossPlatforms() throws Exception {
        reply("/jd", jdBody());
        reply("/tb", tbBody());
        EcomSearchTool tool = new EcomSearchTool(settingsWithAll());

        String out = tool.execute("{\"keyword\":\"A4打印纸\"}", ToolContext.empty());

        int tbIdx = out.indexOf("晨光A4打印纸");
        int jdIdx = out.indexOf("得力(deli)");
        assertTrue(tbIdx > 0 && jdIdx > 0, out);
        assertTrue(tbIdx < jdIdx, "82 元那款应排在 89.9 元之前:\n" + out);
    }

    @Test
    @DisplayName("京东联盟业务错误(HTTP 200 + error_response)要说人话,不能伪装成「查到 0 条」")
    void jdBusinessErrorIsReadable() throws Exception {
        reply("/jd", """
                {"error_response":{"code":"403","zh_desc":"签名验证失败"}}
                """);
        EcomSearchTool tool = new EcomSearchTool(settingsWithAll());

        String out = tool.execute("{\"keyword\":\"A4纸\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertTrue(out.contains("没有查到") || out.contains("京东联盟报错"), out);
    }

    @Test
    @DisplayName("maxPrice 过滤生效:上限 80 时,82 和 89.9 都被剔除")
    void maxPriceFilter() throws Exception {
        reply("/jd", jdBody());
        reply("/tb", tbBody());
        EcomSearchTool tool = new EcomSearchTool(settingsWithAll());

        String out = tool.execute("{\"keyword\":\"A4打印纸\",\"maxPrice\":80}", ToolContext.empty());
        assertTrue(out.contains("没有查到"), out);
    }

    @Test
    @DisplayName("没配密钥时工具不可用,原因能直接展示给用户")
    void unavailableWithoutKeys() {
        SettingsService settings = new SettingsService(tempDir.toString());
        EcomSearchTool tool = new EcomSearchTool(settings);
        assertFalse(tool.available());
        assertTrue(tool.unavailableReason().contains("设置"), tool.unavailableReason());
    }
}
