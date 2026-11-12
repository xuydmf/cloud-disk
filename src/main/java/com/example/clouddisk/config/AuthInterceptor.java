package com.example.clouddisk.config;

import com.example.clouddisk.common.LoginUser;
import com.example.clouddisk.service.SysUserService;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口鉴权：/api/** 必须登录；/api/admin/** 必须是 ADMIN。
 *
 * 两种凭据：
 *   · 浏览器：会话 cookie（Spring Session JDBC）
 *   · 原生 App：`Authorization: Bearer <sessionId>`，图片/视频这类没法带 header 的请求用 `?token=`
 *     （App 的页面从 capacitor://localhost 加载，跨域请求不会带 cookie，只能靠 token）
 */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String REQ_LOGIN_USER = "loginUser";

    /** 账号状态回查的节流间隔（毫秒） */
    private static final long RECHECK_MS = 30_000L;

    /**
     * 「这个凭据上次回查账号状态的时间」——放**进程内存**，不放 session。
     *
     * 放 session 里会出事：Spring Session JDBC 保存时是先删属性再插入，
     * 翻相册会并发发好几个请求（一次预取 5 张图），几条线程同时 setAttribute
     * 就会撞 `Duplicate entry ... for key 'PRIMARY'`，那几个请求直接 500。
     * 存内存里既没有这个竞态，也省掉每 30 秒一次多余的会话写回。
     */
    private final ConcurrentHashMap<String, Long> lastRecheck = new ConcurrentHashMap<>();

    private final SysUserService sysUserService;
    private final TokenAuthService tokenAuth;

    public AuthInterceptor(SysUserService sysUserService, TokenAuthService tokenAuth) {
        this.sysUserService = sysUserService;
        this.tokenAuth = tokenAuth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检不带凭据，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = tokenAuth.token(request);
        HttpSession session = null;
        LoginUser cached = null;
        String sid;

        if (token != null) {
            cached = tokenAuth.user(token);
            sid = token;
        } else {
            session = request.getSession(false);
            Object v = session == null ? null : session.getAttribute(LoginUser.SESSION_KEY);
            if (v instanceof LoginUser) {
                cached = (LoginUser) v;
            }
            sid = session == null ? null : session.getId();
        }

        if (!(cached instanceof LoginUser)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "登录已失效，请重新登录");
            return false;
        }

        LoginUser fresh = cached;
        long now = System.currentTimeMillis();
        Long lastAt = lastRecheck.get(sid);
        if (lastAt == null || now - lastAt > RECHECK_MS) {
            fresh = sysUserService.loadForSession(cached.getId());
            if (fresh == null) {
                lastRecheck.remove(sid);
                if (token != null) {
                    tokenAuth.delete(token);
                }
                if (session != null) {
                    session.invalidate();
                }
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "账号已失效，请重新登录");
                return false;
            }
            lastRecheck.put(sid, now);
            if (lastRecheck.size() > 5000) {
                // 会话很多时清掉过期条目，避免这张表无限长
                lastRecheck.entrySet().removeIf(e -> now - e.getValue() > RECHECK_MS * 10);
            }
            if (session != null && (!fresh.getUsername().equals(cached.getUsername())
                    || !fresh.getRole().equals(cached.getRole())
                    || !fresh.getDisplayName().equals(cached.getDisplayName()))) {
                session.setAttribute(LoginUser.SESSION_KEY, fresh);
            }
        }

        if (request.getRequestURI().startsWith("/api/admin/") && !fresh.isAdmin()) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, 403, "需要管理员权限");
            return false;
        }

        request.setAttribute(REQ_LOGIN_USER, fresh);
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, int code, String message)
            throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
        response.getWriter().flush();
    }
}
