/**
 * @Author : Cui
 * @Date: 2026/05/31 23:35
 * @Description DataSmart Govern Backend - DataSyncAgentExecuteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import lombok.Data;

/**
 * task-management 调用 data-sync 内部 Agent 执行入口时使用的请求 DTO。
 *
 * <p>该 DTO 与 data-sync 模块的内部契约保持字段同名，但不直接依赖 data-sync Java 类。
 * 微服务之间通过 JSON 契约解耦，避免 task-management 编译期依赖 data-sync 模块实现。</p>
 */
@Data
public class DataSyncAgentExecuteRequest {
    private String commandId;
    private String idempotencyKey;
    private String auditId;
    private String sessionId;
    private String runId;
    private String toolCode;
    private Long tenantId;
    private Long projectId;
    private Long workspaceId;
    private String actorId;
    private String traceId;
    private Long templateId;
    private Long syncTemplateId;
    private String name;
    private String description;
    private String priority;
    private String runMode;
    private Long ownerId;
}
