/**
 * @Author : Cui
 * @Date: 2026/06/20 23:35
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxRecoveryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker outbox 超时恢复请求。
 *
 * <p>这个请求用于内部补偿器、运维控制面或后续后台 scheduler：
 * 当 outbox 已经进入 {@code DISPATCHING}，但长时间没有收到 datasource-management 的成功 receipt，
 * 系统需要判断该命令是否可能因为 worker 崩溃、进程重启、网络中断或下游调用超时而“悬挂”。</p>
 *
 * <p>为什么请求里不直接暴露 timeoutSeconds：</p>
 * <p>1. 超时阈值属于平台级可靠性策略，应由配置中心、环境配置或运维策略统一控制；</p>
 * <p>2. 如果调用方随意传入过短阈值，可能把仍在真实投递中的命令错误恢复回 DEFERRED，造成重复副作用风险；</p>
 * <p>3. 请求只负责限定本次恢复扫描范围，例如按租户、项目和 limit 控制批量大小。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerOutboxRecoveryRequest {

    /**
     * 执行恢复动作的补偿器或运维工具标识。
     *
     * <p>该字段会进入响应摘要，便于后续审计“是谁触发了恢复”。当前 outbox 表还没有 executor owner 字段，
     * 因此本阶段不把它持久化到 outbox 行中；后续如果引入 lease/heartbeat，可以把 executorId 扩展成持久化租约所有者。</p>
     */
    private String executorId;

    /**
     * 可选租户过滤条件。
     *
     * <p>生产环境中推荐补偿器按租户分片运行，避免一个大租户产生大量 stale DISPATCHING 后，
     * 一次恢复扫描占满数据库和 worker 资源。</p>
     */
    private Long tenantId;

    /**
     * 可选项目过滤条件。
     *
     * <p>项目级过滤适合灰度恢复、项目级故障隔离和定向排障。例如某个项目的 datasource-management
     * 下游刚刚恢复时，可以只恢复该项目的悬挂命令。</p>
     */
    private Long projectId;

    /**
     * 本次最多扫描和尝试恢复多少条 stale DISPATCHING 命令。
     *
     * <p>服务端会再次裁剪该值，避免一次恢复动作把大量记录从 DISPATCHING 推回 DEFERRED，
     * 造成后续 dispatcher 瞬时高峰。补偿类接口一般宁愿多轮小批量，也不要单轮大批量。</p>
     */
    private Integer limit;
}
