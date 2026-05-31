package com.czh.datasmart.govern.task;

import com.czh.datasmart.govern.task.config.AgentAsyncTaskCommandKafkaProperties;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:10
 * @Description DataSmart Govern Backend - TaskManagementApplication.java
 * @Version:1.0.0
 *
 * 任务管理模块启动类。
 * 这个模块当前承担的是平台“任务中心”的职责：
 * 1. 接收上游提交的任务定义。
 * 2. 维护任务生命周期状态。
 * 3. 记录执行日志，支撑后续追踪、审计和问题复盘。
 *
 * 之所以把启动类也写清楚说明，是因为在微服务项目里，
 * 每个模块都不是普通的 package 聚合，而是一个可独立启动、独立部署、独立演进的业务服务。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AgentAsyncTaskCommandKafkaProperties.class,
        AgentAsyncToolWorkerProperties.class
})
public class TaskManagementApplication {

    /**
     * Spring Boot 标准入口。
     * 这里没有附加复杂逻辑，目的是让应用启动流程保持清晰可预测，
     * 后续需要接入启动监听器、预热逻辑或模块级初始化时，再在更合适的配置类中扩展。
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskManagementApplication.class, args);
    }
}
