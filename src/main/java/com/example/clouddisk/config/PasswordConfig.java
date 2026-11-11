package com.example.clouddisk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 只用到 Spring Security 的密码哈希能力，不启用其过滤器链。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // strength 10 是 BCrypt 默认值，单次校验约 50~80ms，够用且不至于拖慢登录
        return new BCryptPasswordEncoder();
    }
}
