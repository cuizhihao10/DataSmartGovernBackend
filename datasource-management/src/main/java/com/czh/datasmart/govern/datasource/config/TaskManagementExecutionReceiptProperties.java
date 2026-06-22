/**
 * @Author : Cui
 * @Date: 2026/06/22 10:41
 * @Description DataSmart Govern Backend - TaskManagementExecutionReceiptProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * datasource-management 向 task-management 回写执行回执的配置。
 *
 * <p>这组配置服务于 DataSync 端到端闭环：datasource Runner 完成一批执行后，可以把低敏执行事实回传给
 * task-management，由任务中心统一展示“命令已投递之后，下游到底执行到了哪里”。</p>
 *
 * <p>为什么默认关闭：</p>
 * <p>1. 本地开发时经常只启动 datasource-management，不能要求 task-management 必须同时在线；</p>
 * <p>2. 执行回执属于控制面可观测性增强，不应默认反向影响真实数据同步主流程；</p>
 * <p>3. 商业化部署时可以通过环境变量打开，并逐步叠加服务账号签名、mTLS、服务网格和告警。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.task-management-execution-receipt")
public class TaskManagementExecutionReceiptProperties {

    /**
     * 是否启用 Runner -> task-management 执行回执回写。
     */
    private Boolean enabled = false;

    /**
     * task-management 内部访问地址。
     *
     * <p>当前本地环境默认是 localhost:8081；生产环境建议走内部 gateway、服务发现域名或服务网格地址。</p>
     */
    private String taskManagementBaseUrl = "http://localhost:8081";

    /**
     * task-management 接收执行回执的内部路径。
     */
    private String recordEndpointPath = "/internal/data-sync-worker-execution-receipts/record";

    /**
     * 回执发布失败时是否失败开放。
     *
     * <p>默认 true 表示只记录低敏日志，不中断数据同步。若某些客户要求“控制面审计必须成功才允许继续”，
     * 可以改成 false，让发布失败直接抛出异常。</p>
     */
    private Boolean failOpen = true;

    /**
     * HTTP 连接超时毫秒数。
     */
    private Long connectTimeoutMs = 1000L;

    /**
     * HTTP 读取超时毫秒数。
     */
    private Long readTimeoutMs = 1500L;

    /**
     * datasource 执行器标识。
     */
    private String executorId = "datasource-management-runner";

    /**
     * 来源服务名。
     */
    private String sourceService = "datasource-management";
}
