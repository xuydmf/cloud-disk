package com.example.clouddisk.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户，对应表 sys_user。
 * <p>
 * 这里刻意不使用逻辑删除字段 deleted：用户量小，物理删除更符合直觉，
 * 也能让 username 上的唯一索引在删除后可以复用。
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，全局唯一 */
    private String username;

    /** BCrypt 密文，长度固定 60 */
    private String password;

    /** 显示名，为空时前端回落为 username */
    private String displayName;

    /** 角色：ADMIN / USER */
    private String role;

    /** 1-启用 0-禁用（禁用后不可登录，已登录会话在下次请求时失效） */
    private Integer enabled;

    private LocalDateTime lastLoginTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
