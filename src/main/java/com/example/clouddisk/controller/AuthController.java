package com.example.clouddisk.controller;

import com.example.clouddisk.common.LoginUser;
import com.example.clouddisk.common.Result;
import com.example.clouddisk.dto.LoginForm;
import com.example.clouddisk.dto.PasswordForm;
import com.example.clouddisk.entity.SysUser;
import com.example.clouddisk.service.SysUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;

/**
 * 登录相关接口：登录 / 登出 / 当前用户 / 修改自己的密码。
 * 注意这里没有注册接口——账号只能由管理员在后台添加。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysUserService sysUserService;

    public AuthController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @PostMapping("/login")
    public Result<LoginUser> login(@RequestBody @Valid LoginForm form,
                                   HttpServletRequest request) {
        SysUser user;
        try {
            user = sysUserService.authenticate(form.getUsername(), form.getPassword());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }

        HttpSession session = request.getSession(true);
        // 防会话固定攻击：登录成功后换一个 session id
        try {
            request.changeSessionId();
        } catch (IllegalStateException ignored) {
            // 容器不支持时忽略
        }
        LoginUser loginUser = new LoginUser(
                user.getId(), user.getUsername(),
                (user.getDisplayName() == null || user.getDisplayName().trim().isEmpty())
                        ? user.getUsername() : user.getDisplayName(),
                user.getRole());
        session.setAttribute(LoginUser.SESSION_KEY, loginUser);
        // 有效期统一由 application.yml 的 spring.session.timeout 控制，不在这里硬编码
        return Result.success(loginUser);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success();
    }

    /** 前端启动时调用：未登录返回 data=null，而不是 401 */
    @GetMapping("/current")
    public Result<LoginUser> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object v = session == null ? null : session.getAttribute(LoginUser.SESSION_KEY);
        return Result.success(v instanceof LoginUser ? (LoginUser) v : null);
    }

    /** 修改自己的密码 */
    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody PasswordForm form, HttpServletRequest request) {
        Object v = request.getAttribute("loginUser");
        if (!(v instanceof LoginUser)) {
            return Result.error(401, "登录已失效，请重新登录");
        }
        try {
            sysUserService.changeOwnPassword((LoginUser) v, form.getOldPassword(), form.getNewPassword());
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
