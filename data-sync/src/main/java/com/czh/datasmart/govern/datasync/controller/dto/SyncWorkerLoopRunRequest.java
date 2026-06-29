/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - SyncWorkerLoopRunRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * data-sync worker loop 单轮触发请求。
 *
 * <p>该请求用于手动运维触发、internal 机器调用以及后续外部 worker 服务复用。
 * 它不携带模板配置正文、字段映射、过滤条件、SQL、checkpoint 原始值、连接地址、账号、密码或样本数据；
 * 真正执行所需的敏感上下文仍由 data-sync 和 datasource-management 在受控服务端链路内解析。</p>
 */
@Data
public class SyncWorkerLoopRunRequest {

    /**
     * 可选执行器 ID。
     *
     * <p>为空时使用配置中的默认 executorId。
     * 如果外部 worker 调用该接口，建议传入稳定且可追踪的实例 ID，例如 sync-worker-a-001，
     * 方便从 execution.executor_id 追溯认领方。</p>
     */
    private String executorId;

    /**
     * 可选租户过滤。
     *
     * <p>为空表示认领全平台队列中最早的一条任务；租户专属 worker 或灰度验证时可以传 tenantId。</p>
     */
    private Long tenantId;

    /**
     * 单轮最多处理多少条 execution。
     *
     * <p>服务端会做 1 到 50 的保护性裁剪，避免调用方误传过大数值造成数据库和下游 connector runtime 突发压力。</p>
     */
    private Integer maxExecutions;

    /**
     * 本轮认领 execution 使用的租约秒数。
     *
     * <p>服务端会做 30 到 1800 秒的保护性裁剪。该值应大于 datasource-run-once 的读取超时，
     * 否则 worker 仍在等待远端响应时可能已经被过期租约恢复任务重新入队。</p>
     */
    private Long leaseSeconds;
}
