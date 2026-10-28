package com.blankgrid.aione.config;

import com.blankgrid.aione.controller.AuthController;
import com.blankgrid.aione.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 口令拦截:设置了口令后,/api/**(除登录)必须带正确的 Authorization token */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SettingsService settings;

    public AuthInterceptor(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String pwd = settings.get().password;
        if (pwd == null || pwd.isBlank()) return true; // 未启用口令
        if (request.getRequestURI().startsWith("/api/auth/")) return true;
        String token = request.getHeader("Authorization");
        if (token != null && token.equals(AuthController.tokenOf(pwd))) return true;
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"需要登录\"}");
        return false;
    }
}
