package com.example.clouddisk.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 新建 / 编辑用户请求体。
 * password 仅在新建与重置密码时使用，编辑其它字段时可留空。
 */
@Data
public class UserForm implements Serializable {

    private String username;

    private String password;

    private String displayName;

    /** ADMIN / USER，默认 USER */
    private String role;

    private Boolean enabled;
}
