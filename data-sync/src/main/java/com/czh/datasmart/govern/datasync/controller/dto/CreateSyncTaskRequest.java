/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - CreateSyncTaskRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建同步任务请求。
 *
 * <p>任务来自模板，但任务拥有自己的负责人、优先级、调度配置和运行模式。
 * 这种拆分可以支撑“同一个模板生成多个运营任务”，例如每日同步、一次性补数、紧急回放各自独立管理。
 */
@Data
public class CreateSyncTaskRequest {

    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>默认继承模板项目；如果请求显式传入，服务端会校验它必须与模板一致。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>默认继承模板工作空间；如果请求显式传入，服务端会校验它必须与模板一致。
     */
    private Long workspaceId;

    @NotNull(message = "模板 ID 不能为空")
    private Long templateId;

    /**
     * 任务分组编码。
     *
     * <p>可选字段。适合前端、导入工具或 Agent 在创建任务时把一批任务归入同一个业务分组。
     * 例如一次全库迁移可能会生成多张表的多个同步任务，它们可以共享同一个 groupCode，便于后续批量查看、
     * 批量下线、导出、故障定位和组级调度能力扩展。服务端会把编码规范化为大写并限制字符集，避免导入导出时
     * 出现大小写、空格或特殊字符导致的重复分组。</p>
     */
    private String groupCode;

    /**
     * 任务分组展示名称。
     *
     * <p>可选字段。为空时服务端会使用 groupCode 作为展示名。groupName 只用于展示和低敏审计摘要，
     * 不作为稳定引用；稳定引用始终使用 groupCode。</p>
     */
    private String groupName;

    private String name;
    private String description;
    private String priority;
    private String scheduleConfig;
    private String runMode;
    private Long ownerId;

    /**
     * 高风险任务是否已经完成外部审批确认。
     *
     * <p>该字段只用于当前闭环版本承接“已经由运营台、审批流或受控本地 E2E 环境确认”的事实，
     * 不是让普通用户绕过审批。Service 层仍会结合 actorRole 判断调用方是否具备提交批准事实的权限；
     * 后续正式接入 permission-admin 的 approvalFactId 后，应优先以服务端审批事实为准，而不是只相信布尔值。</p>
     */
    private Boolean approvalConfirmed;

    /**
     * 审批事实 ID。
     *
     * <p>这里保存低敏引用，例如 {@code approval:sync-local-e2e-001}，用于审计时说明本次高风险任务
     * 为什么被允许入队。它不应该包含 SQL、表名、字段映射、连接串、token、密码或任何业务样本数据。</p>
     */
    private String approvalFactId;
}
