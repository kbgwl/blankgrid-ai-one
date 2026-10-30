package com.blankgrid.aione.service;

/**
 * 有一项交付被了结(当前只有"砍掉")。
 *
 * <p>为什么用事件而不是在 GoalService 里直接判结项:结项要重算均分、落盘导出、
 * 推 SSE、写动态 —— 那是引擎的活。让 GoalService 反过来依赖引擎会绕成环,
 * 也会把"砍掉"这件事和"目标完成"这套规则焊死在一起。
 */
public record DeliverableSettledEvent(Long goalId) {}
