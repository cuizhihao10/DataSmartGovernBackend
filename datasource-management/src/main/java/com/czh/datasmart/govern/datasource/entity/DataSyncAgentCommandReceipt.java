/**
 * @Author : Cui
 * @Date: 2026/06/20 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentCommandReceipt.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * datasource-management 侧 Agent 命令 receipt。
 *
 * <p>task-management 已经有自己的 DataSync worker command outbox，但那张表只能证明
 * “任务中心准备把命令投递给 data-sync”。本表则证明“datasource-management 已经接收该命令，
 * 并把它转换为本模块内的同步任务副作用”。两张表分别属于两个服务，不能互相替代。</p>
 *
 * <p>为什么 datasource 侧也需要 receipt？</p>
 * <p>1. HTTP 超时可能发生在 datasource 已经创建任务之后，如果没有下游幂等表，上游重试会再次创建任务。</p>
 * <p>2. Kafka 或 dispatcher 重放时，同一个 commandId/idempotencyKey 必须复用同一份结果。</p>
 * <p>3. 运维排障时需要从 commandId 反查 syncTaskId，而不是只能在 task-management 的 outbox 中看到“已调用”。</p>
 *
 * <p>安全边界：</p>
 * <p>本表只保存低敏控制面字段，不保存工具参数正文、SQL、连接串、凭据、样本数据、prompt 或模型输出。
 * name/description 也不在这里保存，避免把用户自然语言上下文复制到跨服务 receipt 台账。</p>
 */
@Data
@TableName("sync_agent_command_receipt")
public class DataSyncAgentCommandReceipt {

    /**
     * 数据库自增主键，仅用于表内排序、分页和 updateById。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * datasource-management 本地 receipt ID。
     *
     * <p>当前由 commandId 派生，保证同一 command 在 datasource 侧只会有一条 receipt。
     * 它不是上游 task-management outbox 的 receiptId，两者分别服务不同边界。</p>
     */
    private String receiptId;

    /**
     * Agent Runtime 稳定命令 ID。
     */
    private String commandId;

    /**
     * 跨服务幂等键。与 commandId 一起约束重复投递。
     */
    private String idempotencyKey;

    /**
     * Agent 会话、run 和审计 ID，用于低敏排障关联。
     */
    private String agentSessionId;
    private String agentRunId;
    private String auditId;

    /**
     * 工具编码。当前只接受 data-sync.execute。
     */
    private String toolCode;

    /**
     * 租户、项目和工作空间边界快照。
     *
     * <p>任务最终 project/workspace 仍然以同步模板为准；这里保存请求快照是为了排查上游命令和模板归属是否一致。</p>
     */
    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * 原始发起人 ID。保留字符串形式以兼容未来统一账号体系。
     */
    private String actorId;

    /**
     * 链路追踪 ID。
     */
    private String traceId;

    /**
     * 上游传入的历史模板 ID，以及 datasource 实际采用的同步模板 ID。
     */
    private Long templateId;
    private Long syncTemplateId;
    private Long resolvedTemplateId;

    /**
     * datasource-management 创建或复用的同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 当前阶段只是创建并入队任务，通常还没有 sync_execution。
     * 后续如果内部入口升级为“创建并立即 claim/run”，该字段会保存对应 execution ID。
     */
    private Long syncExecutionId;

    /**
     * receipt 状态。
     *
     * <p>当前主要使用 RECEIVED 和 QUEUED：
     * RECEIVED 表示幂等键已被 datasource 抢占，正在事务内转换任务；
     * QUEUED 表示任务已经创建并进入同步任务队列。</p>
     */
    private String status;

    /**
     * 同步任务当前低敏状态快照，例如 QUEUED。
     */
    private String downstreamState;

    /**
     * 是否已经越过 datasource 侧副作用边界。
     *
     * <p>创建同步任务和入队都会改变业务台账，因此一旦成功就必须标记为 true，
     * 方便上游判断这条命令是否已经被下游接受。</p>
     */
    private Boolean sideEffectStarted;

    /**
     * 下游是否确认副作用已经执行完成到“可继续调度”的程度。
     *
     * <p>这里的 executed 不代表数据已经搬运完成，只代表命令已经被转换为同步任务并入队。</p>
     */
    private Boolean sideEffectExecuted;

    /**
     * 是否重复命中幂等记录。
     */
    private Boolean duplicate;

    /**
     * 低敏结果说明。不得写入异常堆栈、SQL、连接信息、原始参数或 prompt。
     */
    private String message;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
