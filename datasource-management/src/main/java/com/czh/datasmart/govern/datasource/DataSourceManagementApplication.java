package com.czh.datasmart.govern.datasource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceManagementApplication.java
 * @Version:1.0.0
 *
 * 数据源管理模块启动类。
 * 当前模块负责解决的是“平台如何登记、维护和验证外部数据源连接信息”，
 * 而不是直接执行数据采集、质量检测或资产加工。
 *
 * 这样拆分模块的好处在于：
 * 1. 把连接管理和执行逻辑解耦。
 * 2. 便于其他模块统一复用已登记的数据源配置。
 * 3. 更适合作为企业级治理平台的基础设施能力沉淀。
 */
@SpringBootApplication
@EnableScheduling
public class DataSourceManagementApplication {

    /**
     * Spring Boot 标准入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(DataSourceManagementApplication.class, args);
    }
}
