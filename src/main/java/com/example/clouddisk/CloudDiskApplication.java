package com.example.clouddisk;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 类阿里云云盘 - 文件上传与管理平台 启动类
 */
@SpringBootApplication
@MapperScan("com.example.clouddisk.mapper")
@EnableScheduling   // 回收站过期清理等定时任务
public class CloudDiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudDiskApplication.class, args);
    }
}
