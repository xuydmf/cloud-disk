package com.example.clouddisk.config;

import com.example.clouddisk.common.LoginUser;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * Bearer token 鉴权：token 就是 Spring Session 的 session id。
 *
 * 原生 App 的页面从 capacitor://localhost 加载，访问 http://<服务器> 属于跨域，
 * 浏览器不会带上会话 cookie。所以登录响应除了 Set-Cookie，再给一个 X-Auth-Token：
 *   · fetch / XHR 放 `Authorization: Bearer <token>`
 *   · <img> / <video> 放 `?token=<token>`
 *
 * 复用 Spring Session 的好处：30 天滑动过期、服务端重启不掉线、禁用账号后 token 一起失效。
 */
@Component
public class TokenAuthService {

    private final JdbcIndexedSessionRepository sessions;

    public TokenAuthService(JdbcIndexedSessionRepository sessions) {
        this.sessions = sessions;
    }

    /** 从请求里取 token：优先 Authorization，其次 GET 的 ?token= */
    public String token(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = auth.substring(7).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            String q = request.getParameter("token");
            if (q != null && !q.trim().isEmpty()) {
                return q.trim();
            }
        }
        return null;
    }

    public LoginUser user(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session session = sessions.findById(token);
        if (session == null) {
            return null;
        }
        Object v = session.getAttribute(LoginUser.SESSION_KEY);
        return v instanceof LoginUser ? (LoginUser) v : null;
    }

    public LoginUser user(HttpServletRequest request) {
        return user(token(request));
    }

    public void delete(String token) {
        if (token != null && !token.isEmpty()) {
            sessions.deleteById(token);
        }
    }
}
