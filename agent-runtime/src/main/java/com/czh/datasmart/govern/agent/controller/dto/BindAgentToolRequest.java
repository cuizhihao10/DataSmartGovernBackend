/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - BindAgentToolRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.model.AgentToolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Agent 工具绑定请求。
 *
 * <p>工具绑定的业务含义是：允许某个 Agent 会话在指定边界内调用某类平台能力。
 * 它不是立即执行工具，也不是绕过权限中心直接拿到下游服务权限。真正执行工具时仍需要：
 * 1. gateway/permission-admin 判断操作者是否有入口权限；
 * 2. 工具适配器再次校验租户、项目、工作空间和数据范围；
 * 3. 高风险工具进入审批或人工确认流程；
 * 4. 审计系统记录“谁让 Agent 调用了什么工具、作用于哪个对象、结果如何”。
 *
 * @param toolCode 工具编码，例如 datasource.metadata.read、quality.rule.suggest。
 * @param toolType 工具类型，用于权限、审计、风险分级和运行计划分类。
 * @param displayName 管理后台展示名称。
 * @param targetService 未来实际调用的服务名，例如 datasource-management、data-quality、task-management。
 * @param targetResourceId 可选目标资源 ID，例如某个数据源 ID、质量规则 ID 或任务 ID。
 * @param readOnly 是否只读。只读工具仍可能接触敏感数据，因此不能等同于“无风险”。
 * @param allowedActions 允许动作列表，例如 VIEW、GENERATE、CREATE_TASK、RUN、EXPORT。
 */
public record BindAgentToolRequest(
        @NotBlank(message = "toolCode 不能为空")
        @Size(max = 128, message = "toolCode 最多 128 个字符")
        String toolCode,

        @NotNull(message = "toolType 不能为空")
        AgentToolType toolType,

        @Size(max = 128, message = "displayName 最多 128 个字符")
        String displayName,

        @Size(max = 128, message = "targetService 最多 128 个字符")
        String targetService,

        Long targetResourceId,

        Boolean readOnly,

        @Size(max = 20, message = "allowedActions 最多 20 个")
        List<@Size(max = 64, message = "allowedAction 最多 64 个字符") String> allowedActions) {
}
