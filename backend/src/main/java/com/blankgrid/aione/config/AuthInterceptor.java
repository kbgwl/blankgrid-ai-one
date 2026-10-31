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
        // 会议转写推送:外部 ASR 服务不该持有控制台登录口令。
        // 这一条路径由它自己的每场会 token 把关(见 MeetingController#ingest),
        // 而不是"免鉴权"—— 没有正确 token 的请求在控制器里就被拒了。
        if (request.getRequestURI().startsWith("/api/meetings/ingest/")) return true;
        String token = request.getHeader("Authorization");
        if (token != null && token.equals(AuthController.tokenOf(pwd))) return true;
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"需要登录\"}");
        return false;
    }
}
