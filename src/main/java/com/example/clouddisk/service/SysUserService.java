package com.example.clouddisk.service;

import com.example.clouddisk.common.LoginUser;
import com.example.clouddisk.common.UserVO;
import com.example.clouddisk.dto.UserForm;
import com.example.clouddisk.entity.SysUser;

import java.util.List;

/**
 * 用户与登录相关业务。
 * <p>
 * 系统不提供注册入口：除 application.yml 托管的管理员之外，
 * 所有账号都只能由管理员在后台页面创建。
 */
public interface SysUserService {

    /** application.yml 中托管的管理员用户名（该账号密码以配置文件为准） */
    String managedUsername();

    /** 启动时按配置初始化/同步管理员账号 */
    void ensureAdmin(String username, String rawPassword, String displayName, String role, boolean syncPassword);

    /** 校验用户名密码；失败抛 IllegalArgumentException */
    SysUser authenticate(String username, String rawPassword);

    /** 用 session 里的 id 重新加载用户，已被删除或禁用时返回 null */
    LoginUser loadForSession(Long id);

    List<UserVO> listUsers(String keyword);

    UserVO createUser(UserForm form);

    UserVO updateUser(Long id, UserForm form, LoginUser operator);

    void resetPassword(Long id, String newPassword);

    void changeOwnPassword(LoginUser me, String oldPassword, String newPassword);

    void deleteUser(Long id, LoginUser operator);
}
