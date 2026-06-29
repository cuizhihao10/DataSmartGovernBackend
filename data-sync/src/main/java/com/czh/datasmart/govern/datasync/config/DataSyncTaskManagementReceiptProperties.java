/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 投递 task-management execution receipt 的配置。
 *
 * <p>task-management receipt 是跨模块闭环的关键：data-sync 已经知道 execution 是否完成或失败，
 * 但平台任务中心需要一份低敏投影，才能在统一任务历史、Agent timeline、运维诊断和告警中展示“下游真实执行结果”。</p>
 *
 * <p>为什么默认 deliveryRequired=false：</p>
 * <p>1. data-sync complete/fail 是领域事实，不能因为任务中心暂时不可用而反向阻塞同步状态机；</p>
 * <p>2. task-management receipt 当前是投影和诊断面，短期投递失败可以通过日志、指标和后续 outbox/retry 机制补偿；</p>
 * <p>3. 如果客户要求强一致任务中心，可在生产环境把 deliveryRequired 打开，并配合重试、死信和告警一起使用。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.task-management-receipt")
public class DataSyncTaskManagementReceiptProperties {

    /**
     * 是否启用 task-management receipt 投递。
     */
    private boolean enabled = true;

    /**
     * receipt 投递失败是否阻断当前 data-sync 派发流程。
     *
     * <p>默认 false，表示投影失败只告警不影响 execution complete/fail。
     * 如果设置 true，HTTP 不可用或 task-management 返回失败会向上抛异常，需要调用方明确接受这种强一致代价。</p>
     */
    private boolean deliveryRequired = false;

    /**
     * task-management 基础地址。
     *
     * <p>本地默认端口 8081。生产环境可以替换为 Nacos 服务名、内部 gateway、service mesh 虚拟服务或受控内网域名。
     * 该地址不得出现在业务响应、runtime event、receipt 内容或普通审计摘要中。</p>
     */
    private String baseUrl = "http://localhost:8081";

    /**
     * task-management 记录 DataSync worker execution receipt 的 internal 路径。
     */
    private String recordPath = "/internal/data-sync-worker-execution-receipts/record";

    /**
     * data-sync 作为 receipt 来源服务写入的服务名。
     */
    private String sourceService = "data-sync";

    /**
     * 内部调用使用的服务账号角色。
     */
    private String actorRole = "SERVICE_ACCOUNT";

    /**
     * 建立 HTTP 连接的超时时间，单位毫秒。
     */
    private long connectTimeoutMs = 1000L;

    /**
     * 读取 task-management 响应的超时时间，单位毫秒。
     */
    private long readTimeoutMs = 2000L;
}
