/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutbox.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DataSync worker 命令 outbox 实体。
 *
 * <p>这张表由 task-management 持有，用来记录“任务中心准备让 data-sync 执行一次同步动作”的低敏命令事实。
 * 它不是 data-sync 的任务表，也不是 Agent Runtime 的 command outbox，而是 task-management 自己的跨服务命令账本。</p>
 *
 * <p>本表的定位：</p>
 * <p>1. 在调用 data-sync 前先持久化命令，避免任务中心已经认领任务但进程崩溃后完全找不到下游动作；</p>
 * <p>2. 在调用 data-sync 后记录 receipt，保存 syncTaskId/syncExecutionId 这类低敏下游引用；</p>
 * <p>3. 为后续补偿器、重试 dispatcher、运维排障和跨模块闭环提供稳定查询入口；</p>
 * <p>4. 不保存真实工具参数、SQL、样本数据、连接串、凭据、模型输出或用户 prompt。</p>
 */
@Data
@TableName("task_data_sync_worker_command_outbox")
public class DataSyncWorkerCommandOutbox {

    /**
     * 数据库主键。
     * 仅用于表内排序、分页和 updateById；业务幂等不依赖该字段。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * outbox 记录唯一 ID。
     * 推荐由 commandId 派生，便于外部排障时从命令直接定位 outbox 记录。
     */
    private String outboxId;

    /**
     * Agent command ID。
     * 对同一条 Agent 异步工具命令，task-management 只允许创建一条 data-sync worker outbox。
     */
    private String commandId;

    /**
     * 跨服务幂等键。
     * 它会同时传给 data-sync 内部执行入口，让下游也能识别重复请求。
     */
    private String idempotencyKey;

    /**
     * task-management 任务 ID。
     * 用于把 outbox 记录和任务中心主表关联起来。
     */
    private Long taskId;

    /**
     * Agent Runtime run ID。
     * 这里保留字符串形式，因为 Agent run 与 task_execution_run 是两套 ID 空间。
     */
    private String agentRunId;

    /**
     * Agent 会话 ID。
     * 用于后续把 receipt 回填到 Agent timeline 或按会话排障。
     */
    private String agentSessionId;

    /**
     * Agent 工具审计 ID。
     */
    private String auditId;

    /**
     * 工具编码，例如 data-sync.execute。
     */
    private String toolCode;

    /**
     * 目标服务，当前应为 data-sync 或 datasource-management 的内部同步入口。
     */
    private String targetService;

    /**
     * 命令操作类型。
     * 当前固定为 DATA_SYNC_EXECUTE，未来可扩展 DATA_SYNC_RUN_BATCH_ONCE、DATA_SYNC_CANCEL、DATA_SYNC_BACKFILL。
     */
    private String operation;

    /**
     * 租户、项目、工作空间隔离边界。
     */
    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * 原始发起者和 traceId。
     */
    private String actorId;
    private String traceId;

    /**
     * 兼容历史 task template ID。
     */
    private Long templateId;

    /**
     * data-sync 模板 ID。
     */
    private Long syncTemplateId;

    /**
     * outbox 状态。
     */
    private String status;

    /**
     * 投递尝试次数。
     */
    private Integer attemptCount;

    /**
     * 低敏 payload JSON。
     * 该字段只保存 ID、类型、状态和边界字段，不保存工具参数正文。
     */
    private String payloadJson;

    /**
     * payload UTF-8 字节数。
     */
    private Integer payloadSizeBytes;

    /**
     * payload 是否被截断或阻断。
     * 当前正常实现不截断；如果未来超出上限，应阻断命令而不是悄悄截断后执行。
     */
    private Boolean payloadTruncated;

    /**
     * 下一次允许重试时间。
     */
    private LocalDateTime nextRetryAt;

    /**
     * 最近一次开始投递时间。
     */
    private LocalDateTime dispatchedAt;

    /**
     * worker receipt ID。
     * 成功或失败 receipt 都应该有稳定 ID，便于幂等回放和运维检索。
     */
    private String receiptId;

    /**
     * data-sync 返回的同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * data-sync 返回的同步 execution ID。
     */
    private Long syncExecutionId;

    /**
     * 是否已经越过“调用下游”的副作用边界。
     */
    private Boolean sideEffectStarted;

    /**
     * 下游是否确认副作用已经被接受或执行。
     */
    private Boolean sideEffectExecuted;

    /**
     * 最近一次低敏错误摘要。
     */
    private String lastError;

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
