/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - CreateAgentSessionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建 Agent 会话请求。
 *
 * <p>会话是 Agent Runtime 的最小上下文容器。它需要在创建时确定租户、项目、工作空间、操作者和目标，
 * 因为后续模型调用、工具调用、审计事件、文件缓存、向量检索都必须继承这些边界。
 *
 * <p>为什么不只传一段 prompt：
 * 1. prompt 无法表达租户/项目隔离边界；
 * 2. prompt 无法稳定表达哪些工具可用、哪些工具只读、哪些工具需要审批；
 * 3. prompt 无法支持后续审计追踪和运行恢复；
 * 4. 商业化 Agent 必须有可解释、可治理、可追责的上下文模型。
 *
 * @param tenantId 租户 ID，所有 Agent 会话必须明确归属租户。
 * @param projectId 项目 ID，当前推荐默认使用项目级隔离。
 * @param workspaceId 工作空间 ID，可为空；为空时表示项目默认工作空间。
 * @param actorId 操作者 ID 或服务账号 ID。
 * @param channel 来源渠道，例如 WEB、API、DINGTALK、WECHAT、SYSTEM。
 * @param objective 用户本次会话的治理目标，例如“分析数据源质量问题并生成规则建议”。
 * @param isolationLevel 工作空间隔离级别，默认 PROJECT。
 * @param toolBindings 初始工具绑定列表，后续也可通过工具绑定接口追加。
 */
public record CreateAgentSessionRequest(
        @NotNull(message = "tenantId 不能为空")
        Long tenantId,

        @NotNull(message = "projectId 不能为空")
        Long projectId,

        Long workspaceId,

        @NotBlank(message = "actorId 不能为空")
        @Size(max = 128, message = "actorId 最多 128 个字符")
        String actorId,

        @Size(max = 64, message = "channel 最多 64 个字符")
        String channel,

        @NotBlank(message = "objective 不能为空")
        @Size(max = 2000, message = "objective 最多 2000 个字符")
        String objective,

        WorkspaceIsolationLevel isolationLevel,

        @Valid
        @Size(max = 20, message = "单个会话初始最多绑定 20 个工具")
        List<BindAgentToolRequest> toolBindings) {
}
