/**
 * @Author : Cui
 * @Date: 2026/06/21 00:00
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDeadLetterManageRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker outbox 死信人工处置请求。
 *
 * <p>该请求面向内部运维控制面、补偿工具或后续平台管理员后台。
 * 它只允许对 {@code DEAD_LETTER} 状态的命令做受控动作，避免普通用户或普通 dispatcher
 * 直接把任意 outbox 改回可执行状态。</p>
 *
 * <p>敏感数据约束：</p>
 * <p>1. commandId 是低敏定位字段，可以用于查询和幂等；</p>
 * <p>2. reason 只允许保存经过服务端脱敏后的短摘要，不能保存 SQL、连接串、凭据、工具实参或内部 endpoint；</p>
 * <p>3. 请求不携带 payloadJson，不携带 datasource 连接信息，也不允许调用方覆盖 outbox 原始 payload。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerOutboxDeadLetterManageRequest {

    /**
     * 执行人工处置的操作者、运维工具或服务账号标识。
     *
     * <p>当前阶段先作为低敏审计摘要进入响应和 lastError 摘要。
     * 后续如果 permission-admin 完成服务账号签名、RBAC 和审计主体注入，
     * 该字段应由认证上下文生成，而不是完全相信调用方传入。</p>
     */
    private String executorId;

    /**
     * 需要处置的 Agent command ID。
     *
     * <p>使用 commandId 而不是数据库自增 id，是因为 commandId 可以贯穿 Agent runtime、
     * task-management outbox 和 datasource-management receipt，适合作为跨模块排障入口。</p>
     */
    private String commandId;

    /**
     * 死信处置动作。
     *
     * <p>{@link DataSyncWorkerOutboxDeadLetterAction#REPLAY} 表示重新放回 DEFERRED；
     * {@link DataSyncWorkerOutboxDeadLetterAction#CLOSE} 表示推进到 CLOSED 终态。</p>
     */
    private DataSyncWorkerOutboxDeadLetterAction action;

    /**
     * 操作原因或事故备注。
     *
     * <p>该字段是给运维和学习排障使用的“短说明”，不是日志正文。
     * 服务端会做换行清理、常见 secret/endpoint/SQL 片段脱敏和长度裁剪；
     * API 响应也不会把脱敏后的正文返回给调用方，只返回可见性策略。</p>
     */
    private String reason;

    /**
     * REPLAY 动作重新进入 DEFERRED 后，距离下一次允许 dispatcher 领取的秒数。
     *
     * <p>设置延迟而不是立即投递，是为了避免刚刚从故障恢复的下游服务被死信重放瞬间打爆。
     * 服务端会裁剪该值，保证它不会小于安全下限，也不会大到让命令长期不可见。</p>
     */
    private Integer retryAfterSeconds;
}
