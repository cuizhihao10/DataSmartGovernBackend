/**
 * @Author : Cui
 * @Date: 2026/06/20 23:18
 * @Description DataSmart Govern Backend - DataSyncAgentExecuteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Agent 触发 data-sync.execute 时，datasource-management 内部入口接收的命令请求。
 *
 * <p>这个 DTO 与 task-management 侧同名 DTO 保持字段级 JSON 契约一致，但两边不通过 Java 依赖共享类。
 * 这样做是微服务解耦的基本原则：task-management 只知道“我要发一个 data-sync.execute 命令”，
 * datasource-management 只知道“我要接收一个已经通过上游治理的内部命令”，两边通过 HTTP JSON 契约协作，
 * 不把编译期依赖绑在一起。</p>
 *
 * <p>安全边界说明：</p>
 * <p>1. 请求里只允许携带低敏控制面字段，例如 commandId、tenantId、projectId、templateId、priority。</p>
 * <p>2. 不允许在本 DTO 中承载 SQL、连接串、密码、样本数据、prompt、模型输出或真实工具参数正文。</p>
 * <p>3. name/description 虽然是普通文本，但仍可能来自 Agent 参数，因此服务层不会把 description 原样复制到
 *    公开任务描述中，而是生成低敏说明，避免把自然语言上下文误写入任务列表或审计投影。</p>
 */
@Data
public class DataSyncAgentExecuteRequest {

    /**
     * Agent Runtime 生成的稳定命令 ID。
     *
     * <p>同一个 commandId 在跨服务重试、HTTP 超时重放、人工补偿重放时必须保持不变。
     * datasource-management 会把它写入本地 receipt 表，并用它判断是否已经创建过同步任务。</p>
     */
    @NotBlank(message = "commandId 不能为空")
    private String commandId;

    /**
     * 跨服务幂等键。
     *
     * <p>commandId 表示“哪条命令”，idempotencyKey 表示“这次副作用的幂等范围”。
     * 当前上游约定通常为 agent-async-tool:{commandId}，后续如果一个命令拆成多个分片副作用，
     * 可以继续扩展幂等键粒度，而不必改变 commandId 的含义。</p>
     */
    @NotBlank(message = "idempotencyKey 不能为空")
    private String idempotencyKey;

    /**
     * Agent 工具审计 ID，用于和 Agent timeline、审批事实、运行时事件做低敏关联。
     */
    private String auditId;

    /**
     * Agent 会话 ID。
     */
    private String sessionId;

    /**
     * Agent run ID。它与 datasource 的 sync_execution.id 不是同一个 ID 空间。
     */
    private String runId;

    /**
     * 工具编码。当前内部入口只接受 data-sync.execute。
     */
    @NotBlank(message = "toolCode 不能为空")
    private String toolCode;

    /**
     * 租户边界。同步任务必须落在明确租户内，不能创建无租户任务。
     */
    @NotNull(message = "tenantId 不能为空")
    private Long tenantId;

    /**
     * 项目边界快照。任务最终项目归属会由同步模板决定，本字段用于命令审计和边界一致性排查。
     */
    private Long projectId;

    /**
     * 工作空间边界快照。任务最终 workspace 仍以同步模板为准。
     */
    private Long workspaceId;

    /**
     * 原始业务发起人 ID。
     *
     * <p>task-management 以字符串传递该字段，是为了兼容未来统一账号体系里可能出现的非数字主体 ID。
     * datasource 当前任务表仍使用 Long，因此服务层会做“能转数字就使用，不能转就回退到服务账号”的归一化。</p>
     */
    private String actorId;

    /**
     * 链路追踪 ID。只用于日志、响应和排障关联，不参与幂等裁决。
     */
    private String traceId;

    /**
     * 历史兼容模板 ID。若 syncTemplateId 为空，会把该字段当作 datasource 同步模板 ID。
     */
    private Long templateId;

    /**
     * data-sync 同步模板 ID。新链路优先使用该字段。
     */
    private Long syncTemplateId;

    /**
     * 用户期望的任务名称。
     *
     * <p>服务层会追加 commandId 短后缀来避免任务名称唯一键冲突，并限制长度，避免同名重复命令互相覆盖。</p>
     */
    private String name;

    /**
     * 用户期望的任务说明。当前不原样落库为任务 description，避免复制 Agent 参数正文。
     */
    private String description;

    /**
     * 同步任务优先级。为空时服务层默认 MEDIUM。
     */
    private String priority;

    /**
     * 执行模式。为空时服务层默认 MANUAL。
     */
    private String runMode;

    /**
     * 任务负责人。为空时优先使用可解析的 actorId，否则回退到服务账号 ID 0。
     */
    private Long ownerId;
}
