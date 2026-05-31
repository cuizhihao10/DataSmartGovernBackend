/**
 * @Author : Cui
 * @Date: 2026/05/31 16:42
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandInbox.java
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
 * Agent 异步工具命令 Inbox 实体。
 *
 * <p>Inbox Pattern 是事件驱动系统中非常实用的可靠性模式：消费者不是收到消息后直接盲目执行业务，
 * 而是先把消息的业务身份写入本地数据库，并用唯一索引裁决“这条命令是否已经消费过”。</p>
 *
 * <p>本表与 task 主表的职责不同：</p>
 * <p>1. Inbox 保存跨服务命令来源、幂等身份、隔离边界和消费结果；</p>
 * <p>2. Task 保存任务当前状态、队列、租约、重试和执行结果；</p>
 * <p>3. 同一个 command 只能创建一个 task，但一个 task 后续可以产生多个 execution run。</p>
 */
@Data
@TableName("agent_async_task_command_inbox")
public class AgentAsyncTaskCommandInbox {

    /**
     * 数据库主键，仅用于表内更新和运维定位。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Agent Runtime 生成的稳定命令 ID。
     */
    private String commandId;

    /**
     * 跨服务幂等键。数据库会同时对 commandId 和 idempotencyKey 建唯一索引。
     */
    private String idempotencyKey;

    /**
     * 消息协议版本。
     */
    private String schemaVersion;

    /**
     * 命令类型。
     */
    private String commandType;

    /**
     * Agent 工具执行审计 ID。
     */
    private String auditId;

    /**
     * Agent 会话 ID。
     */
    private String sessionId;

    /**
     * Agent Run ID。
     */
    private String runId;

    /**
     * 工具编码。
     */
    private String toolCode;

    /**
     * 目标业务模块。
     */
    private String targetService;

    /**
     * 目标端点模板。
     */
    private String targetEndpoint;

    /**
     * 租户、项目、工作空间共同构成异步动作隔离边界。
     */
    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * 原始发起者与 traceId，用于审计和链路回放。
     */
    private String actorId;
    private String traceId;

    /**
     * 受控载荷引用，不保存原始参数值。
     */
    private String payloadReference;

    /**
     * 参数名与敏感参数名 JSON 快照。
     *
     * <p>这里只保存字段名，方便审计“任务执行前需要读取哪些参数、哪些字段必须脱敏”，
     * 不保存参数值，避免 Inbox、死信和日志成为敏感信息副本。</p>
     */
    private String argumentNames;
    private String sensitiveArgumentNames;

    /**
     * 消费状态，例如 PROCESSING、TASK_CREATED。
     */
    private String consumeState;

    /**
     * 由该命令创建的任务 ID。
     */
    private Long taskId;

    /**
     * 首次和最近一次收到该命令的时间。
     *
     * <p>重复消息只刷新 lastSeenTime，不重复创建任务。该字段也可用于后续计算重复投递率和清理策略。</p>
     */
    private LocalDateTime firstSeenTime;
    private LocalDateTime lastSeenTime;

    /**
     * 数据库记录创建与更新时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
