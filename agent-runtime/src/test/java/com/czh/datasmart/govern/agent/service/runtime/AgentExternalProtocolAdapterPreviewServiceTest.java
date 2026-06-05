/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolAdapterPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentExternalProtocolAdapterPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolPreviewView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.service.AgentSkillPublicationManifestService;
import com.czh.datasmart.govern.agent.service.AgentSkillRegistryService;
import com.czh.datasmart.govern.agent.service.AgentToolRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP/A2A 外部协议适配预览服务测试。
 *
 * <p>这组测试保护的是“协议投影层”的边界，而不是某个具体工具或 Skill 的业务执行。
 * 由于 MCP/A2A 后续会成为外部 Agent 接入 DataSmart 的入口，如果 preview 阶段就把内部 endpoint、
 * 工具实参、资源正文、prompt 正文或密钥类信息暴露出去，后续真实协议服务很容易继承错误契约。
 * 因此测试重点放在三件事上：
 * 1. MCP tools/resources/prompts 与 A2A Agent Card 都能从现有控制面事实源生成；
 * 2. A2A 只暴露 READY Skill，非 READY 能力只能出现在管理诊断建议里；
 * 3. 响应序列化后不包含内部 endpoint、工具参数值、查询语句样例或凭据类字符串。</p>
 */
class AgentExternalProtocolAdapterPreviewServiceTest {

    private AgentExternalProtocolAdapterPreviewService service;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        runtimeProperties.setToolRegistry(new LinkedHashMap<>());
        runtimeProperties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        runtimeProperties.getToolRegistry().put("task.create.draft", taskDraftTool());

        AgentSkillRegistryProperties skillProperties = new AgentSkillRegistryProperties();
        skillProperties.setSkillRegistry(new LinkedHashMap<>());
        skillProperties.getSkillRegistry().put("quality.rule.design", qualityRuleSkill());
        skillProperties.getSkillRegistry().put("quality.unsafe.direct-fix", nonReadySkill());
        skillProperties.getSkillRegistry().put("disabled.skill", disabledSkill());

