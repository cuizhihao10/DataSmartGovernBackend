package com.czh.datasmart.govern.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GatewayApplication.java
 * @Version:1.0.0
 *
 * 网关模块启动类。
 * 网关在当前项目中的角色不是承载具体业务，而是作为统一入口层，
 * 负责把外部请求路由到对应业务模块，并补充横切上下文。
 *
 * 也正因为如此，网关天然更适合承载：
 * 1. 路由契约说明。
 * 2. 请求上下文透传。
 * 3. 认证、鉴权、限流等统一策略。
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * Spring Boot 标准入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
