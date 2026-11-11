package com.example.clouddisk.config;

import com.example.clouddisk.service.SysUserService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * 注册鉴权拦截器。
 * 静态资源（/css/**、/js/**、/login.html）不拦截，否则登录页自身会被挡住。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SysUserService sysUserService;

    public WebMvcConfig(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(sysUserService))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/logout",
                        "/api/auth/current",
                        // 诊断埋点：登录前也可能要测（选择器的耗时跟登录态无关），
                        // 且它只写日志、不返回任何业务数据。
                        "/api/debug/**"
                );

        registry.addInterceptor(new PageAuthInterceptor())
                .addPathPatterns("/", "/index.html", "/admin.html", "/editor.html");
    }

    /**
     * 缓存策略。
     *
     * 起因：改完前端代码，iPhone 上打开还是老行为——因为之前这些响应完全不带 Cache-Control，
     * Safari 会按 Last-Modified 做启发式缓存，手机端尤其不肯重新拉。
     *
     * 现在：HTML 一律 no-store（保证每次都拿到最新的 js/css 引用）；
     * js / css 允许缓存但必须回源验证（max-age=0 + must-revalidate），
     * 未变更时走 304，几十字节，几乎无感。内网环境不必为这点流量换旧代码。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());

        registry.addResourceHandler("/js/**", "/css/**")
                .addResourceLocations("classpath:/static/js/", "classpath:/static/css/")
                .setCacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).mustRevalidate().cachePublic());
    }
}
