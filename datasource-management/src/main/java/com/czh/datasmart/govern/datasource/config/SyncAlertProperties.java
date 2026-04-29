package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncAlertProperties.java
 * @Version:1.0.0
 *
 * 同步治理告警配置。
 * 这一组配置的职责不是定义“何时触发告警”，而是定义“告警生成后如何去重、是否自动投递、往哪里投递”。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.sync-alert")
public class SyncAlertProperties {

    /**
     * 是否在告警创建或刷新后自动尝试投递。
     */
    private Boolean autoDeliverOnOpen = true;

    /**
     * 告警去重时间窗，单位秒。
     * 同一告警键在该时间窗内重复触发时，优先刷新已有告警而不是无限新增。
     */
    private Integer dedupWindowSeconds = 600;

    /**
     * 默认投递通道。
     */
    private String defaultChannel = "WEBHOOK";

    /**
     * 通道链。
     * 允许配置成类似 `WEBHOOK,INTERNAL_LOG` 的形式，表示先走 webhook，失败后再走内部日志通道兜底。
     */
    private String channelChain = "NONE";

    /**
     * 是否启用 webhook 投递。
     */
    private Boolean webhookEnabled = false;

    /**
     * webhook 地址。
     */
    private String webhookUrl;

    /**
     * 飞书机器人 webhook 地址。
     * 当前先复用通用 webhook 发送模型，把“通道差异”抽象在配置层。
     */
    private String feishuWebhookUrl;

    /**
     * 企业微信机器人 webhook 地址。
     */
    private String wecomWebhookUrl;

    /**
     * 建连超时时间，单位秒。
     */
    private Integer connectTimeoutSeconds = 3;

    /**
     * 请求超时时间，单位秒。
     */
    private Integer readTimeoutSeconds = 5;

    /**
     * 最大自动重试次数。
     */
    private Integer maxDeliveryRetryCount = 3;

    /**
     * 失败后的基础重试退避秒数。
     */
    private Integer retryBackoffSeconds = 300;

    /**
     * 单次批量补投最多处理多少条告警。
     * 这里显式设置上限，是为了防止运营侧一次补投把整个模块线程池或外部告警平台打满。
     */
    private Integer retryDispatchBatchLimit = 100;
}
