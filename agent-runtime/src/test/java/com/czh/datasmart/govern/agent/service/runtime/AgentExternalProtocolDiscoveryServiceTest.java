/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentExternalProtocolDiscoveryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpListedToolView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResponse;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部协议发现服务测试。
 *
 * <p>这组测试覆盖 5.26 的两个关键发现入口：MCP `tools/list` 风格工具目录和 A2A public Agent Card。
 * 这些接口将来很可能被外部 Agent、IDE Agent Host、Python Runtime、网关缓存或安全扫描器直接读取，
 * 因此测试重点不是业务执行，而是协议形态和信息披露边界。</p>
 *
 * <p>特别要保护的边界：
 * 1. 工具发现只能返回 schema，不能返回工具参数 example 或真实参数值；
 * 2. 公开 Agent Card 只能暴露 READY Skill，不能把禁用或治理不完整的能力发布出去；
 * 3. 响应序列化后不能包含内部 endpoint、内部域名、targetService、SQL/高风险查询样例、api key 或 secret。</p>
 */
class AgentExternalProtocolDiscoveryServiceTest {

    private AgentExternalProtocolDiscoveryService service;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        runtimeProperties.setToolRegistry(new LinkedHashMap<>());
        runtimeProperties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        runtimeProperties.getToolRegistry().put("quality.rule.suggest", qualityRuleTool());
        runtimeProperties.getToolRegistry().put("task.create.draft", taskDraftTool());

        AgentSkillRegistryProperties skillProperties = new AgentSkillRegistryProperties();
        skillProperties.setSkillRegistry(new LinkedHashMap<>());
        skillProperties.getSkillRegistry().put("quality.rule.design", qualityRuleSkill());
        skillProperties.getSkillRegistry().put("quality.unsafe.direct-fix", nonReadySkill());
        skillProperties.getSkillRegistry().put("disabled.skill", disabledSkill());

