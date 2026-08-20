/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalEventProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务图事实审批事件配置。
 *
 * <p>图事实写入属于跨服务异步流程：审批事实先落 permission-admin 自己的数据库，
 * 再由 outbox 投递 Kafka。把开关和重试参数独立出来，可以在没有 Kafka 的开发环境
 * 只验证审批入库，也可以在生产环境显式打开事件投递。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.permission.graph-fact-events")
public class GraphFactApprovalEventProperties {

    /** 是否允许发布图事实审批事件。 */
    private boolean enabled = false;

    /** 图事实审批事件 Topic。 */
    private String topic = "datasmart.graph.facts.approved.v1";

    /** 是否启动该类 outbox 的后台投递器。 */
    private boolean dispatcherEnabled = false;

    /** 每轮最多抢占的事件数量。 */
    private int dispatchBatchSize = 50;

    /** 投递器轮询间隔。 */
    private long dispatchFixedDelayMs = 5000L;

    /** Kafka send future 的最大等待时间。 */
    private Duration sendTimeout = Duration.ofSeconds(3);

    /** 单条消息失败后的下一次重试间隔。 */
    private Duration retryDelay = Duration.ofSeconds(30);

    /** 超过该次数后由通用 outbox 进入 DEAD。 */
    private int maxAttempts = 10;

    /** SENDING 状态的恢复窗口。 */
    private Duration sendingTimeout = Duration.ofMinutes(5);
}
