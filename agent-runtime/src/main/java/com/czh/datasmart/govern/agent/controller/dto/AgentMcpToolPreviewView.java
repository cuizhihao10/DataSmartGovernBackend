/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentMcpToolPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * MCP Tool 映射预览。
 *
 * <p>MCP 的 Tool 是“模型可发现并可请求调用的外部能力”。DataSmart 内部已经有
 * `AgentToolDescriptorView` 记录工具治理信息，但对外协议映射时不能原样暴露内部 endpoint、
 * 工具实参、服务地址或执行路径。因此这个视图只展示低敏摘要：工具名、展示名、描述、风险、
 * 参数数量、审批要求、是否只读、是否具备租户/项目隔离等。</p>
 *
 * <p>为什么只返回 `inputSchemaRef` 而不是完整 inputSchema：
 * MCP tools/list 通常会返回 JSON Schema，但当前阶段只是 adapter preview。真实 JSON Schema
 * 需要结合租户、角色、项目、数据域和工具版本生成，并且敏感参数字段可能需要按调用方权限脱敏。
 * 这里先返回引用地址，表达“未来可以从 Java 控制面受控获取 schema”，避免把 schema 当作
 * 可以被外部 Agent 直接执行的授权凭证。</p>
 *
 * @param name MCP tool name，使用 DataSmart toolCode，便于审计时回溯内部工具目录
 * @param title 面向人类或外部 Agent 目录展示的标题
 * @param description 工具能力说明，只允许低敏描述，不包含 endpoint、URL、密钥、SQL 或样例数据
 * @param inputSchemaRef 受控 schema 引用路径，真实 schema 读取仍需要经过 Java 控制面和权限校验
 * @param requiredParameterCount 必填参数数量，用于让外部 Agent 预估是否需要继续向用户追问
 * @param sensitiveParameterCount 敏感参数数量，只暴露数量，不暴露敏感字段名和参数值
 * @param taskSupport MCP task-augmented execution 提示，当前用于说明同步/异步/审批型工具的未来适配方向
 * @param riskLevel DataSmart 工具风险等级，外部协议适配层必须据此触发确认、审批或阻断
 * @param requiresApproval 是否需要审批。true 表示外部协议不能直接执行，必须进入审批/确认流
 * @param readOnly 是否只读。只读仍不等于无风险，因为查询也可能读取敏感元数据或异常样本
 * @param tenantScoped 是否要求租户边界。商业化多租户部署中应优先保持 true
 * @param projectScoped 是否要求项目边界。数据治理对象通常必须绑定项目或授权项目集合
 * @param allowedActions 低敏动作标签，例如 VIEW、GENERATE、CREATE，用于外部 Agent 做规划前过滤
 * @param payloadPolicy 载荷暴露策略，明确 preview 中不出现工具实参、执行路径、下游 URL 或工具结果
 */
public record AgentMcpToolPreviewView(
        String name,
        String title,
        String description,
        String inputSchemaRef,
        int requiredParameterCount,
        int sensitiveParameterCount,
        String taskSupport,
        String riskLevel,
        Boolean requiresApproval,
        Boolean readOnly,
        Boolean tenantScoped,
        Boolean projectScoped,
        List<String> allowedActions,
        String payloadPolicy
) {
}
