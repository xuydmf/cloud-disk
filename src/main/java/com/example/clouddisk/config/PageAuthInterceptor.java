package com.example.clouddisk.config;

import com.example.clouddisk.common.LoginUser;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 页面鉴权：未登录访问工作台时直接跳登录页；普通用户访问 /admin.html 时退回工作台。
 */
public class PageAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        Object v = session == null ? null : session.getAttribute(LoginUser.SESSION_KEY);
        String ctx = request.getContextPath();

        if (!(v instanceof LoginUser)) {
            response.sendRedirect(ctx + "/login.html");
            return false;
        }

        LoginUser user = (LoginUser) v;
        if (request.getRequestURI().endsWith("/admin.html") && !user.isAdmin()) {
            response.sendRedirect(ctx + "/index.html");
            return false;
        }
        return true;
    }
}
