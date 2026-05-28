/**
 * @Author : Cui
 * @Date: 2026/05/13 23:27
 * @Description DataSmart Govern Backend - AgentToolRegistryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDefinitionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolGovernanceDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolInputFieldView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolInvocationDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolMemoryDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolParameterDescriptorView;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具注册服务。
 *
 * <p>该服务负责把配置文件中的 `tool-registry` 转换成稳定的工具目录视图。
 * 它是未来工具适配器、Agent 编排器、前端工具选择器和审计系统的共同入口。
 *
 * <p>当前为什么只做“注册目录”而不直接执行：
 * 1. 执行工具需要更完整的权限、审批、幂等和审计链路；
 * 2. datasource/data-quality/task-management 的工具适配器应分别设计输入输出契约，不能仓促拼 HTTP；
 * 3. 工具目录先稳定后，后续 Python Runtime 或 LangGraph 节点才能安全引用 toolCode。
 */
@Service
@RequiredArgsConstructor
public class AgentToolRegistryService {

    /**
     * DataSmart 自定义工具描述 schema 版本。
     *
     * <p>这里刻意不声明为完整 MCP 版本号。
     * 当前输出只是 MCP-style descriptor：字段组织方式更适合工具规划和跨运行时消费，
     * 但还没有实现 MCP 的完整 JSON-RPC、capability negotiation、resources/prompts 等协议细节。
     */
    private static final String TOOL_DESCRIPTOR_SCHEMA_VERSION = "datasmart.agent.tool.v1";

    private static final String TOOL_DESCRIPTOR_TYPE = "DATASMART_AGENT_TOOL";
    private static final String TOOL_DESCRIPTOR_PROTOCOL_HINT = "MCP_STYLE";

    private final AgentRuntimeProperties properties;

    /**
     * 查询工具目录。
     *
     * <p>支持按工具类型、风险等级和启用状态过滤。
     * 当前数据来自本地配置，列表规模较小，因此在内存中过滤即可；后续迁移到数据库后应下沉为分页查询。
     */
    public List<AgentToolDefinitionView> listTools(String toolType, String riskLevel, Boolean enabledOnly) {
        ensureRuntimeEnabled();
        return properties.getToolRegistry().entrySet().stream()
                .map(this::toToolView)
                .filter(item -> toolType == null || toolType.isBlank() || item.toolType().equals(normalize(toolType)))
                .filter(item -> riskLevel == null || riskLevel.isBlank() || item.riskLevel().equals(normalize(riskLevel)))
                .filter(item -> !Boolean.TRUE.equals(enabledOnly) || Boolean.TRUE.equals(item.enabled()))
                .sorted(Comparator.comparing(AgentToolDefinitionView::toolCode))
                .toList();
    }

    /**
     * 查询单个工具详情。
     *
     * <p>详情接口用于 Agent 绑定工具前的二次确认，也可用于前端展示工具说明、输入参数和风险提示。
     */
    public AgentToolDefinitionView getTool(String toolCode) {
        ensureRuntimeEnabled();
        return findTool(toolCode)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent 工具未注册，toolCode=" + toolCode));
    }

    /**
     * 查询工具描述符列表。
     *
     * <p>描述符面向 Python Runtime、智能网关、工具规划器和未来 MCP 适配层。
     * 它比普通工具目录多了治理策略、参数敏感性、参数解析方式、记忆写入策略和缓存策略。
     *
     * <p>为什么不直接让 Python Runtime 读取 application.yml：
     * 1. 配置文件是部署细节，不应该暴露给另一个运行时；
     * 2. Java Runtime 可以在这里统一补默认值、过滤禁用工具、隐藏内部字段；
     * 3. 后续迁移到数据库或权限中心时，调用方仍然消费同一 API。
     */
    public List<AgentToolDescriptorView> listToolDescriptors(String toolType,
                                                             String riskLevel,
                                                             Boolean enabledOnly) {
        ensureRuntimeEnabled();
        return properties.getToolRegistry().entrySet().stream()
                .map(this::toToolDescriptor)
                .filter(item -> toolType == null || toolType.isBlank() || item.toolType().equals(normalize(toolType)))
                .filter(item -> riskLevel == null || riskLevel.isBlank()
                        || item.governance().riskLevel().equals(normalize(riskLevel)))
                .filter(item -> !Boolean.TRUE.equals(enabledOnly) || Boolean.TRUE.equals(item.governance().enabled()))
                .sorted(Comparator.comparing(AgentToolDescriptorView::toolCode))
                .toList();
    }

    /**
     * 查询单个工具描述符。
     *
     * <p>当 Python Runtime 规划某个具体工具调用前，可以先拉取该描述符，
     * 用其中的 required/sensitive/resolution 规则判断参数是否齐全、是否需要审批、是否可以从上下文补齐。
     */
    public AgentToolDescriptorView getToolDescriptor(String toolCode) {
        ensureRuntimeEnabled();
        AgentRuntimeProperties.ToolDefinitionProperties tool = properties.getToolRegistry().get(toolCode);
        if (tool == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Agent 工具描述符不存在，toolCode=" + toolCode);
        }
        return toToolDescriptor(Map.entry(toolCode, tool));
    }

