package com.blankgrid.aione;

import com.blankgrid.aione.config.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期前后端分离放开本地跨域;
 * 生产模式下后端直接托管 frontend/dist 构建产物(从项目根目录启动)。
 * 设置了访问口令后,/api/** 全部需要登录(静态页面本身不需要)。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("file:frontend/dist/", "classpath:/static/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 前端为 hash 路由,根路径回落到 index.html
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
