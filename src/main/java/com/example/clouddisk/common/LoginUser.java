package com.example.clouddisk.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录态对象，直接放进 HttpSession。
 * 不含密码等敏感字段，可安全回传给前端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    /** 会话要序列化进 MySQL（Spring Session JDBC），显式声明避免后续加字段后反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** session 中存放登录用户的 key */
    public static final String SESSION_KEY = "CD_LOGIN_USER";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private Long id;
    private String username;
    private String displayName;
    private String role;

    public boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }
}
