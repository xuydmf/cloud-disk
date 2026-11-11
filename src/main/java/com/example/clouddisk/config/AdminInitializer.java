package com.example.clouddisk.config;

import com.example.clouddisk.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 按 application.yml 的 app.admin.* 初始化管理员账号（需在 SchemaInitializer 建表之后执行）。
 */
@Slf4j
@Component
@Order(2)
public class AdminInitializer implements CommandLineRunner {

    private final SysUserService sysUserService;

    @Value("${app.admin.username:}")
    private String username;

    @Value("${app.admin.password:}")
    private String password;

    @Value("${app.admin.display-name:系统管理员}")
    private String displayName;

    @Value("${app.admin.role:ADMIN}")
    private String role;

    @Value("${app.admin.sync-password:true}")
    private boolean syncPassword;

    public AdminInitializer(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @Override
    public void run(String... args) {
        try {
            sysUserService.ensureAdmin(username, password, displayName, role, syncPassword);
        } catch (Exception e) {
            log.error("管理员账号初始化失败：{}", e.getMessage(), e);
        }
    }
}
