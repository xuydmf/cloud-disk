package com.example.clouddisk.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端埋点收口。目前只有一个用途：把手机上「选完照片到页面恢复」的耗时打回服务端日志，
 * 免得靠人肉抄数字。
 *
 * 只在局域网自用的场景下存在，不接受任何写操作，只写日志。
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    @PostMapping(value = "/picker", consumes = MediaType.TEXT_PLAIN_VALUE)
    public String picker(@RequestBody String body) {
        // 用 ASCII 标签，方便 grep
        log.info("[picker] {}", body == null ? "" : body.replace('\n', '|'));
        return "ok";
    }
}
