package com.example.clouddisk.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 密码重置 / 修改自己的密码
 */
@Data
public class PasswordForm implements Serializable {

    /** 仅「修改自己的密码」时需要 */
    private String oldPassword;

    private String newPassword;
}
