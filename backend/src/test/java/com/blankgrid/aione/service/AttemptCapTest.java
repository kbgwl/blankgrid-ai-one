package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 返工上限的算法。
 *
 * <p>这个数一旦算错,后果是两个方向的,而且都不会报错:
 * 算宽了 —— 一份明明过不了的成果无限返工,每轮都是一次完整的模型调用;
 * 算窄了 —— 明明还有机会,成果被直接打成 BLOCKED,整条下游再也推不动。
 *
 * <p>所以它不值得"两处各写一遍"。CEO 上下文里显示的「已用 x/y 轮」
 * 和真正判定用的必须是同一个公式,否则人看到的进度和执行时用的门槛对不上。
 */
class AttemptCapTest {

    @Test
    @DisplayName("默认 2 轮返工 → 总共 3 次机会(首轮 + 2 次返工)")
    void baseline() {
        assertEquals(3, CompanyEngineService.attemptCap(2, 0));
    }

    @Test
    @DisplayName("追加 1 轮 → 上限抬到 4,不是把已用轮次清零")
    void bonusRaisesCap() {
        assertEquals(4, CompanyEngineService.attemptCap(2, 1));
        assertEquals(5, CompanyEngineService.attemptCap(2, 2));
    }

    @Test
    @DisplayName("即使轮次配成 0,也至少给一次执行机会")
    void zeroConfigStillGivesOneShot() {
        assertEquals(2, CompanyEngineService.attemptCap(0, 0),
                "配成 0 应该理解成「不返工,但总要让它写一次」,而不是一次都不跑");
        assertEquals(2, CompanyEngineService.attemptCap(-5, 0));
    }

    @Test
    @DisplayName("负数追加不能反过来扣掉上限")
    void negativeBonusCannotShrink() {
        assertEquals(3, CompanyEngineService.attemptCap(2, -1),
                "脏数据(比如手改库)不该让一份成果凭空少一次机会");
    }

    @Test
    @DisplayName("追加是加在全局配置之上,改全局设置时已追加的仍然生效")
    void bonusStacksOnTopOfSettings() {
        assertTrue(CompanyEngineService.attemptCap(1, 2) > CompanyEngineService.attemptCap(1, 0));
        assertEquals(6, CompanyEngineService.attemptCap(3, 2));
    }
}
