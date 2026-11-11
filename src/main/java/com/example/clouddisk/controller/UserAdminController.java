package com.example.clouddisk.controller;

import com.example.clouddisk.common.LoginUser;
import com.example.clouddisk.common.Result;
import com.example.clouddisk.common.UserVO;
import com.example.clouddisk.dto.UserForm;
import com.example.clouddisk.service.SysUserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 后台用户管理（仅 ADMIN 可访问，由 AuthInterceptor 统一拦截 /api/admin/**）。
 * 这是系统里唯一的"添加登录用户"入口。
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final SysUserService sysUserService;

    public UserAdminController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @GetMapping
    public Result<List<UserVO>> list(@RequestParam(required = false) String keyword) {
        return Result.success(sysUserService.listUsers(keyword));
    }

    @PostMapping
    public Result<UserVO> create(@RequestBody UserForm form) {
        try {
            return Result.success(sysUserService.createUser(form));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<UserVO> update(@PathVariable Long id, @RequestBody UserForm form,
                                 HttpServletRequest request) {
        try {
            return Result.success(sysUserService.updateUser(id, form, operator(request)));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 管理员重置某个用户的密码 */
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody UserForm form) {
        try {
            sysUserService.resetPassword(id, form.getPassword());
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        try {
            sysUserService.deleteUser(id, operator(request));
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private LoginUser operator(HttpServletRequest request) {
        Object v = request.getAttribute("loginUser");
        return v instanceof LoginUser ? (LoginUser) v : null;
    }
}
