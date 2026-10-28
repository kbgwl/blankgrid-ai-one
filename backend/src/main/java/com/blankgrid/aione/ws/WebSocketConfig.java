package com.blankgrid.aione.ws;

import com.blankgrid.aione.controller.AuthController;
import com.blankgrid.aione.service.SettingsService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SettingsService settings;

    public WebSocketConfig(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        // 原生 WebSocket 端点,前端不依赖 sockjs 时使用
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    /** 设置了口令后,STOMP CONNECT 必须携带正确的 Authorization 头 */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc != null && StompCommand.CONNECT.equals(acc.getCommand())) {
                    String pwd = settings.get().password;
                    if (pwd != null && !pwd.isBlank()) {
                        String token = firstNative(acc, "Authorization");
                        if (!AuthController.tokenOf(pwd).equals(token)) {
                            throw new IllegalArgumentException("需要登录");
                        }
                    }
                }
                return message;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static String firstNative(StompHeaderAccessor acc, String name) {
        Object nativeHeaders = acc.getHeader("nativeHeaders");
        if (nativeHeaders instanceof Map<?, ?> map) {
            Object list = ((Map<String, Object>) map).get(name);
            if (list instanceof List<?> l && !l.isEmpty()) return String.valueOf(l.get(0));
        }
        return acc.getFirstNativeHeader(name);
    }
}
