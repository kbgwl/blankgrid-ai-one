package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 区分"活干砸了"和"服务商那边出问题"。
 * 前者要返工,后者不该扣重试次数 —— 一次余额不足就能把整条线永久钉死在受阻。
 */
class ProviderErrorTest {

    @Test
    @DisplayName("余额不足、限流、超时、空响应都算服务商侧问题")
    void providerSide() {
        assertTrue(CompanyEngineService.providerError("API 返回 402: {\"error\":{\"message\":\"Insufficient Balance\"}}"));
        assertTrue(CompanyEngineService.providerError("API 错误: Insufficient Balance"));
        assertTrue(CompanyEngineService.providerError("模型未返回内容"));
        assertTrue(CompanyEngineService.providerError("连接大模型 API 失败: Connection reset"));
        assertTrue(CompanyEngineService.providerError("API 返回 429: rate limit"));
        assertTrue(CompanyEngineService.providerError("请求被中断"));
    }

    @Test
    @DisplayName("内容不合格、缺料、违规不算服务商侧问题,该照常返工")
    void contentSide() {
        assertFalse(CompanyEngineService.providerError("评审未通过,正文缺少价格依据"));
        assertFalse(CompanyEngineService.providerError("缺少产品事实,无法定稿"));
        assertFalse(CompanyEngineService.providerError(null));
        assertFalse(CompanyEngineService.providerError(""));
    }
}
