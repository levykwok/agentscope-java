/*
 * Copyright 2026 by the company contributors.
 */
package com.company.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 主业务模块入口。
 * 统一放在这里，核心代码与 Agentscope 框架/示例解耦。
 */
@SpringBootApplication
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
