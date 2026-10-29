package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 采购场景真正会翻车的地方不是"查不到",而是"查到了但认错货"。
 *
 * <p>三件事必须钉死:关键词搜出来的标题要能识别翻新/港版/合约机;
 * 拿到具体商品编号要能精准核价(而不是还在模糊搜);店铺要能看出能不能开专票。
 */
class EcomPrecisionTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper M = new ObjectMapper();
    private HttpServer server;
    private String base;
    /** 记录两边最后收到的请求参数,用来断言"走的是哪个接口" */
    private final AtomicReference<Map<String, String>> lastJd = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, String>> lastTb = new AtomicReference<>(Map.of());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void jd(String json) {
        server.createContext("/jd", (HttpExchange ex) -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastJd.set(parseForm(body));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
    }

    private void tb(String json) {
        server.createContext("/tb", (HttpExchange ex) -> {
            lastTb.set(parseForm(ex.getRequestURI().getQuery()));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
    }

    private SettingsService settings() {
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

    /** 三条搜索结果:国行自营、港版第三方、官换机。价格故意让风险款最便宜 */
    private String jdSearchBody() throws Exception {
        ObjectMapper m = new ObjectMapper();
        var data = m.createObjectNode();
        var arr = data.putArray("data");

        var a = arr.addObject();
        a.put("skuName", "Apple iPhone 17 Pro 256GB 深蓝色钛金属 国行全网通");
        a.put("skuId", "100001");
        a.putObject("priceInfo").put("price", 8999).put("lowestCouponPrice", 8899);
        a.putObject("shopInfo").put("shopName", "Apple产品京东自营旗舰店");
        a.put("inOrderCount30Days", 860);
        // 故意给一条推广短链:输出里必须换成 skuId 直链,不能让用户多点一次跳转
        a.put("materialUrl", "https://u.jd.com/AbCdEf");

        var b = arr.addObject();
        b.put("skuName", "iPhone 17 Pro 256G 港版 全新未激活 代购");
        b.put("skuId", "100002");
        b.putObject("priceInfo").put("price", 8299);
        b.putObject("shopInfo").put("shopName", "香港数码代购店");
        b.put("inOrderCount30Days", 12);
        b.put("materialUrl", "https://item.jd.com/100002.html");

        var c = arr.addObject();
        c.put("skuName", "苹果 iPhone 17 Pro 256G 官换机 99新 资源机");
        c.put("skuId", "100003");
        c.putObject("priceInfo").put("price", 6199);
        c.putObject("shopInfo").put("shopName", "某数码专营店");
        c.put("inOrderCount30Days", 40);
        c.put("materialUrl", "https://item.jd.com/100003.html");
        data.put("code", 200);

        var root = m.createObjectNode();
        root.putObject("jd_union_open_goods_jingfen_query_response")
                .put("result", m.writeValueAsString(data));
        return m.writeValueAsString(root);
    }

    private String jdSkuBody() throws Exception {
        ObjectMapper m = new ObjectMapper();
        var data = m.createObjectNode();
        var arr = data.putArray("data");
        var a = arr.addObject();
        a.put("skuName", "Apple iPhone 17 Pro Max 512GB 银色");
        a.put("skuId", "100099");
        a.putObject("priceInfo").put("price", 11999).put("lowestPrice", 11699);
        a.putObject("shopInfo").put("shopName", "Apple产品京东自营旗舰店");
        a.put("inOrderCount30Days", 300);
        a.put("materialUrl", "https://item.jd.com/100099.html");
        data.put("code", 200);

        var root = m.createObjectNode();
        root.putObject("jd_union_open_goods_query_response").put("result", m.writeValueAsString(data));
        return m.writeValueAsString(root);
    }

    @Test
    @DisplayName("关键词搜索:港版/官换机要标 ⚠ 并排到最后,国行自营排第一")
    void flagsRiskyItemsAndSortsThemLast() throws Exception {
        jd(jdSearchBody());
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"keyword\":\"iPhone 17 Pro 256G\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertTrue(out.contains("⚠"), "港版与官换机都要被打标:\n" + out);
        assertTrue(out.contains("非国行"), "港版应标注非国行:\n" + out);
        assertTrue(out.contains("非全新机"), "官换机/99新应标注非全新:\n" + out);
        assertTrue(out.contains("2 条带风险标记"), "摘要行要给出风险条数:\n" + out);

        // 国行自营 8899 比港版 8299 贵,但必须排在前面 —— 便宜 600 换来没保修,不值。
        // 两条风险款之间仍按价格排(谁更便宜谁在前),这个顺序不重要,重要是有风险的都在后面。
        int guohang = out.indexOf("100001");
        int gangban = out.indexOf("100002");
        int guanhuan = out.indexOf("100003");
        assertTrue(guohang > 0 && gangban > 0 && guanhuan > 0, out);
        assertTrue(guohang < gangban, "国行应排在港版之前:\n" + out);
        assertTrue(guohang < guanhuan, "国行应排在官换机之前:\n" + out);
    }

    @Test
    @DisplayName("店铺性质:京东自营标注可开专票,第三方店提示发票风险")
    void tagsShopNature() throws Exception {
        jd(jdSearchBody());
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"keyword\":\"iPhone\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertTrue(out.contains("自营(可开专票)"), out);
        assertTrue(out.contains("第三方店(发票/售后由商家承担)"), out);
    }

    @Test
    @DisplayName("精准核价:传 skuIds 走商品详情接口,不再走关键词搜索")
    void preciseBySkuId() throws Exception {
        jd(jdSkuBody());
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"skuIds\":\"100099\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertEquals("jd.union.open.goods.query", lastJd.get().get("method"),
                "传了 skuIds 就该走商品详情接口:" + lastJd.get());
        JsonNode biz = M.readTree(lastJd.get().get("360buy_param_json"));
        assertEquals(100099L, biz.path("goodsReqDTO").path("skuIds").path(0).asLong(),
                "skuIds 要原样传进去:" + biz);
        assertTrue(out.contains("iPhone 17 Pro Max 512GB"), out);
        assertTrue(out.contains("¥11699.00"), "有 lowestPrice 时优先用它:" + out);
    }

    /** 从整条商品链接里抠编号 —— 用户经常直接粘链接 */
    @Test
    @DisplayName("skuIds 传整条链接也能抠出商品编号")
    void skuIdExtractedFromUrl() throws Exception {
        jd(jdSkuBody());
        EcomSearchTool tool = new EcomSearchTool(settings());

        tool.execute("{\"skuIds\":\"https://item.jd.com/100099.html\",\"platforms\":\"jd\"}", ToolContext.empty());

        JsonNode biz = M.readTree(lastJd.get().get("360buy_param_json"));
        assertEquals(100099L, biz.path("goodsReqDTO").path("skuIds").path(0).asLong());
    }

    @Test
    @DisplayName("精准核价:传 itemIds 走淘宝商品详情接口")
    void preciseByItemId() throws Exception {
        tb("""
                {"tbk_item_info_get_response":{"results":{"n_tbk_item":[
                  {"num_iid":601,"title":"Apple iPhone 17 Pro 256GB 国行 全新未拆封",
                   "zk_final_price":"8999.00","item_url":"https://item.taobao.com/item.htm?id=601",
                   "nick":"Apple Store 官方旗舰店"}
                ]}}}
                """);
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"itemIds\":\"601\"}", ToolContext.empty());

        assertEquals("taobao.tbk.item.info.get", lastTb.get().get("method"), lastTb.get().toString());
        assertEquals("601", lastTb.get().get("num_iids"));
        assertTrue(out.contains("8999.00"), out);
        assertTrue(out.contains("Apple Store 官方旗舰店"), "店铺名要能从 nick 兜底取到:" + out);
    }

    @Test
    @DisplayName("既没给关键词也没给编号:要求补充,不能空跑")
    void requiresSomeIdentifier() throws Exception {
        EcomSearchTool tool = new EcomSearchTool(settings());
        String out = tool.execute("{}", ToolContext.empty());
        assertTrue(out.contains("keyword"), out);
        assertTrue(out.contains("skuIds"), out);
    }

    @Test
    @DisplayName("风险词识别:普通商品不能误伤")
    void riskWordsDoNotOverTrigger() {
        assertEquals("", EcomSearchTool.detectRisk("Apple iPhone 17 Pro 256GB 国行全网通"));
        assertEquals("", EcomSearchTool.detectRisk("得力 A4打印纸 70g 500张/包"));
        assertEquals("", EcomSearchTool.detectRisk(""));
        assertNotEquals("", EcomSearchTool.detectRisk("iPhone 17 美版 有锁"));
        assertNotEquals("", EcomSearchTool.detectRisk("华强北 组装 苹果"));
    }

    @Test
    @DisplayName("输出末尾必须写明不代下单、不承担售后")
    void statesBoundary() throws Exception {
        jd(jdSearchBody());
        EcomSearchTool tool = new EcomSearchTool(settings());
        String out = tool.execute("{\"keyword\":\"iPhone\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertTrue(out.contains("不代下单"), out);
        assertTrue(out.contains("激活或拆封后不支持 7 天无理由"), out);
    }

    /**
     * 「点开就能下单」的关键:链到的是**那一款商品的购买页**,不是搜索结果页、不是店铺页。
     * 京东的 skuId 本身就是最小销售单位(一色一容量一个 id),拼出来的地址打开就是那款,
     * 不用再选规格;联盟给的 u.jd.com 短链要跳一次、还可能落活动页,所以不能用它当主链接。
     */
    @Test
    @DisplayName("下单直链:京东用 skuId 直拼,推广短链不进主链接")
    void jdBuyUrlIsSkuPage() throws Exception {
        jd(jdSearchBody());
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"keyword\":\"iPhone\",\"platforms\":\"jd\"}", ToolContext.empty());

        assertTrue(out.contains("下单:https://item.jd.com/100001.html"), "应给出 skuId 直链:\n" + out);
        assertFalse(out.contains("u.jd.com"), "推广短链不该出现在下单地址里:\n" + out);
    }

    @Test
    @DisplayName("下单直链:天猫店走 detail.tmall.com,集市店走 item.taobao.com")
    void tbBuyUrlByShopType() throws Exception {
        tb("""
                {"tbk_dg_material_optional_response":{"result_list":{"map_data":[
                  {"title":"Apple iPhone 17 Pro 256GB 国行","item_id":"7001","zk_final_price":"8999.00",
                   "coupon_amount":"0","volume":320,"shop_title":"Apple Store 官方旗舰店",
                   "item_url":"https://s.click.taobao.com/xyz","user_type":1},
                  {"title":"iPhone 17 Pro 256G 国行 全新","item_id":"7002","zk_final_price":"8899.00",
                   "coupon_amount":"0","volume":60,"shop_title":"某数码店",
                   "item_url":"https://s.click.taobao.com/abc","user_type":0}
                ]}}}
                """);
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"keyword\":\"iPhone\",\"platforms\":\"tb\"}", ToolContext.empty());

        assertTrue(out.contains("下单:https://detail.tmall.com/item.htm?id=7001"),
                "天猫店应给 detail.tmall.com 直链:\n" + out);
        assertTrue(out.contains("下单:https://item.taobao.com/item.htm?id=7002"),
                "集市店应给 item.taobao.com 直链:\n" + out);
        assertFalse(out.contains("s.click.taobao.com"), "推广短链不该出现在下单地址里:\n" + out);
    }

    @Test
    @DisplayName("没有商品编号时才退回推广链接,总比给不出链接强")
    void fallsBackToPromoUrlWithoutId() throws Exception {
        tb("""
                {"tbk_dg_material_optional_response":{"result_list":{"map_data":[
                  {"title":"无编号商品","zk_final_price":"10.00","shop_title":"某店",
                   "item_url":"https://s.click.taobao.com/only","user_type":0}
                ]}}}
                """);
        EcomSearchTool tool = new EcomSearchTool(settings());

        String out = tool.execute("{\"keyword\":\"随便\",\"platforms\":\"tb\"}", ToolContext.empty());

        assertTrue(out.contains("下单:https://s.click.taobao.com/only"), out);
    }
}
