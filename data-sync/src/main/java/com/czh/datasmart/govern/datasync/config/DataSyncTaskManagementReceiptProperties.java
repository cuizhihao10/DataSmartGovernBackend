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

    /**
     * receipt outbox 配置。
     *
     * <p>这里把 outbox 放在 task-management-receipt 配置块下面，是因为它不是通用消息 outbox，
     * 而是专门服务于 “data-sync execution 完成/失败事实 -> task-management 投影” 这条跨服务闭环。
     * 后续如果 data-sync 还要向 Kafka、审计中心、告警中心投递其它事件，可以再抽象统一 outbox。</p>
     */
    private Outbox outbox = new Outbox();

    @Data
    public static class Outbox {

        /**
         * 是否启用本地 durable outbox。
         *
         * <p>默认启用。关闭后会退回到“直接 HTTP 投递”的历史模式，只适合本地排障；
         * 商业化环境建议保持开启，因为 task-management 短暂不可用时必须保留待补偿投递意图。</p>
         */
        private boolean enabled = true;

        /**
         * 是否在写入 outbox 后立即尝试投递。
         *
         * <p>开启后正常路径仍然接近实时：complete/fail 后立即 POST 给 task-management；
         * 如果这次失败，再由 scheduler 按 nextRetryAt 重试。</p>
         */
        private boolean immediateDeliveryEnabled = true;

        /**
         * 是否启用后台定时重试。
         *
         * <p>如果关闭，失败 receipt 会停在 PENDING/RETRY_WAIT，需要运维手动调用 internal dispatch 接口。</p>
         */
        private boolean schedulerEnabled = true;

        /** 后台调度器启动后首次扫描延迟。 */
        private long initialDelayMs = 45000L;

        /** 后台调度器上一轮结束后多久再次扫描。 */
        private long fixedDelayMs = 30000L;

        /** 单轮最多处理多少条 due receipt，避免一次性打爆 task-management。 */
        private int batchSize = 20;

        /** 单条 receipt 最大投递次数，达到后进入 DEAD_LETTER。 */
        private int maxAttempts = 6;

        /** 初始退避秒数；第 N 次失败会按指数退避增长。 */
        private long baseBackoffSeconds = 30L;

        /** 最大退避秒数，避免长时间故障后 nextRetryAt 过远。 */
        private long maxBackoffSeconds = 1800L;

        /**
         * DELIVERING 状态最大允许停留秒数。
         *
         * <p>如果进程在“标记 DELIVERING 后、写 DELIVERED/RETRY_WAIT 前”崩溃，记录会卡在 DELIVERING。
         * 后台调度器会把超过该时间的 DELIVERING 视为可重试，形成崩溃恢复能力。</p>
         */
        private long staleDeliveringSeconds = 300L;

        /** 调度器和手动补偿默认使用的平台系统 actorId。 */
        private Long systemActorId = 0L;

        /** 调度器和手动补偿默认使用的平台系统角色。 */
        private String systemActorRole = "SERVICE_ACCOUNT";

        /** 自动生成 traceId 的前缀，不承载业务参数或敏感执行信息。 */
        private String traceIdPrefix = "data-sync-task-management-receipt-outbox";
    }
}
