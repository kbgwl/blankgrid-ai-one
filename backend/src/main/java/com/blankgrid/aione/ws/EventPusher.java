package com.blankgrid.aione.ws;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向前端 /topic/events 推送实时事件:
 *  - employee-status:岗位状态变化(驱动格子呼吸/告警动画)
 *  - task-update:任务状态变化
 *  - task-delta:任务流式输出增量
 */
@Component
public class EventPusher {

    private final SimpMessagingTemplate template;

    public EventPusher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void push(String type, Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        out.put("type", type);
        out.put("ts", System.currentTimeMillis());
        template.convertAndSend("/topic/events", out);
    }
}
