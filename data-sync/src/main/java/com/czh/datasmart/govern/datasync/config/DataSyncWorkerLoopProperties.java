/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 内嵌 worker loop 配置。
 *
 * <p>worker loop 是把“执行记录已经可认领”和“connector runtime 已经可 run-once”串起来的闭环组件。
 * 它不负责创建同步任务、不直接拼接 SQL、不读取数据源密码，也不替代 datasource-management 的真实读写能力；
 * 它只做一件事：按租约协议认领 execution，然后把已认领的 execution 交给受控 run-once 派发服务。</p>
 *
 * <p>为什么把调度参数放到配置里：</p>
 * <p>1. 商用环境中不同租户、不同连接器、不同客户内网条件下，worker 扫描频率和单轮执行量差异很大；</p>
 * <p>2. 如果固定写死在代码里，遇到队列积压、目标端限流、数据库压力或维护窗口时无法快速调参；</p>
 * <p>3. 当前阶段先提供内嵌 worker loop，未来迁移到独立 worker 服务、Kubernetes Job、调度中心或分布式 worker 池时，
 *    可以继续复用这些语义作为外部 worker 的默认配置契约。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.worker-loop")
public class DataSyncWorkerLoopProperties {

    /**
     * 是否启用内嵌定时调度器。
     *
     * <p>注意：这里控制的是后台定时触发，不控制手动/internal API。
     * 默认关闭是为了避免开发者只启动 data-sync 时，服务自动认领历史 QUEUED 任务并触发真实源端读取/目标端写入。
     * 生产或完整联调环境可以显式开启，让 data-sync 自身承担最小 worker 闭环。</p>
     */
    private boolean schedulerEnabled = false;

    /**
     * 内嵌 worker 默认执行器 ID。
     *
     * <p>executorId 会写入 data_sync_execution.executor_id，并参与 heartbeat、complete、fail 等后续回调校验。
     * 它不是用户身份，也不是服务账号密钥，只是租约持有者标识；多实例部署时建议通过环境变量拼接实例名或 Pod 名，
     * 例如 data-sync-worker-${HOSTNAME}，方便排查“哪一个实例认领了任务”。</p>
     */
    private String executorId = "data-sync-embedded-worker";

    /**
     * 单次 worker loop 默认最多处理多少条 execution。
     *
     * <p>该值越大，单轮可以更快追赶积压，但也可能让一个 data-sync 实例长时间占用业务线程并连续触发下游写入。
     * 当前默认 3 是一个保守起点：既能证明链路闭环，又不会在本地或小型环境中造成突发压力。</p>
     */
    private int maxExecutionsPerRun = 3;

    /**
     * 认领 execution 时默认申请的租约秒数。
     *
     * <p>租约必须覆盖一次 run-once 调用的正常耗时，并为 complete/fail 回调留出余量。
     * 如果后续打开多批循环或大表分片，需要同时调整 datasource-run-once 超时、worker heartbeat 和该租约时长。</p>
     */
    private long leaseSeconds = 180L;

    /**
     * 可选租户过滤。
     *
     * <p>为空表示平台级 worker 可认领所有租户的 QUEUED execution。
     * 租户专属部署、监管隔离客户或大型客户独占 worker 池可以指定 tenantId，避免跨租户抢占资源。</p>
     */
    private Long tenantId;

    /**
     * 调度器启动后的首次执行延迟，单位毫秒。
     *
     * <p>延迟启动可以让数据库连接池、Nacos 注册、下游 datasource-management 和网关身份链路先完成预热。</p>
     */
    private long initialDelayMs = 20000L;

    /**
     * 上一轮 worker loop 结束后多久启动下一轮，单位毫秒。
     *
     * <p>使用 fixedDelay 而不是 fixedRate，是为了避免上一轮还在执行真实数据读写时，本实例又启动下一轮认领。</p>
     */
    private long fixedDelayMs = 10000L;

    /**
     * 内嵌 worker 写审计时使用的系统 actorId。
     *
     * <p>当前使用 0 表示平台系统动作。接入 Keycloak/OIDC service account 后，可以替换为真实服务账号主体 ID。</p>
     */
    private Long systemActorId = 0L;

    /**
     * 内嵌 worker 写审计时使用的角色。
     *
     * <p>这里使用 SERVICE_ACCOUNT，与 gateway/permission-admin 服务账号委托契约保持一致。</p>
     */
    private String systemActorRole = "SERVICE_ACCOUNT";

    /**
     * 自动生成 traceId 时使用的前缀。
     *
     * <p>traceId 只用于链路排障和审计关联，不承载业务参数、SQL、连接地址、字段值或样本数据。</p>
     */
    private String traceIdPrefix = "data-sync-worker-loop";

    /**
     * 返回经过保护边界裁剪后的单轮最大执行数。
     *
     * <p>最大值限制为 50，避免误配置导致一次 HTTP 手动触发或一次定时调度认领过多任务。
     * 真正的大规模吞吐应通过多 worker、分片、队列和配额控制解决，而不是让单轮无限扩大。</p>
     */
    public int effectiveMaxExecutionsPerRun(Integer override) {
        int value = override == null || override <= 0 ? maxExecutionsPerRun : override;
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 50);
    }

    /**
     * 返回经过保护边界裁剪后的租约秒数。
     *
     * <p>最小 30 秒用于避免 worker 刚认领就被恢复任务误判过期；最大 1800 秒与现有 lease service 上限保持一致。</p>
     */
    public long effectiveLeaseSeconds(Long override) {
        long value = override == null || override <= 0 ? leaseSeconds : override;
        if (value < 30L) {
            return 30L;
        }
        return Math.min(value, 1800L);
    }
}
