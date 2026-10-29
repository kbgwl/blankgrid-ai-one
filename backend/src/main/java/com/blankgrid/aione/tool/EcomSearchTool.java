package com.blankgrid.aione.tool;

import com.blankgrid.aione.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 电商比价:京东联盟 + 淘宝客,一次调用查全网。
 *
 * <p>两条硬约束,写在这里免得以后有人想"顺便把下单也做了":
 * <ul>
 *   <li>这两个接口是 CPS 推广体系,只返回商品信息和带 PID 的推广链接。</li>
 *   <li>没有下单接口、没有支付接口。付款必须由人来做,这是平台协议划的线。</li>
 * </ul>
 *
 * <p>所以这个工具的定位是「查得准」,不是「买得到」。把价格、销量、规格、链接给准,
 * 人就只差按一下 —— 这一下的责任留在人手里,反而是对的。
 */
@Component
public class EcomSearchTool implements AiTool {

    private static final Logger log = LoggerFactory.getLogger(EcomSearchTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private static final String JD_GATEWAY = "https://api.jd.com/routerjson";
    private static final String TB_GATEWAY = "https://eco.taobao.com/router/rest";

    private final SettingsService settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public EcomSearchTool(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "ecom_search";
    }

    @Override
    public String description() {
        return """
                在京东和淘宝按关键词搜索商品,返回真实到手价(券后价)、30 天销量、店铺名和购买链接。
                凡是涉及价格、销量、规格、比价的活,必须先用这个工具查,不要凭记忆报价。
                参数:keyword(必填,商品名,尽量带规格如"A4打印纸 70g 500张");platforms(默认 jd,tb 两个都查);
                limit(每个平台返回几条,默认 6);maxPrice(只要这个价格以下的,可选);
                sort(price=价格从低到高,默认;sales=销量从高到低)。
                返回结果已按到手价升序排好,可以直接用来做采购比价单。""";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object","properties":{
                  "keyword":{"type":"string","description":"搜索关键词,建议带规格与型号,越具体越准"},
                  "platforms":{"type":"string","description":"平台,逗号分隔:jd,tb。默认两个都查"},
                  "limit":{"type":"integer","description":"每个平台返回条数,默认 6,最多 20"},
                  "maxPrice":{"type":"number","description":"只要到手价不高于此值的结果"},
                  "sort":{"type":"string","enum":["price","sales"],"description":"排序:price 价格升序(默认),sales 销量降序"}
                },"required":["keyword"]}""";
    }

    @Override
    public String category() {
        return "ecommerce";
    }

    @Override
    public boolean available() {
        SettingsService.Ecom e = settings.get().ecom;
        return e != null && e.anyReady();
    }

    @Override
    public String unavailableReason() {
        return "还没配置电商联盟密钥。到「设置 → 电商联盟」填京东联盟或淘宝客的 AppKey/AppSecret"
                + "(京东还要推广位 PID 才能生成购买链接),填完这里就会自动可用。";
    }

    /** 归一化后的一个商品。两个平台的字段名完全不一样,先统一再排序。 */
    private record Item(String platform, String title, BigDecimal finalPrice, BigDecimal originalPrice,
                        String couponText, int sales30d, String shop, String url, String skuId,
                        BigDecimal commissionRate) {
        /** 到手价排序用的键;null 视为最贵,排最后 */
        BigDecimal sortKey() {
            return finalPrice == null ? new BigDecimal("99999999") : finalPrice;
        }
    }

    @Override
    public String execute(String argsJson, ToolContext ctx) throws Exception {
        JsonNode args = (argsJson == null || argsJson.isBlank()) ? mapper.createObjectNode() : mapper.readTree(argsJson);
        String keyword = args.path("keyword").asText("").trim();
        if (keyword.isEmpty()) {
            return "缺少 keyword 参数。请说明要搜什么商品,例如 {\"keyword\":\"A4打印纸 70g\"}。";
        }
        String platforms = args.path("platforms").asText("jd,tb").toLowerCase(Locale.ROOT);
        int limit = Math.min(20, Math.max(1, args.path("limit").asInt(6)));
        BigDecimal maxPrice = args.has("maxPrice") && !args.path("maxPrice").isNull()
                ? new BigDecimal(args.path("maxPrice").asText("0")) : null;
        boolean bySales = "sales".equalsIgnoreCase(args.path("sort").asText("price"));

        SettingsService.Ecom e = settings.get().ecom;
        boolean wantJd = platforms.contains("jd") && e.jdReady();
        boolean wantTb = platforms.contains("tb") && e.tbReady();

        if (!wantJd && !wantTb) {
            return "两家都没配好密钥,查不了。" + unavailableReason();
        }

        // 两家接口互不依赖,并行查 —— 串行会让一次比价等足两个网络往返
        CompletableFuture<List<Item>> jdF = wantJd
                ? CompletableFuture.supplyAsync(() -> safeSearch("京东", () -> searchJd(keyword, limit, bySales, e)))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<Item>> tbF = wantTb
                ? CompletableFuture.supplyAsync(() -> safeSearch("淘宝", () -> searchTb(keyword, limit, bySales, e)))
                : CompletableFuture.completedFuture(List.of());

        List<Item> all = new ArrayList<>();
        all.addAll(jdF.join());
        all.addAll(tbF.join());

        if (maxPrice != null) {
            BigDecimal cap = maxPrice;
            all.removeIf(i -> i.finalPrice() == null || i.finalPrice().compareTo(cap) > 0);
        }
        if (all.isEmpty()) {
            return "「" + keyword + "」在" + (wantJd ? "京东" : "") + (wantJd && wantTb ? "、" : "")
                    + (wantTb ? "淘宝" : "") + "没有查到符合条件的商品。"
                    + (maxPrice != null ? "可能是价格上限设得太低,可以放宽到 " + maxPrice.multiply(new BigDecimal("1.5")).setScale(0, RoundingMode.HALF_UP) + " 再试。" : "建议换个更通用的关键词(去掉型号、颜色、包装数)。");
        }

        all.sort(bySales
                ? Comparator.comparingInt(Item::sales30d).reversed()
                : Comparator.comparing(Item::sortKey));

        return render(keyword, all, wantJd, wantTb, bySales);
    }

    /** 单个平台失败不能把整个比价拖垮:另一家的数据照样有用。 */
    private List<Item> safeSearch(String name, java.util.concurrent.Callable<List<Item>> task) {
        try {
            return task.call();
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("{} 搜索失败:{}", name, msg);
            return List.of();
        }
    }

    // ==================== 京东联盟 ====================

    private List<Item> searchJd(String keyword, int limit, boolean bySales, SettingsService.Ecom e) throws Exception {
        Map<String, Object> goodsReq = new LinkedHashMap<>();
        goodsReq.put("keyword", keyword);
        goodsReq.put("pageIndex", 1);
        goodsReq.put("pageSize", Math.max(limit, 10));
        if (bySales) {
            goodsReq.put("sortName", "inOrderCount30Days");
            goodsReq.put("sort", "desc");
        } else {
            goodsReq.put("sortName", "price");
            goodsReq.put("sort", "asc");
        }
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("goodsReq", goodsReq);
        String bizJson = mapper.writeValueAsString(biz);

        TreeMap<String, String> p = new TreeMap<>();
        p.put("method", "jd.union.open.goods.jingfen.query");
        p.put("app_key", e.jdAppKey.trim());
        p.put("timestamp", ZonedDateTime.now(CST).format(TS));
        p.put("format", "json");
        p.put("v", "1.0");
        p.put("sign_method", "md5");
        p.put("360buy_param_json", bizJson);
        p.put("sign", md5Sign(p, e.jdAppSecret.trim()));

        String gateway = (e.jdGateway == null || e.jdGateway.isBlank()) ? JD_GATEWAY : e.jdGateway.trim();
        String body = formEncode(p);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gateway))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("京东返回 HTTP " + resp.statusCode() + ":" + clip(resp.body(), 200));
        }
        JsonNode root = mapper.readTree(resp.body());

        // 联盟的错误是 200 + 错误码,不是 HTTP 错误。不认这个只会看到"查出 0 条"
        JsonNode errResp = root.path("error_response");
        if (!errResp.isMissingNode() && !errResp.isNull() && errResp.isObject()) {
            String msg = errResp.path("zh_desc").asText(errResp.path("code").asText(""));
            throw new IllegalStateException("京东联盟报错:" + clip(msg, 160)
                    + "(常见原因:AppKey 未申请商品查询权限、签名错、IP 未加白)");
        }

        // result 是个 JSON 字符串,要再解一次。key 可能是 _response 也可能是 _responce(官方拼写就这样)
        JsonNode payload = null;
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String k = names.next();
            if (k.startsWith("jd_union_open_goods_jingfen") && k.endsWith("response")) {
                JsonNode wrapper = root.path(k);
                String inner = wrapper.path("result").asText("");
                payload = inner.isEmpty() ? wrapper : mapper.readTree(inner);
                break;
            }
        }
        if (payload == null) {
            JsonNode g = root.path("jd_union_open_goods_jingfen_query_responce").path("result");
            String inner = g.asText("");
            payload = inner.isEmpty() ? root : mapper.readTree(inner);
        }
        if (payload.has("code") && payload.path("code").asInt(200) != 200) {
            throw new IllegalStateException("京东联盟返回码 " + payload.path("code").asInt()
                    + ":" + clip(payload.path("message").asText(""), 160));
        }

        List<Item> out = new ArrayList<>();
        JsonNode data = payload.path("data");
        if (data.isArray()) {
            for (JsonNode n : data) {
                String title = n.path("skuName").asText("");
                if (title.isBlank()) continue;
                JsonNode price = n.path("priceInfo");
                BigDecimal finalPrice = dec(price.path("lowestCouponPrice").asText(""));
                if (finalPrice == null) finalPrice = dec(price.path("lowestPrice").asText(""));
                if (finalPrice == null) finalPrice = dec(price.path("price").asText(""));
                BigDecimal original = dec(price.path("price").asText(""));
                String shop = n.path("shopInfo").path("shopName").asText("");
                int sales = n.path("inOrderCount30Days").asInt(0);
                String url = n.path("materialUrl").asText("");
                if (url.isBlank()) url = n.path("goodsUrl").asText("");
                if (url.isBlank()) {
                    String sku = n.path("skuId").asText("");
                    if (!sku.isBlank()) url = "https://item.jd.com/" + sku + ".html";
                }
                BigDecimal coupon = dec(price.path("couponInfo").path("couponList").path(0).path("discount").asText(""));
                String couponText = (coupon != null && coupon.compareTo(BigDecimal.ZERO) > 0)
                        ? "券 ¥" + coupon.stripTrailingZeros().toPlainString() : "";
                out.add(new Item("京东", title, finalPrice, original, couponText, sales, shop, url,
                        n.path("skuId").asText(""), dec(n.path("commissionInfo").path("commissionShare").asText(""))));
            }
        }
        return out;
    }

    // ==================== 淘宝客 ====================

    private List<Item> searchTb(String keyword, int limit, boolean bySales, SettingsService.Ecom e) throws Exception {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("method", "taobao.tbk.dg.material.optional");
        p.put("app_key", e.tbAppKey.trim());
        p.put("timestamp", ZonedDateTime.now(CST).format(TS));
        p.put("format", "json");
        p.put("v", "2.0");
        p.put("sign_method", "md5");
        p.put("q", keyword);
        p.put("adzone_id", e.tbAdzoneId.trim());
        p.put("page_no", "1");
        p.put("page_size", String.valueOf(Math.max(limit, 10)));
        // 采购场景：只看得见有券的，基本等于只看真实优惠价
        if (!Boolean.FALSE.equals(e.couponOnly)) p.put("has_coupon", "true");
        p.put("sort", bySales ? "tk_total_sales_des" : "price_asc");
        p.put("sign", md5Sign(p, e.tbAppSecret.trim()));

        String gateway = (e.tbGateway == null || e.tbGateway.isBlank()) ? TB_GATEWAY : e.tbGateway.trim();
        String url = gateway + "?" + formEncode(p);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("淘宝返回 HTTP " + resp.statusCode() + ":" + clip(resp.body(), 200));
        }
        JsonNode root = mapper.readTree(resp.body());

        JsonNode err = root.path("error_response");
        if (!err.isMissingNode() && !err.isNull() && err.isObject()) {
            String msg = err.path("sub_msg").asText(err.path("msg").asText(""));
            throw new IllegalStateException("淘宝客报错:" + clip(msg, 160)
                    + "(常见原因:应用未备案、adzone_id 与 AppKey 不匹配、权限包未申请)");
        }

        JsonNode block = root.path("tbk_dg_material_optional_response");
        JsonNode list = block.path("result_list").path("map_data");
        List<Item> out = new ArrayList<>();
        if (!list.isArray()) return out;
        for (JsonNode n : list) {
            String title = n.path("title").asText("");
            if (title.isBlank()) continue;
            BigDecimal zk = dec(n.path("zk_final_price").asText(""));
            BigDecimal coupon = dec(n.path("coupon_amount").asText(""));
            BigDecimal finalPrice = zk;
            if (zk != null && coupon != null && coupon.compareTo(BigDecimal.ZERO) > 0) {
                finalPrice = zk.subtract(coupon).max(BigDecimal.ZERO);
            }
            String couponInfo = n.path("coupon_info").asText("");
            String shop = n.path("shop_title").asText("");
            // user_type: 0 集市店, 1 天猫 —— 采购时天猫通常更看重发票与售后
            if (n.path("user_type").asInt(0) == 1 && !shop.contains("天猫")) shop = shop + "(天猫)";
            String itemUrl = n.path("item_url").asText("");
            if (itemUrl.isBlank()) {
                String id = n.path("item_id").asText("");
                if (!id.isBlank()) itemUrl = "https://item.taobao.com/item.htm?id=" + id;
            }
            out.add(new Item("淘宝", title, finalPrice, zk, couponInfo.isBlank() && coupon != null
                    ? "券 ¥" + coupon.stripTrailingZeros().toPlainString(): couponInfo,
                    n.path("volume").asInt(0), shop, itemUrl, n.path("item_id").asText(""),
                    dec(n.path("commission_rate").asText(""))));
        }
        return out;
    }

    // ==================== 输出 ====================

    /**
     * 渲染成模型好读的紧凑文本。
     *
     * <p>刻意不用 JSON:表格化文本对模型更友好,token 也少一半。
     * 采购单最终要给人看,顺手把"每单位价格"留白给模型用 calc 工具算,别在这里猜规格。
     */
    private String render(String keyword, List<Item> items, boolean hasJd, boolean hasTb, boolean bySales) {
        StringBuilder sb = new StringBuilder();
        sb.append("【比价结果】「").append(keyword).append("」 共 ").append(items.size()).append(" 条")
          .append(" · 平台:").append(hasJd ? "京东" : "").append(hasJd && hasTb ? "+" : "").append(hasTb ? "淘宝" : "")
          .append(" · 按").append(bySales ? "30天销量" : "到手价").append("排序\n");
        sb.append("(到手价 = 券后实付,已含平台折扣;不含运费,下单前请在结算页确认)\n\n");

        int i = 1;
        for (Item it : items) {
            sb.append(i++).append(". [").append(it.platform()).append("] ");
            if (it.finalPrice() != null) {
                sb.append("¥").append(money(it.finalPrice()));
                if (it.originalPrice() != null && it.finalPrice().compareTo(it.originalPrice()) < 0) {
                    sb.append(" (原价¥").append(money(it.originalPrice())).append(")");
                }
            } else {
                sb.append("价格未返回");
            }
            if (it.couponText() != null && !it.couponText().isBlank()) sb.append(" ").append(it.couponText());
            if (it.sales30d() > 0) sb.append(" · 30天销量 ").append(it.sales30d());
            sb.append("\n   ").append(it.title());
            if (!it.shop().isBlank()) sb.append("\n   店铺:").append(it.shop());
            if (it.url() != null && !it.url().isBlank()) sb.append("\n   ").append(it.url());
            sb.append("\n");
        }
        sb.append("\n提示:销量低但价格明显更低的,可能是小店或无票渠道,采购时要一并说明发票与售后风险。");
        return sb.toString();
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 签名 ====================

    /**
     * 京东与淘宝用的是同一套签名规则:参数按名排序 → 拼成 key1value1key2value2 →
     * 前后各接一次 AppSecret → MD5 → 大写。
     *
     * <p>两个坑都踩过:参数值<b>不能</b>先 URL 编码再参与签名(否则永远对不上),
     * 空值参数<b>不</b>参与签名。
     */
    static String md5Sign(TreeMap<String, String> params, String secret) throws Exception {
        StringBuilder raw = new StringBuilder(secret);
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("sign".equals(e.getKey())) continue;
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            raw.append(e.getKey()).append(v);
        }
        raw.append(secret);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02X", b));
        return hex.toString();
    }

    private static String formEncode(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 供设置页「测试连通」用:只搜一条,验证密钥与权限是否真的通。 */
    public String selfTest() {
        SettingsService.Ecom e = settings.get().ecom;
        if (e == null || !e.anyReady()) return "还没配置任何联盟密钥";
        StringBuilder sb = new StringBuilder();
        if (e.jdReady()) {
            try {
                List<Item> items = searchJd("抽纸", 1, false, e);
                sb.append("京东:连通,样例 ").append(items.isEmpty() ? "0 条(可能该词无联盟商品)" : items.size() + " 条").append("\n");
            } catch (Exception ex) {
                sb.append("京东:失败 — ").append(clip(ex.getMessage(), 160)).append("\n");
            }
        }
        if (e.tbReady()) {
            try {
                List<Item> items = searchTb("抽纸", 1, false, e);
                sb.append("淘宝:连通,样例 ").append(items.isEmpty() ? "0 条" : items.size() + " 条").append("\n");
            } catch (Exception ex) {
                sb.append("淘宝:失败 — ").append(clip(ex.getMessage(), 160)).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