        AgentSkillRegistryService skillRegistryService =
                new AgentSkillRegistryService(runtimeProperties, skillProperties);
        AgentSkillPublicationManifestService manifestService =
                new AgentSkillPublicationManifestService(skillRegistryService);
        AgentToolRegistryService toolRegistryService = new AgentToolRegistryService(runtimeProperties);
        service = new AgentExternalProtocolAdapterPreviewService(
                manifestService,
                skillRegistryService,
                toolRegistryService
        );
    }

    /**
     * 验证默认预览能生成 MCP 与 A2A 两侧的低敏目录。
     *
     * <p>默认 includeNonReady=false 时，禁用 Skill 不会进入 Manifest；但启用却治理不完整的 Skill
     * 仍会进入 Manifest 并标记为非 READY。A2A Agent Card 更严格，只允许 READY Skill 出现在外部名片中。
     * 这能避免外部 Agent 发现一个“存在但不能安全委派”的能力。</p>
     */
    @Test
    void buildPreviewShouldExposeMcpAndA2aMetadataOnly() throws Exception {
        AgentExternalProtocolAdapterPreviewResponse response = service.buildPreview(null, null, false);

        assertEquals("datasmart.agent-runtime.external-protocol-adapter-preview.v1", response.schemaVersion());
        assertTrue(response.policy().previewOnly());
        assertFalse(response.policy().executionEnabled());
        assertEquals("2025-11-25", response.mcp().protocolVersion());
        assertEquals("1.0.0", response.a2a().protocolVersion());
        assertEquals(2, response.sourceToolCount());
        assertEquals(2, response.sourceSkillCount());
        assertEquals(2, response.mcp().toolCount());
        assertEquals(1, response.a2a().skillCount());
        assertEquals("quality.rule.design", response.a2a().skills().getFirst().skillId());
        assertTrue(response.referenceUrls().stream().anyMatch(url -> url.contains("modelcontextprotocol.io")));
        assertTrue(response.referenceUrls().stream().anyMatch(url -> url.contains("a2a-protocol.org")));

        String serialized = objectMapper().writeValueAsString(response);
        String lowerSerialized = serialized.toLowerCase(Locale.ROOT);
        assertFalse(serialized.contains("targetEndpoint"));
        assertFalse(serialized.contains("toolArguments"));
        assertFalse(serialized.contains("selectedNodeOutboxRequestTemplate"));
        assertFalse(lowerSerialized.contains("https://internal.example.com"));
        assertFalse(lowerSerialized.contains("drop table"));
        assertFalse(lowerSerialized.contains("api-key-for-test"));
        assertFalse(lowerSerialized.contains("\"datasourceid\":1001"));
    }

    /**
     * 验证非 READY Skill 不会进入 A2A Agent Card。
     *
     * <p>管理员可以通过 includeNonReady=true 查看禁用或治理不完整的 Skill，以便定位为什么某个能力没有暴露。
     * 但外部 Agent Card 不能因为诊断视图包含这些条目就把它们也发布出去。A2A 的发现机制通常会被其他 Agent
     * 用于自动委派任务，所以这里必须保持“诊断可见”和“对外可委派”分离。</p>
     */
    @Test
    void nonReadySkillsShouldStayOutOfA2aAgentCard() {
        AgentExternalProtocolAdapterPreviewResponse response = service.buildPreview(null, null, true);

        assertEquals(3, response.sourceSkillCount());
        assertEquals(1, response.a2a().skillCount());
        assertFalse(response.a2a().skills().stream()
                .anyMatch(skill -> skill.skillId().equals("quality.unsafe.direct-fix")));
        assertFalse(response.a2a().skills().stream()
                .anyMatch(skill -> skill.skillId().equals("disabled.skill")));
        assertTrue(response.recommendedNextSteps().stream()
                .anyMatch(step -> step.contains("非 READY Skill")));
    }

    /**
     * 验证需要审批的工具在 MCP 预览中被标记为 taskSupport=required。
     *
     * <p>高风险或审批型工具不能被外部 Agent 当作普通同步函数调用。把 taskSupport 标记为 required，
     * 是为了给后续真实 MCP Server 留出异步任务、审批、确认和状态回查的协议空间。</p>
     */
    @Test
    void approvalToolsShouldRequireTaskAugmentedExecution() {
        AgentExternalProtocolAdapterPreviewResponse response = service.buildPreview(null, null, false);

        AgentMcpToolPreviewView taskTool = response.mcp().tools().stream()
                .filter(tool -> tool.name().equals("task.create.draft"))
                .findFirst()
                .orElseThrow();
        assertEquals("required", taskTool.taskSupport());
        assertTrue(taskTool.requiresApproval());
        assertFalse(taskTool.readOnly());
        assertEquals("METADATA_ONLY_NO_TOOL_ARGUMENTS_NO_PROMPT_BODY_NO_RESOURCE_BODY_NO_MODEL_OUTPUT",
                taskTool.payloadPolicy());
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setDisplayName("数据源元数据读取");
        tool.setDescription("读取数据源元数据，内部地址 https://internal.example.com，不允许执行 DROP TABLE 或使用 api-key-for-test。");
        tool.setTargetService("datasource-management");
        tool.setTargetEndpoint("/internal/datasources/{datasourceId}/metadata?secret=api-key-for-test");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.SYNC);
        tool.setAllowedActions(List.of("VIEW"));
        tool.setInputSchema(List.of(inputField("datasourceId", "number", true, true, "can-fill-from-context")));
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties taskDraftTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("task.create.draft");
        tool.setToolType(AgentToolType.TASK_MANAGEMENT);
        tool.setDisplayName("任务草稿创建");
        tool.setDescription("把 Agent 规划结果转成受控任务草稿。");
        tool.setReadOnly(false);
        tool.setRiskLevel(AgentToolRiskLevel.HIGH);
        tool.setExecutionMode(AgentToolExecutionMode.APPROVAL_REQUIRED);
        tool.setRequiresApproval(true);
        tool.setAllowedActions(List.of("CREATE"));
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
        field.setSensitive(sensitive);
        field.setResolution(resolution);
        field.setExample("{\"datasourceId\":1001}");
        return field;
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties qualityRuleSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("quality.rule.design");
        skill.setDisplayName("质量规则设计 Skill");
        skill.setDescription("根据元数据和历史异常生成质量规则草稿。");
        skill.setDomain("data-quality");
        skill.setRequiredTools(List.of("datasource.metadata.read"));
        skill.setRequiredPermissions(List.of("quality:rule:draft"));
        skill.setMemoryDependencies(List.of("semantic", "episodic"));
        skill.setRiskLevel("medium");
        skill.setApprovalPolicy("draft-review");
        skill.setTriggerKeywords(List.of("质量", "规则"));
        skill.setExamples(List.of("为客户主数据生成质量规则草案"));
        return skill;
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties nonReadySkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("quality.unsafe.direct-fix");
        skill.setDisplayName("未完成治理的直接修复 Skill");
        skill.setDescription("用于验证缺少审计策略时不能进入 A2A Agent Card。");
        skill.setDomain("data-quality");
        skill.setRiskLevel("high");
        skill.setApprovalPolicy("none");
        skill.setAuditRequired(false);
        return skill;
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties disabledSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("disabled.skill");
        skill.setDisplayName("禁用 Skill");
        skill.setEnabled(false);
        return skill;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