    /**
     * 查找工具定义。
     *
     * <p>返回 Optional 而不是直接抛异常，是为了让会话绑定、工具适配器和审计逻辑可以按自己的业务语义处理缺失工具。
     */
    public Optional<AgentToolDefinitionView> findTool(String toolCode) {
        AgentRuntimeProperties.ToolDefinitionProperties tool = properties.getToolRegistry().get(toolCode);
        if (tool == null) {
            return Optional.empty();
        }
        return Optional.of(toToolView(Map.entry(toolCode, tool)));
    }

    /**
     * 获取启用的工具定义。
     *
     * <p>会话绑定和真实执行阶段应使用该方法，而不是只检查工具是否存在。
     * 已禁用工具可能是因为维护窗口、下游故障、合规策略或客户环境裁剪，不能继续被 Agent 引用。
     */
    public AgentToolDefinitionView requireEnabledTool(String toolCode) {
        AgentToolDefinitionView tool = getTool(toolCode);
        if (!Boolean.TRUE.equals(tool.enabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 工具已禁用，toolCode=" + toolCode);
        }
        return tool;
    }

    /**
     * 判断工具是否存在且启用。
     *
     * <p>当前方法先提供给后续 AgentSessionService 或工具适配器复用。
     * 这一轮不强制会话绑定必须来自工具目录，是为了兼容研发期临时工具；后续进入执行阶段时应逐步收紧。
     */
    public boolean isEnabled(String toolCode) {
        AgentRuntimeProperties.ToolDefinitionProperties tool = properties.getToolRegistry().get(toolCode);
        return tool != null && Boolean.TRUE.equals(tool.getEnabled());
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    private AgentToolDefinitionView toToolView(Map.Entry<String, AgentRuntimeProperties.ToolDefinitionProperties> entry) {
        AgentRuntimeProperties.ToolDefinitionProperties tool = entry.getValue();
        String resolvedCode = tool.getToolCode() == null || tool.getToolCode().isBlank()
                ? entry.getKey()
                : tool.getToolCode();
        AgentToolType resolvedType = tool.getToolType() == null
                ? AgentToolType.KNOWLEDGE_RETRIEVAL
                : tool.getToolType();
        return new AgentToolDefinitionView(
                resolvedCode,
                tool.getEnabled(),
                resolvedType.name(),
                tool.getDisplayName(),
                tool.getDescription(),
                tool.getTargetService(),
                tool.getTargetEndpoint(),
                tool.getReadOnly(),
                tool.getRiskLevel() == null ? null : tool.getRiskLevel().name(),
                tool.getExecutionMode() == null ? null : tool.getExecutionMode().name(),
                tool.getRequiresApproval(),
                tool.getIdempotent(),
                tool.getTimeoutMs(),
                tool.getMaxRetries(),
                tool.getAllowedActions() == null ? List.of() : tool.getAllowedActions(),
                tool.getInputSchema() == null ? List.of() : tool.getInputSchema().stream()
                        .map(this::toInputFieldView)
                        .toList()
        );
    }

    private AgentToolInputFieldView toInputFieldView(AgentRuntimeProperties.ToolInputFieldProperties field) {
        return new AgentToolInputFieldView(
                field.getName(),
                field.getType(),
                field.getRequired(),
                field.getDescription(),
                field.getExample()
        );
    }

    private AgentToolDescriptorView toToolDescriptor(Map.Entry<String, AgentRuntimeProperties.ToolDefinitionProperties> entry) {
        AgentRuntimeProperties.ToolDefinitionProperties tool = entry.getValue();
        AgentToolDefinitionView definition = toToolView(entry);
        List<AgentToolParameterDescriptorView> parameters = tool.getInputSchema() == null
                ? List.of()
                : tool.getInputSchema().stream().map(this::toParameterDescriptor).toList();
        return new AgentToolDescriptorView(
                TOOL_DESCRIPTOR_SCHEMA_VERSION,
                TOOL_DESCRIPTOR_TYPE,
                TOOL_DESCRIPTOR_PROTOCOL_HINT,
                definition.toolCode(),
                definition.displayName(),
                definition.description(),
                definition.toolType(),
                new AgentToolInvocationDescriptorView(
                        definition.targetService(),
                        definition.targetEndpoint(),
                        definition.executionMode(),
                        definition.timeoutMs(),
                        definition.maxRetries(),
                        definition.idempotent()
                ),
                new AgentToolGovernanceDescriptorView(
                        definition.enabled(),
                        definition.readOnly(),
                        definition.riskLevel(),
                        definition.requiresApproval(),
                        tool.getTenantScoped(),
                        tool.getProjectScoped(),
                        definition.allowedActions(),
                        parameters.stream()
                                .filter(parameter -> Boolean.TRUE.equals(parameter.sensitive()))
                                .map(AgentToolParameterDescriptorView::name)
                                .toList()
                ),
                new AgentToolMemoryDescriptorView(
                        normalizeText(tool.getMemoryWritePolicy(), "NONE"),
                        normalizeText(tool.getCachePolicy(), "SESSION_ONLY")
                ),
                parameters
        );
    }

    private AgentToolParameterDescriptorView toParameterDescriptor(AgentRuntimeProperties.ToolInputFieldProperties field) {
        return new AgentToolParameterDescriptorView(
                field.getName(),
                normalizeText(field.getType(), "string"),
                Boolean.TRUE.equals(field.getRequired()),
                Boolean.TRUE.equals(field.getSensitive()),
                normalizeText(field.getResolution(), "USER_REQUIRED"),
                field.getDescription(),
                field.getExample()
        );
    }

    private String normalize(String value) {
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : normalize(value);
    }
}
