/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandStageRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker command 入箱请求。
 *
 * <p>该请求由 task-management 内部构造，不直接暴露给普通用户。
 * 它描述的是“任务中心即将让 data-sync 执行一条同步命令”这件事，而不是 data-sync 真正执行结果。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerCommandStageRequest {

    /**
     * Agent command ID。
     */
    private String commandId;

    /**
     * 跨服务幂等键。
     */
    private String idempotencyKey;

    /**
     * task-management 任务 ID。
     */
    private Long taskId;

    /**
     * Agent 会话和 run ID。
     */
    private String sessionId;
    private String runId;

    /**
     * Agent 工具审计 ID。
     */
    private String auditId;

    /**
     * 工具编码。
     */
    private String toolCode;

    /**
     * 目标服务。
     */
    private String targetService;

    /**
     * 本条 outbox 命令表达的操作。
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
     * 历史模板 ID 与 data-sync 模板 ID。
     */
    private Long templateId;
    private Long syncTemplateId;

    /**
     * 低敏执行选项。
     * 当前只允许保存 priority、runMode、ownerId 这类控制字段，不保存 SQL、密码、样本数据或工具参数正文。
     */
    private String priority;
    private String runMode;
    private Long ownerId;
}
