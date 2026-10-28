package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 待补清单的主题收敛。素材来自真跑目标 #5 时的现场:
 * CEO、产品部、设计部各问了一遍"你的产品/服务叫什么",创始人看到 11 条待补里有 4 条是同一件事。
 */
class NeedsInputTopicTest {

    @Test
    @DisplayName("同一主题的多条只留信息最全的那条")
    void productFactsMerge() {
        List<String> items = List.of(
                "空白格真实业务与产品/服务清单(名称、数量、一句话价值)—— 缺它文案只能写虚话",
                "创始人提供的真实产品/服务名称、功能清单与交付形态 —— 文案正文必须用真实名称,不能由我代拟");
        assertEquals("产品事实", CompanyEngineService.factTopic(items.get(0)));
        List<String> out = CompanyEngineService.compactTopics(items);
        assertEquals(1, out.size());
        // 保留最长的那条(信息最全),而不是简单取第一条
        assertTrue(out.get(0).contains("交付形态"));
    }

    @Test
    @DisplayName("一条里混了几类事实时,归到先命中的那类,内容不被截断")
    void compositeGoesToFirstTopic() {
        String composite = "空白格对外业务事实:产品/服务正式名称、可公开的客户与案例、联系方式与办公地址"
                + " —— 高保真稿正文与页脚需真实内容,编造会导致返工";
        // 「客户」在「产品」之前命中,归客户案例;文案本身一个字不丢
        assertEquals("客户案例", CompanyEngineService.factTopic(composite));
        List<String> out = CompanyEngineService.compactTopics(List.of(
                "可公开的真实客户、案例或业绩数据 —— 缺它案例区只能空着", composite));
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("联系方式与办公地址"));
    }

    @Test
    @DisplayName("表单接收邮箱属于表单服务,不能被当成联系方式合并掉")
    void formEmailIsNotContact() {
        assertEquals("表单服务", CompanyEngineService.factTopic("表单接收邮箱或第三方表单服务(Formspree/EmailJS 等)的账号与额度"));
        assertEquals("联系方式", CompanyEngineService.factTopic("对外联系方式与商务口径:电话、微信、办公地址"));

        List<String> out = CompanyEngineService.compactTopics(List.of(
                "对外联系方式与商务口径:电话、微信、办公地址",
                "表单接收邮箱或第三方表单服务(Formspree/EmailJS 等)的账号与额度"));
        assertEquals(2, out.size(), "两件事必须分开问,合并会漏料");
    }

    @Test
    @DisplayName("域名与托管、ICP 备案是两件事,都要保留")
    void domainAndIcpKept() {
        List<String> out = CompanyEngineService.compactTopics(List.of(
                "域名与静态托管账号(Vercel/Netlify)的归属与权限",
                "如使用国内服务器需提供 ICP 备案主体与备案号"));
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("认不出主题的照原样保留,不做任何猜测性合并")
    void unknownKept() {
        List<String> out = CompanyEngineService.compactTopics(List.of(
                "上一版官网上线后用户投诉集中在哪三个点",
                "上一版官网上线后用户投诉集中在哪三个点"));
        assertEquals(2, out.size(), "主题未命中时交给上层的近似查重,不在这里吞");
        assertNull(CompanyEngineService.factTopic("上一版官网上线后用户投诉集中在哪三个点"));
    }

    @Test
    @DisplayName("收敛保持原有顺序,不会把清单打乱")
    void keepsOrder() {
        List<String> out = CompanyEngineService.compactTopics(List.of(
                "域名与静态托管账号",
                "产品/服务正式名称",
                "域名归属与权限说明"));
        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("域名"));
        assertTrue(out.get(1).startsWith("产品"));
    }
}
