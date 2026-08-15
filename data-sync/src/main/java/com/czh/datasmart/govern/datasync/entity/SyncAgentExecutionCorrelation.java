/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncAgentExecutionCorrelation.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 工具调用与 data-sync execution 的低敏关联事实。
 *
 * <p>Agent Runtime、Kafka、data-sync worker 各自拥有自己的状态机，不能通过互相覆盖状态来“合并”它们。
 * 这张表只保存跨域关联键，让运维查询可以把几套真实状态按同一 execution 聚合起来。它不保存 prompt、
 * 工具参数、SQL、凭据或模型输出，因此不是第二个审计库，也不是新的执行状态机。</p>
 */
@Data
@TableName("data_sync_agent_execution_correlation")
public class SyncAgentExecutionCorrelation {

    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户边界。 */
    private Long tenantId;

    /** 项目边界。 */
    private Long projectId;

    /** 同步任务 ID。 */
    private Long syncTaskId;

    /** 被 Agent 工具调用创建的 execution ID。 */
    private Long syncExecutionId;

    /** Agent 异步命令 ID；直接 Agent 工具入口没有初始命令，因此允许为空。 */
    private String commandId;

    /** ASYNC_AGENT_COMMAND 或 DIRECT_AGENT_TOOL，用于准确解释入口链路。 */
    private String entryMode;

    /** Agent 会话 ID。 */
    private String sessionId;

    /** Agent Run ID。 */
    private String runId;

    /** Java Agent 工具审计 ID。 */
    private String auditId;

    /** 跨服务追踪 ID。 */
    private String traceId;

    /** 关联事实首次写入时间。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 关联事实更新时间；重复请求不会改写原始身份。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
