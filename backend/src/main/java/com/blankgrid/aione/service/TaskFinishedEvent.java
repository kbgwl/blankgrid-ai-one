package com.blankgrid.aione.service;

/** 任务结束事件:目标引擎据此推进成果物、评审与返工。 */
public record TaskFinishedEvent(Long taskId, boolean success, String error) {}
