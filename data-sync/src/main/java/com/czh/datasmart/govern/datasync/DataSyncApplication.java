/**
 * @Author : Cui
 * @Date: 2026/05/07 21:25
 * @Description DataSmart Govern Backend - DataSyncApplication.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据同步模块启动类。
 *
 * <p>这个模块是从 datasource-management 中拆出的独立产品能力边界。
 * datasource-management 负责“数据源是什么、能不能连、有哪些表和字段”，data-sync 负责“把哪些数据按什么模式从源端移动到目标端”。
 *
 * <p>为什么要独立成微服务：
 * 1. 数据同步会逐步引入任务状态机、checkpoint、重试、限流、并发配额、失败样本和审计，复杂度远高于数据源 CRUD；
 * 2. 同步执行会访问源端和目标端，性能风险、连接风险和合规风险都更高，需要独立扩容和独立观测；
 * 3. 后续 data-quality、data-asset、agent-runtime 都可能发起同步或消费同步结果，独立服务更适合作为平台能力复用。
 */
@SpringBootApplication
@EnableScheduling
public class DataSyncApplication {

    /**
     * Spring Boot 标准入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(DataSyncApplication.class, args);
    }
}
