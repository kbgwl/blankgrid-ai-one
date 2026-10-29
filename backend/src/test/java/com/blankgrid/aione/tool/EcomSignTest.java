package com.blankgrid.aione.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 京东/淘宝共用一套签名规则。这里的期望值是按官方文档的规则独立算出来的:
 * 参数名排序 → 拼 key1value1 → 前后各接一次 secret → MD5 → 大写。
 *
 * <p>签名错了的表现是"接口能通但报 403/签名无效",而且看不出是哪一步错的,
 * 所以必须钉死一个已知正确的值,不能只断言"不为空"。
 */
class EcomSignTest {

    @Test
    @DisplayName("京东签名与官方规则一致(含 360buy_param_json 排在字母键之前)")
    void jdSignMatchesReference() throws Exception {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("app_key", "mykey");
        p.put("format", "json");
        p.put("method", "jd.union.open.goods.jingfen.query");
        p.put("sign_method", "md5");
        p.put("timestamp", "2026-01-01 00:00:00");
        p.put("v", "1.0");
        p.put("360buy_param_json", "{\"goodsReq\":{\"keyword\":\"A4纸\",\"pageIndex\":1}}");

        assertEquals("91762ABC6384534845EC8EB580300AAD", EcomSearchTool.md5Sign(p, "testsecret"));
    }

    @Test
    @DisplayName("空值参数不参与签名:多塞一个空串不能改变结果")
    void emptyValuesAreIgnored() throws Exception {
        TreeMap<String, String> base = new TreeMap<>();
        base.put("app_key", "k");
        base.put("v", "1.0");
        String a = EcomSearchTool.md5Sign(base, "sec");

        TreeMap<String, String> withEmpty = new TreeMap<>(base);
        withEmpty.put("access_token", "");
        assertEquals(a, EcomSearchTool.md5Sign(withEmpty, "sec"));
    }

    @Test
    @DisplayName("sign 自身不参与签名(否则回传已签好的 map 会算出另一个值)")
    void signKeyExcluded() throws Exception {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("app_key", "k");
        String plain = EcomSearchTool.md5Sign(p, "sec");
        p.put("sign", plain);
        assertEquals(plain, EcomSearchTool.md5Sign(p, "sec"));
    }

    @Test
    @DisplayName("中文参数按原样参与签名,不做 URL 编码")
    void chineseNotUrlEncoded() throws Exception {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("q", "打印纸");
        // 若实现里先 urlencode 再签,这里会得到完全不同的值
        String sign = EcomSearchTool.md5Sign(p, "sec");
        assertEquals(32, sign.length());
        // 手工验证:secret + q打印纸 + secret
        java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
        byte[] d = md5.digest(("secq打印纸sec").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02X", b));
        assertEquals(hex.toString(), sign);
    }
}
