package com.example.clouddisk.common;

import com.example.clouddisk.entity.SysUser;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户列表 / 详情返回对象（绝不包含密码字段）
 */
@Data
public class UserVO implements Serializable {

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private Boolean enabled;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;

    /** 是否由 application.yml 托管（该账号密码以配置文件为准，后台不可改） */
    private Boolean managed;

    public static UserVO of(SysUser u, String managedUsername) {
        UserVO v = new UserVO();
        v.id = u.getId();
        v.username = u.getUsername();
        v.displayName = (u.getDisplayName() == null || u.getDisplayName().trim().isEmpty())
                ? u.getUsername() : u.getDisplayName();
        v.role = u.getRole();
        v.enabled = u.getEnabled() == null || u.getEnabled() == 1;
        v.lastLoginTime = u.getLastLoginTime();
        v.createTime = u.getCreateTime();
        v.managed = managedUsername != null && managedUsername.equalsIgnoreCase(u.getUsername());
        return v;
    }
}
