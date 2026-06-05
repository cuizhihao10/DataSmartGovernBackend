/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolAdapterPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 外部 Agent 协议适配策略视图。
 *
 * <p>这个对象描述“DataSmart 当前准备怎样暴露 MCP/A2A 能力”，而不是描述某一个具体工具或 Skill。
 * 在商业化 Agent 平台里，协议适配层很容易变成新的绕权入口：外部模型或外部 Agent 看到工具后，
 * 可能绕过 Java 控制面的审批、审计、租户隔离和工具沙箱，直接要求执行高风险动作。
 * 因此我们先把策略字段显式返回给前端、运维和 Python Runtime，让所有调用方都能看到当前只是
 * preview/projection 阶段，还不能把这些信息当作真实可执行协议端点。</p>
 *
 * @param previewOnly 是否只读预览。true 表示本接口只展示映射草案，不启动 MCP/A2A Server，不处理真实执行请求
 * @param executionEnabled 外部协议是否允许直接执行。当前必须为 false，真实执行仍要走现有 tool preflight、approval、outbox
 * @param externalServerEnabled 是否已经启用对外协议服务。当前为 false，避免误认为已经有可联网调用的 MCP/A2A endpoint
 * @param metadataOnly 是否仅暴露元数据。true 表示只暴露 capability/descriptor 摘要，不暴露 prompt 正文、工具实参或资源正文
 * @param requiresGatewaySignature 是否要求经由智能网关签名。后续接真实入口时，网关签名用于证明请求来自受控入口
 * @param requiresPermissionAdminDecision 是否要求 permission-admin 参与授权判定，防止协议适配层绕过 RBAC/ABAC
 * @param requiresHumanConfirmationForSensitiveTools 敏感或写操作工具是否必须有人类确认，呼应 MCP 工具安全建议
 * @param sensitivePayloadPolicy 敏感载荷策略，明确哪些内容永远不能进入外部协议预览响应
 * @param runtimeEventPolicy 后续真实接入时的事件记录策略，用于说明协议调用也必须可审计、可回放、可诊断
 * @param unsupportedCapabilities 当前尚未实现的协议能力，避免产品或前端把 preview 误解为完整协议实现
 */
public record AgentExternalProtocolAdapterPolicyView(
        Boolean previewOnly,
        Boolean executionEnabled,
        Boolean externalServerEnabled,
        Boolean metadataOnly,
        Boolean requiresGatewaySignature,
        Boolean requiresPermissionAdminDecision,
        Boolean requiresHumanConfirmationForSensitiveTools,
        String sensitivePayloadPolicy,
        String runtimeEventPolicy,
        List<String> unsupportedCapabilities
) {
}
