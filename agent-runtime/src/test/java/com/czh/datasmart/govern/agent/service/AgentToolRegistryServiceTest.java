/**
 * @Author : Cui
 * @Date: 2026/05/13 23:36
 * @Description DataSmart Govern Backend - AgentToolRegistryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDefinitionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDescriptorView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具注册服务测试。
 *
 * <p>这组测试保护工具目录的核心语义：
 * 1. 只返回启用工具；
 * 2. 可以按工具类型和风险等级过滤；
 * 3. 工具详情保留输入 schema、审批和执行模式；
 * 4. 未注册工具必须明确返回业务异常，避免 Agent 编排器误以为工具可用。
 */
class AgentToolRegistryServiceTest {

    private AgentToolRegistryService service;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setToolRegistry(new LinkedHashMap<>());
        properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        properties.getToolRegistry().put("task.create.draft", taskDraftTool());
        properties.getToolRegistry().put("disabled.tool", disabledTool());
        service = new AgentToolRegistryService(properties);
    }

    /**
     * 验证 enabledOnly 默认语义下只返回启用工具。
     */
    @Test
    void listToolsShouldOnlyReturnEnabledToolsWhenRequested() {
        List<AgentToolDefinitionView> tools = service.listTools(null, null, true);

        assertEquals(2, tools.size());
        assertFalse(tools.stream().anyMatch(item -> item.toolCode().equals("disabled.tool")));
    }

    /**
     * 验证工具类型和风险等级过滤。
     */
    @Test
    void listToolsShouldFilterByTypeAndRisk() {
        List<AgentToolDefinitionView> tools = service.listTools("task-management", "high", true);

        assertEquals(1, tools.size());
        assertEquals("task.create.draft", tools.getFirst().toolCode());
        assertEquals("APPROVAL_REQUIRED", tools.getFirst().executionMode());
        assertTrue(tools.getFirst().requiresApproval());
    }

    /**
     * 验证详情接口保留输入 schema。
     */
    @Test
    void getToolShouldExposeInputSchema() {
        AgentToolDefinitionView tool = service.getTool("datasource.metadata.read");

        assertEquals("DATASOURCE_METADATA", tool.toolType());
        assertEquals(1, tool.inputSchema().size());
        assertEquals("datasourceId", tool.inputSchema().getFirst().name());
    }

    /**
     * 验证工具描述符会输出面向 Python Runtime / 智能网关的治理契约。
     *
     * <p>这个测试保护的是“机器可消费”的新接口，而不是原先面向展示的目录接口。
     * 后续 Python Runtime 的 tool planner 可以依赖这些字段判断：
     * 1. 工具是否是 MCP-style 描述；
     * 2. 是否需要租户/项目范围；
     * 3. 哪些参数敏感；
     * 4. 参数能否从上下文补齐；
     * 5. 工具执行结果是否允许写入 Agent 记忆，以及缓存范围是什么。
     */
    @Test
    void getToolDescriptorShouldExposeGovernanceMemoryAndParameterPolicy() {
        AgentToolDescriptorView descriptor = service.getToolDescriptor("datasource.metadata.read");

        assertEquals("datasmart.agent.tool.v1", descriptor.schemaVersion());
        assertEquals("DATASMART_AGENT_TOOL", descriptor.descriptorType());
        assertEquals("MCP_STYLE", descriptor.protocolHint());
        assertEquals("datasource.metadata.read", descriptor.toolCode());
        assertEquals("datasource-management", descriptor.invocation().targetService());
        assertTrue(descriptor.governance().tenantScoped());
        assertTrue(descriptor.governance().projectScoped());
        assertEquals(List.of("datasourceId"), descriptor.governance().sensitiveFields());
        assertEquals("SEMANTIC", descriptor.memory().memoryWritePolicy());
        assertEquals("PROJECT_SAFE", descriptor.memory().cachePolicy());
        assertEquals("CAN_FILL_FROM_CONTEXT", descriptor.parameters().getFirst().resolution());
        assertTrue(descriptor.parameters().getFirst().sensitive());
    }

    /**
     * 验证描述符列表同样支持风险过滤和启用过滤。
     */
    @Test
    void listToolDescriptorsShouldFilterByRiskAndEnabledState() {
        List<AgentToolDescriptorView> descriptors = service.listToolDescriptors(null, "high", true);

        assertEquals(1, descriptors.size());
        assertEquals("task.create.draft", descriptors.getFirst().toolCode());
        assertTrue(descriptors.getFirst().governance().requiresApproval());
        assertEquals("SESSION_ONLY", descriptors.getFirst().memory().cachePolicy());
    }

    /**
     * 验证未注册工具会被明确拒绝。
     */
    @Test
    void getToolShouldRejectUnknownTool() {
        assertThrows(PlatformBusinessException.class, () -> service.getTool("unknown.tool"));
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setDisplayName("数据源元数据读取");
        tool.setTargetService("datasource-management");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.SYNC);
        tool.setAllowedActions(List.of("VIEW"));
        tool.setTenantScoped(true);
        tool.setProjectScoped(true);
        tool.setMemoryWritePolicy("semantic");
        tool.setCachePolicy("project-safe");
        tool.setInputSchema(List.of(inputField("datasourceId", "number", true, true, "can-fill-from-context")));
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties taskDraftTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("task.create.draft");
        tool.setToolType(AgentToolType.TASK_MANAGEMENT);
        tool.setDisplayName("任务草稿创建");
        tool.setReadOnly(false);
        tool.setRiskLevel(AgentToolRiskLevel.HIGH);
        tool.setExecutionMode(AgentToolExecutionMode.APPROVAL_REQUIRED);
        tool.setRequiresApproval(true);
        tool.setAllowedActions(List.of("CREATE"));
        tool.setMemoryWritePolicy("episodic");
        tool.setCachePolicy("session-only");
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties disabledTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("disabled.tool");
        tool.setToolType(AgentToolType.KNOWLEDGE_RETRIEVAL);
        tool.setEnabled(false);
        return tool;
    }

    private AgentRuntimeProperties.ToolInputFieldProperties inputField(String name,
                                                                       String type,
                                                                       boolean required,
                                                                       boolean sensitive,
                                                                       String resolution) {
        AgentRuntimeProperties.ToolInputFieldProperties field = new AgentRuntimeProperties.ToolInputFieldProperties();
        field.setName(name);
        field.setType(type);
        field.setRequired(required);
        field.setDescription("测试字段");
        field.setSensitive(sensitive);
        field.setResolution(resolution);
        return field;
    }
}