        AgentSkillRegistryService skillRegistryService =
                new AgentSkillRegistryService(runtimeProperties, skillProperties);
        service = new AgentExternalProtocolDiscoveryService(
                new AgentToolRegistryService(runtimeProperties),
                new AgentSkillPublicationManifestService(skillRegistryService),
                skillRegistryService
        );
    }

    /**
     * 验证 MCP tools/list 风格响应只暴露低敏工具 schema。
     *
     * <p>这里会检查 datasourceId 仍然以字段形式出现在 schema 中，但不会出现 example 中的真实 ID。
     * 这是协议发现和真实调用之间非常重要的差异：发现阶段可以告诉 Agent“需要什么字段”，但不能把任何一次
     * 执行上下文、内部填充值或客户数据泄露给外部 Agent。</p>
     */
    @Test
    void listMcpToolsShouldExposeSchemaWithoutExecutionDetails() throws Exception {
        AgentMcpToolsListResponse response = service.listMcpTools(null, null, null, 10, "req-001");

        assertEquals("2.0", response.jsonrpc());
        assertEquals("req-001", response.id());
        assertEquals("tools/list", response.method());
        assertTrue(response.previewOnly());
        assertFalse(response.callEnabled());
        assertEquals(3, response.result().totalCount());
        assertEquals(3, response.result().returnedCount());
        assertNull(response.result().nextCursor());

        AgentMcpListedToolView datasourceTool = response.result().tools().stream()
                .filter(tool -> tool.name().equals("datasource.metadata.read"))
                .findFirst()
                .orElseThrow();
        assertEquals("optional", datasourceTool.execution().get("taskSupport"));
        assertTrue((Boolean) datasourceTool.annotations().get("readOnlyHint"));
        assertEquals("LOW", datasourceTool.annotations().get("datasmart.riskLevel"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) datasourceTool.inputSchema().get("properties");
        assertTrue(properties.containsKey("datasourceId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> datasourceId = (Map<String, Object>) properties.get("datasourceId");
        assertEquals("number", datasourceId.get("type"));
        assertEquals(true, datasourceId.get("x-datasmart-sensitive"));

        String serialized = objectMapper().writeValueAsString(response);
        String lowerSerialized = serialized.toLowerCase(Locale.ROOT);
        assertFalse(lowerSerialized.contains("internal.example.com"));
        assertFalse(lowerSerialized.contains("targetendpoint"));
        assertFalse(lowerSerialized.contains("targetservice"));
        assertFalse(lowerSerialized.contains("toolarguments"));
        assertFalse(lowerSerialized.contains("drop table"));
        assertFalse(lowerSerialized.contains("api-key-for-test"));
        assertFalse(lowerSerialized.contains("secret-for-test"));
        assertFalse(lowerSerialized.contains("\"datasourceid\":1001"));
    }

    /**
     * 验证 MCP tools/list 分页与审批工具 taskSupport。
     *
     * <p>真实客户环境中的工具目录可能逐步扩展到几十或上百个工具，发现接口必须从一开始就支持分页。
     * 同时，高风险和审批型工具必须明确 `taskSupport=required`，避免外部 Agent 把它们当成同步函数直接调用。</p>
     */
    @Test
    void listMcpToolsShouldSupportCursorPaginationAndTaskSupport() {
        AgentMcpToolsListResponse firstPage = service.listMcpTools(null, null, null, 2, "page-1");
        AgentMcpToolsListResponse secondPage = service.listMcpTools(null, null, firstPage.result().nextCursor(), 2, "page-2");

        assertEquals(2, firstPage.result().returnedCount());
        assertEquals("offset:2", firstPage.result().nextCursor());
        assertEquals(1, secondPage.result().returnedCount());
        assertNull(secondPage.result().nextCursor());

        AgentMcpListedToolView taskTool = secondPage.result().tools().stream()
                .filter(tool -> tool.name().equals("task.create.draft"))
                .findFirst()
                .orElseThrow();
        assertEquals("required", taskTool.execution().get("taskSupport"));
        assertEquals(true, taskTool.annotations().get("datasmart.requiresApproval"));
        assertEquals(false, taskTool.annotations().get("readOnlyHint"));
    }

    /**
     * 验证 A2A public Agent Card 只暴露 READY Skill。
     *
     * <p>公开 Agent Card 是外部 Agent 的自动发现入口，必须比管理端 preview 更保守。
     * 如果把非 READY Skill 放进去，外部 Agent 可能自动委派一个缺少审批、审计或隔离策略的任务，
     * 这在商业化客户环境中会直接变成权限与合规风险。</p>
     */
    @Test
    void buildA2aPublicAgentCardShouldExposeOnlyReadySkills() throws Exception {
        AgentA2aPublicAgentCardView card = service.buildA2aPublicAgentCard(null, null);

        assertEquals("DataSmart Govern Master Agent", card.name());
        assertEquals("5.26-preview", card.version());
        assertEquals("HTTP+JSON", card.supportedInterfaces().getFirst().protocolBinding());
        assertEquals("1.0", card.supportedInterfaces().getFirst().protocolVersion());
        assertFalse(card.capabilities().streaming());
        assertFalse(card.capabilities().pushNotifications());
        assertTrue(card.capabilities().extendedAgentCard());
        assertTrue(card.securitySchemes().containsKey("bearerAuth"));
        assertTrue(card.signatures().isEmpty());
        assertEquals(1, card.skills().size());
        assertEquals("quality.rule.design", card.skills().getFirst().id());
        assertNotNull(card.skills().getFirst().securityRequirements());

        String serialized = objectMapper().writeValueAsString(card);
        String lowerSerialized = serialized.toLowerCase(Locale.ROOT);
        assertFalse(lowerSerialized.contains("quality.unsafe.direct-fix"));
        assertFalse(lowerSerialized.contains("disabled.skill"));
        assertFalse(lowerSerialized.contains("internal.example.com"));
        assertFalse(lowerSerialized.contains("targetendpoint"));
        assertFalse(lowerSerialized.contains("api-key"));
        assertFalse(lowerSerialized.contains("secret-for-test"));
        assertFalse(lowerSerialized.contains("\"datasourceid\":1001"));
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setDisplayName("数据源元数据读取");
        tool.setDescription("读取数据源元数据，内部地址 https://internal.example.com，不允许执行 DROP TABLE 或使用 api-key-for-test。");
        tool.setTargetService("datasource-management");
        tool.setTargetEndpoint("/internal/datasources/{datasourceId}/metadata?secret=secret-for-test");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.SYNC);
        tool.setAllowedActions(List.of("VIEW"));
        tool.setInputSchema(List.of(inputField("datasourceId", "number", true, true, "CAN_FILL_FROM_CONTEXT")));
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties qualityRuleTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("quality.rule.suggest");
        tool.setToolType(AgentToolType.DATA_QUALITY);
        tool.setDisplayName("数据质量规则建议");
        tool.setDescription("基于字段语义生成质量规则草稿。");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.MEDIUM);
        tool.setExecutionMode(AgentToolExecutionMode.DRAFT_ONLY);
        tool.setAllowedActions(List.of("GENERATE"));
        tool.setInputSchema(List.of(inputField("businessGoal", "string", true, false, "USER_REQUIRED")));
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
        field.setDescription("字段说明中也可能包含 https://internal.example.com，应在发现接口中脱敏。");
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
        skill.setRequiredTools(List.of("datasource.metadata.read", "quality.rule.suggest"));
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
