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
                在京东和淘宝查商品真实到手价(券后价)、30 天销量、店铺性质和购买链接。
                凡是涉及价格、销量、规格、比价的活,必须先用这个工具查,不要凭记忆报价。
                两种用法:
                1) 关键词搜索:传 keyword,适合"先看看市面上有什么、什么价"。
                2) 精准核价:传 skuIds(京东商品编号)或 itemIds(淘宝商品 id),返回的就是那一个确定型号的价格。
                   已知具体商品链接时用这个 —— 关键词搜出来的标题是模糊匹配,同代不同容量/颜色会混在一起。
                参数:keyword 与 skuIds/itemIds 至少给一个;platforms 默认 jd,tb 都查;
                limit 每个平台返回几条(默认 6);maxPrice 价格上限;sort(price 价格升序,默认 / sales 销量降序)。
                返回结果按到手价升序排好,非全新/非国行/合约机等风险款已自动标 ⚠ 并排到最后。""";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object","properties":{
                  "keyword":{"type":"string","description":"搜索关键词,建议带完整型号与规格,如 iPhone 17 Pro 256G 国行"},
                  "skuIds":{"type":"string","description":"京东商品编号(skuId),逗号分隔。精准核价用,给了就走商品详情接口"},
                  "itemIds":{"type":"string","description":"淘宝商品 id(num_iid),逗号分隔。精准核价用"},
                  "platforms":{"type":"string","description":"平台,逗号分隔:jd,tb。默认两个都查"},
                  "limit":{"type":"integer","description":"每个平台返回条数,默认 6,最多 20"},
                  "maxPrice":{"type":"number","description":"只要到手价不高于此值的结果"},
                  "sort":{"type":"string","enum":["price","sales"],"description":"排序:price 价格升序(默认),sales 销量降序"}
                }}""";
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

    /**
     * 标题里出现这些词,基本等于「不是你以为的那个商品」。
     *
     * <p>采购场景最贵的错误不是买贵,是买错:10 部港版 iPhone 发下去,激活了不能退,
     * 无官方保修,报销还卡发票。这些词在标题里都是明写的,机器一眼能挑出来,
     * 靠人一条条看 20 个商品标题反而会漏。
     */
    private static final String[][] RISK_WORDS = {
            {"港版", "美版", "日版", "韩版", "欧版", "亚太版", "海外版", "国际版", "水货", "外版", "非国行", "美行", "港行"},
            {"官换", "官翻", "翻新", "二手", "拆封", "开封机", "99新", "95新", "9成新", "展示机", "样机", "演示机", "资源机", "BS机", "组装机"},
            {"合约机", "有锁", "卡贴", "运营商定制", "合约套餐", "绑定套餐"},
            {"山寨", "高仿", "精仿", "华强北", "原装拆机", "配件机", "无包装"},
    };

    private static final String[] RISK_LEVELS = {"非国行(国内无官方保修)", "非全新机", "合约/有锁机", "疑似山寨或拼装"};

    /** 命中返回风险说明,没命中返回空串。标题里的字面词,不需要模型判断。 */
    static String detectRisk(String title) {
        if (title == null || title.isBlank()) return "";
        for (int i = 0; i < RISK_WORDS.length; i++) {
            for (String w : RISK_WORDS[i]) {
                if (title.contains(w)) return RISK_LEVELS[i] + ":" + w;
            }
        }
        return "";
    }

    /**
     * 店铺性质。企业采购真正卡人的是这两个字:发票。
     * 自营/旗舰店能开专票、售后走平台;第三方小店往往只能开普票,出问题扯皮。
     */
    static String shopTag(String platform, String shop, boolean tmall) {
        if (shop == null || shop.isBlank()) return "";
        if ("京东".equals(platform)) {
            if (shop.contains("自营")) return "自营(可开专票)";
            if (shop.contains("旗舰店")) return "旗舰店";
            if (shop.contains("专营店") || shop.contains("专卖店")) return "专卖店";
            return "第三方店(发票/售后由商家承担)";
        }
        if (tmall || shop.contains("天猫")) return "天猫";
        if (shop.contains("旗舰店")) return "旗舰店";
        if (shop.contains("专营店") || shop.contains("专卖店")) return "专卖店";
        return "淘宝C店(多为普票,慎选)";
    }

    /** 归一化后的一个商品。两个平台的字段名完全不一样,先统一再排序。 */
    private record Item(String platform, String title, BigDecimal finalPrice, BigDecimal originalPrice,
                        String couponText, int sales30d, String shop, String url, String skuId,
                        BigDecimal commissionRate, String risk, boolean tmall, String promoUrl) {
        /** 到手价排序用的键;null 视为最贵,排最后 */
        BigDecimal sortKey() {
            return finalPrice == null ? new BigDecimal("99999999") : finalPrice;
        }

        boolean risky() {
            return risk != null && !risk.isBlank();
        }
    }

    /**
     * 生成「点开就是这一款、点一下就下单」的直链。
     *
     * <p>关键差别在有没有把规格写进 URL:
     * <ul>
     *   <li>京东的 skuId 本身就是最小销售单位 —— 一个颜色一个容量一个 id,
     *       所以 item.jd.com/{skuId}.html 打开就是那一款,不用再选规格。</li>
     *   <li>淘宝/天猫的 itemId 是 SPU 级,打开还要选颜色容量。{@code detail.tmall.com}
     *       比 {@code item.taobao.com} 少一次跳转,天猫店优先走它。</li>
     *   <li>联盟给的推广短链(u.jd.com / s.click.taobao.com)会先经过跳转,还可能落到活动页 ——
     *       能拿返佣,但采购时要的是"立刻能下单",所以这里放直链,推广链单独留着备用。</li>
     * </ul>
     *
     * <p>上线前唯一的硬约束:平台自己的登录与支付没人能替,链接最多把你送到"立即购买"按钮前面。
     */
    private static String buyUrl(String platform, String id, boolean tmall) {
        if (id == null || id.isBlank() || !id.matches("\\d+")) return "";
        if ("京东".equals(platform)) return "https://item.jd.com/" + id + ".html";
        return tmall
                ? "https://detail.tmall.com/item.htm?id=" + id
                : "https://item.taobao.com/item.htm?id=" + id;
    }

    /** 拿不到编号时,退回接口给的推广链接 —— 有跳转,但总比没有强。 */
    private static String pickUrl(String direct, String promo) {
        return direct != null && !direct.isBlank() ? direct
                : (promo == null ? "" : promo.trim());
    }

    @Override
    public String execute(String argsJson, ToolContext ctx) throws Exception {
        JsonNode args = (argsJson == null || argsJson.isBlank()) ? mapper.createObjectNode() : mapper.readTree(argsJson);
        String keyword = args.path("keyword").asText("").trim();
        String skuIds = args.path("skuIds").asText("").trim();
        String itemIds = args.path("itemIds").asText("").trim();
        boolean precise = !skuIds.isEmpty() || !itemIds.isEmpty();
        if (keyword.isEmpty() && !precise) {
            return "缺少 keyword 参数。请说明要查什么商品,例如 {\"keyword\":\"A4打印纸 70g\"};"
                    + "如果已知具体商品,也可以直接传 skuIds(京东)/ itemIds(淘宝) 做精准核价。";
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

        String jdKeyword = keyword;
        String tbKeyword = keyword;
        // 两家接口互不依赖,并行查 —— 串行会让一次比价等足两个网络往返
        CompletableFuture<List<Item>> jdF = wantJd
                ? CompletableFuture.supplyAsync(() -> safeSearch("京东", () -> skuIds.isEmpty()
                        ? searchJd(jdKeyword, limit, bySales, e)
                        : searchJdBySku(skuIds, limit, e)))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<Item>> tbF = wantTb
                ? CompletableFuture.supplyAsync(() -> safeSearch("淘宝", () -> itemIds.isEmpty()
                        ? searchTb(tbKeyword, limit, bySales, e)
                        : searchTbByItem(itemIds, limit, e)))
                : CompletableFuture.completedFuture(List.of());

        List<Item> all = new ArrayList<>();
        all.addAll(jdF.join());
        all.addAll(tbF.join());

        if (maxPrice != null) {
            BigDecimal cap = maxPrice;
            all.removeIf(i -> i.finalPrice() == null || i.finalPrice().compareTo(cap) > 0);
        }
        if (all.isEmpty()) {
            String what = !keyword.isEmpty() ? "「" + keyword + "」" : "这些商品编号";
            return what + "在" + (wantJd ? "京东" : "") + (wantJd && wantTb ? "、" : "")
                    + (wantTb ? "淘宝" : "") + "没有查到符合条件的商品。"
                    + (maxPrice != null ? "可能是价格上限设得太低,可以放宽到 " + maxPrice.multiply(new BigDecimal("1.5")).setScale(0, RoundingMode.HALF_UP) + " 再试。" : "建议换个更通用的关键词(去掉型号、颜色、包装数)。")
                    + (precise ? "另外检查一下 skuIds/itemIds 是否与平台对应(京东编号和淘宝 id 不通用)。" : "");
        }

        // 先按价格/销量排,风险款稳定地挪到最后。
        // 不剔除是为了让人看见"便宜 2000 的那款是什么" —— 直接藏掉反而会被问"为什么没查到这个价"。
        Comparator<Item> key = bySales
                ? Comparator.comparingInt(Item::sales30d).reversed()
                : Comparator.comparing(Item::sortKey);
        all.sort(Comparator.comparing(Item::risky).thenComparing(key));

        return render(keyword.isEmpty() ? "精准核价 " + (skuIds.isEmpty() ? itemIds : skuIds) : keyword,
                all, wantJd, wantTb, bySales);
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
        return parseJdData(jdCall("jd.union.open.goods.jingfen.query", biz, e));
    }

    /**
     * 按 skuId 精准查。区别在于:京粉精选只覆盖"已进推广池"的商品,
     * 而商品详情查询是按编号点名要,型号、规格、价格都不会串。
     * 拿到具体商品链接后想确认"就是这个型号多少钱",走这条路。
     */
    private List<Item> searchJdBySku(String skuIds, int limit, SettingsService.Ecom e) throws Exception {
        List<Object> ids = new ArrayList<>();
        for (String s : skuIds.split("[,，\\s]+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException ex) {
                // 有人会把整条链接塞进来,顺手把数字抠出来
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6,})").matcher(t);
                if (m.find()) ids.add(Long.parseLong(m.group(1)));
            }
            if (ids.size() >= Math.min(limit, 20)) break;
        }
        if (ids.isEmpty()) throw new IllegalStateException("skuIds 里没有合法的京东商品编号(应是纯数字,如 100011)");
        Map<String, Object> goodsReq = new LinkedHashMap<>();
        goodsReq.put("skuIds", ids);
        goodsReq.put("pageIndex", 1);
        goodsReq.put("pageSize", ids.size());
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("goodsReqDTO", goodsReq);
        return parseJdData(jdCall("jd.union.open.goods.query", biz, e));
    }

    /** 京东联盟调用:签名、网关、业务错误识别都在这里,两个查询方法共用。 */
    private JsonNode jdCall(String method, Map<String, Object> biz, SettingsService.Ecom e) throws Exception {
        String bizJson = mapper.writeValueAsString(biz);

        TreeMap<String, String> p = new TreeMap<>();
        p.put("method", method);
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
        return extractJdPayload(root);
    }

    /**
     * 从响应里挖出真正的结果对象。
     *
     * <p>要点:外层 key 是方法名派生的(如 jd_union_open_goods_jingfen_query_response),
     * 且官方历史上把 response 拼成过 responce;result 里有时是 JSON 字符串要再解一层。
     */
    private JsonNode extractJdPayload(JsonNode root) throws Exception {
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String k = names.next();
            if (k.startsWith("jd_union_open_goods_") && (k.endsWith("response") || k.endsWith("responce"))) {
                JsonNode wrapper = root.path(k);
                String inner = wrapper.path("result").asText("");
                return inner.isEmpty() ? wrapper : mapper.readTree(inner);
            }
        }
        // 兜底:不认 key 名就找任意第一个带 result 的节点,别再写死一个拼错的 key
        names = root.fieldNames();
        while (names.hasNext()) {
            JsonNode wrapper = root.path(names.next());
            String inner = wrapper.path("result").asText("");
            if (!inner.isEmpty()) return mapper.readTree(inner);
        }
        return root;
    }

    private List<Item> parseJdData(JsonNode payload) {
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
                String promo = n.path("materialUrl").asText("");
                if (promo.isBlank()) promo = n.path("goodsUrl").asText("");
                String skuId = n.path("skuId").asText("");
                BigDecimal coupon = dec(price.path("couponInfo").path("couponList").path(0).path("discount").asText(""));
                String couponText = (coupon != null && coupon.compareTo(BigDecimal.ZERO) > 0)
                        ? "券 ¥" + coupon.stripTrailingZeros().toPlainString() : "";
                out.add(new Item("京东", title, finalPrice, original, couponText, sales, shop,
                        pickUrl(buyUrl("京东", skuId, false), promo),
                        skuId, dec(n.path("commissionInfo").path("commissionShare").asText("")),
                        detectRisk(title), false, promo));
            }
        }
        return out;
    }

    // ==================== 淘宝客 ====================

    private List<Item> searchTb(String keyword, int limit, boolean bySales, SettingsService.Ecom e) throws Exception {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("method", "taobao.tbk.dg.material.optional");
        p.put("q", keyword);
        p.put("adzone_id", e.tbAdzoneId.trim());
        p.put("page_no", "1");
        p.put("page_size", String.valueOf(Math.max(limit, 10)));
        // 采购场景：只看得见有券的，基本等于只看真实优惠价
        if (!Boolean.FALSE.equals(e.couponOnly)) p.put("has_coupon", "true");
        p.put("sort", bySales ? "tk_total_sales_des" : "price_asc");

        JsonNode root = tbCall(p, e);
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
            boolean tmall = n.path("user_type").asInt(0) == 1;
            if (tmall && !shop.contains("天猫")) shop = shop + "(天猫)";
            String itemId = n.path("item_id").asText("");
            String itemUrl = n.path("item_url").asText("");
            out.add(new Item("淘宝", title, finalPrice, zk, couponInfo.isBlank() && coupon != null
                    ? "券 ¥" + coupon.stripTrailingZeros().toPlainString(): couponInfo,
                    n.path("volume").asInt(0), shop,
                    pickUrl(buyUrl("淘宝", itemId, tmall), itemUrl),
                    itemId, dec(n.path("commission_rate").asText("")), detectRisk(title), tmall, itemUrl));
        }
        return out;
    }

    /**
     * 按淘宝商品 id 精准取详情。
     *
     * <p>物料搜索返回的价格是 SPU 级的"折扣价",同款不同容量/颜色会混成一个数,
     * 「256G 多少钱」这种问题它答不准。商品详情接口按 id 点名,型号是确定的。
     * 代价:它不带券、不带销量 —— 精准核价只认型号和标价,券要人去结算页看。
     */
    private List<Item> searchTbByItem(String itemIds, int limit, SettingsService.Ecom e) throws Exception {
        List<String> ids = new ArrayList<>();
        for (String s : itemIds.split("[,，\\s]+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6,})").matcher(t);
            if (m.find()) ids.add(m.group(1));
            else if (t.matches("\\d+")) ids.add(t);
            if (ids.size() >= Math.min(limit, 20)) break;
        }
        if (ids.isEmpty()) throw new IllegalStateException("itemIds 里没有合法的淘宝商品 id(纯数字)");

        TreeMap<String, String> p = new TreeMap<>();
        p.put("method", "taobao.tbk.item.info.get");
        p.put("num_iids", String.join(",", ids));
        p.put("platform", "2");

        JsonNode root = tbCall(p, e);
        JsonNode arr = root.path("tbk_item_info_get_response").path("results").path("n_tbk_item");
        List<Item> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            String title = n.path("title").asText("");
            if (title.isBlank()) continue;
            BigDecimal price = dec(n.path("zk_final_price").asText(""));
            String shop = n.path("shop_title").asText(n.path("nick").asText(""));
            String id = n.path("num_iid").asText("");
            String itemUrl = n.path("item_url").asText("");
            out.add(new Item("淘宝", title, price, price, "", 0, shop,
                    pickUrl(buyUrl("淘宝", id, false), itemUrl),
                    id, null, detectRisk(title), false, itemUrl));
        }
        return out;
    }

    /** 淘宝客调用:公共参数、签名、错误识别集中在这里。 */
    private JsonNode tbCall(TreeMap<String, String> p, SettingsService.Ecom e) throws Exception {
        p.put("app_key", e.tbAppKey.trim());
        p.put("timestamp", ZonedDateTime.now(CST).format(TS));
        p.put("format", "json");
        p.put("v", "2.0");
        p.put("sign_method", "md5");
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
        return root;
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
        int riskyCount = (int) items.stream().filter(Item::risky).count();
        sb.append("【比价结果】「").append(keyword).append("」 共 ").append(items.size()).append(" 条")
          .append(" · 平台:").append(hasJd ? "京东" : "").append(hasJd && hasTb ? "+" : "").append(hasTb ? "淘宝" : "")
          .append(" · 按").append(bySales ? "30天销量" : "到手价").append("排序")
          .append(riskyCount > 0 ? " · 其中 " + riskyCount + " 条带风险标记,已排到最后" : "")
          .append("\n");
        sb.append("(到手价 = 券后实付,已含平台折扣;不含运费,下单前请在结算页确认)\n");
        sb.append("(下面每条都给「下单」直链:点开就是这一款的商品页,京东 skuId 已锁定规格,登录后即可购买)\n\n");

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
            if (it.risky()) sb.append(" · ⚠ ").append(it.risk());
            sb.append("\n   ").append(it.title());
            String tag = shopTag(it.platform(), it.shop(), it.tmall());
            if (!it.shop().isBlank()) {
                sb.append("\n   店铺:").append(it.shop());
                if (!tag.isBlank()) sb.append(" [").append(tag).append("]");
            }
            // 标上「下单」二字不是排版问题:模型会照着这段的结构写进产出,
            // 标注清楚它才会在最终交付物里把链接写成可直接点的下单地址,而不是含糊地说"可去购买"。
            if (it.url() != null && !it.url().isBlank()) sb.append("\n   下单:").append(it.url());
            sb.append("\n");
        }

        // 采购单最后一定要落到「按了这一下之后出问题谁负责」——这是我们唯一不能替人决定的事。
        sb.append("\n提示:销量低但价格明显更低的,可能是小店或无票渠道,采购时要一并说明发票与售后风险。");
        sb.append("\n提醒:本工具只提供商品信息与链接,不代下单、不代付、不承担售后。");
        sb.append("退货与保修按平台规则和店铺承诺执行;手机/数码类普遍「激活或拆封后不支持 7 天无理由」,"
                + "批量采购前先跟商家确认未拆封能否退、能否开专票。");
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
