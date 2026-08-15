/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - DataSyncAgentRuntimeObservationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 查询 Agent Runtime 低敏审计快照的配置。
 *
 * <p>统一生命周期图是运维查询，不是新的执行通道。这个配置只允许 data-sync 使用固定内部地址和
 * 服务间令牌读取已经存在的 Agent 工具审计，绝不允许调用方通过请求参数改写目标地址或 Header。
 * 关闭该能力时，图仍然返回本地执行事实，但会把 Agent 审计标记为“未查询”，避免把网络故障伪装成工具成功。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.agent-runtime-observation")
public class DataSyncAgentRuntimeObservationProperties {

    /** 是否启用对 Agent Runtime 的只读审计查询。 */
    private boolean enabled = true;

    /** Agent Runtime 内部服务地址，生产环境应由服务发现或部署配置注入。 */
    private String baseUrl = "http://localhost:8091";

    /** 固定的 Agent 工具审计查询路径模板。 */
    private String auditPathTemplate = "/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions";

    /** 精确的 Agent 异步命令 outbox 观察路径；不再分页拉取同一 Run 的其他命令。 */
    private String commandOutboxPathTemplate =
            "/agent-runtime/sessions/{sessionId}/runs/{runId}/async-task-commands/{commandId}/observation";

    /** Agent Runtime 用来区分受信读取方的服务名。 */
    private String sourceService = "data-sync";

    /** 与 Agent Runtime 共享的内部服务令牌；不会写入日志、数据库或响应。 */
    private String internalServiceToken;

    /** 建立连接的超时时间，避免查询图时长时间占用请求线程。 */
    private long connectTimeoutMs = 800L;

    /** 读取审计快照的超时时间。 */
    private long readTimeoutMs = 1500L;
}
