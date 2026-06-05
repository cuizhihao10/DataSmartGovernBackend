/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentExternalProtocolDiscoveryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentCapabilitiesView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentInterfaceView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentProviderView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicSkillView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpListedToolView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResultView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationManifestView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolParameterDescriptorView;
import com.czh.datasmart.govern.agent.service.AgentSkillPublicationManifestService;
import com.czh.datasmart.govern.agent.service.AgentSkillRegistryService;
import com.czh.datasmart.govern.agent.service.AgentToolRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 外部 Agent 协议发现服务。
 *
 * <p>5.25 已经实现了 MCP/A2A 的“总览预览”。本服务进一步提供两个更接近真实协议发现入口的只读能力：
 * MCP `tools/list` 风格工具目录，以及 A2A public Agent Card 风格能力名片。</p>
 *
 * <p>这里特意没有把逻辑继续写进 `AgentExternalProtocolAdapterPreviewService`：
 * - preview service 面向管理台和架构评审，适合一次性看 MCP、A2A、映射和产品建议；
 * - discovery service 面向外部 Agent、Python Runtime、网关或未来协议 server，返回更接近协议本身的数据。
 * 两者分开后，后续真实 MCP JSON-RPC handler、A2A task endpoint、Agent Registry 或签名发布流程都能单独演进，
 * 不会让一个“万能 Impl”不断膨胀。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentExternalProtocolDiscoveryService {

    private static final String MCP_TOOLS_LIST_SCHEMA_VERSION =
            "datasmart.agent-runtime.mcp-tools-list-discovery.v1";
    private static final String MCP_PROTOCOL_VERSION = "2025-11-25";
    private static final String A2A_AGENT_CARD_VERSION = "5.26-preview";
    private static final String A2A_PROTOCOL_VERSION = "1.0";
    private static final String PAYLOAD_POLICY =
            "METADATA_ONLY_NO_TOOL_ARGUMENTS_NO_PROMPT_BODY_NO_RESOURCE_BODY_NO_MODEL_OUTPUT";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Pattern MCP_TOOL_NAME_UNSAFE = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final Set<String> READY_PUBLICATION_STATES = Set.of("READY");

    private final AgentToolRegistryService toolRegistryService;
    private final AgentSkillPublicationManifestService publicationManifestService;
    private final AgentSkillRegistryService skillRegistryService;

    /**
     * 构建 MCP `tools/list` 只读发现响应。
     *
     * <p>业务输入说明：
     * - `domain`：可选，用于按工具业务类型做近似过滤。当前工具目录以 toolType 为主，没有完整业务域表；
     *   因此这里采用归一化匹配，兼容 `data-quality` 与 `DATA_QUALITY`；
     * - `riskLevel`：可选，只发现某一风险等级工具；
     * - `cursor`：可选分页游标，当前采用 `offset:N` 的轻量格式；
     * - `limit`：分页大小，服务端会限制在 1 到 100，避免外部 Agent 一次性拉取过大目录；
     * - `requestId`：模拟 JSON-RPC id，便于未来把该 REST facade 平滑迁移到真正 JSON-RPC。</p>
     *
     * <p>安全边界：
     * 该方法只输出工具“描述”和“输入 schema”，不会输出 `targetEndpoint`、`targetService`、example、
     * 某次调用参数、内部 URL、SQL 样例或任何凭证。即使工具 descriptor 中配置了这些内容，也会在这里被忽略或脱敏。</p>
     *
     * @return MCP tools/list 风格响应，但 `callEnabled=false`
     */
    public AgentMcpToolsListResponse listMcpTools(String domain,
                                                  String riskLevel,
                                                  String cursor,
                                                  Integer limit,
                                                  String requestId) {
        int pageSize = normalizeLimit(limit);
        int offset = parseCursor(cursor);

        List<AgentToolDescriptorView> filteredTools = toolRegistryService
                .listToolDescriptors(null, riskLevel, true)
                .stream()
                .filter(tool -> matchesDomain(domain, tool.toolType(), tool.toolCode()))
                .sorted(Comparator.comparing(AgentToolDescriptorView::toolCode))
                .toList();

        int fromIndex = Math.min(offset, filteredTools.size());
        int toIndex = Math.min(fromIndex + pageSize, filteredTools.size());
        List<AgentMcpListedToolView> pageTools = filteredTools.subList(fromIndex, toIndex)
                .stream()
                .map(this::toMcpListedTool)
                .toList();
        String nextCursor = toIndex < filteredTools.size() ? "offset:" + toIndex : null;

        return new AgentMcpToolsListResponse(
                MCP_TOOLS_LIST_SCHEMA_VERSION,
                Instant.now(),
                MCP_PROTOCOL_VERSION,
                "2.0",
                nullSafe(requestId, "datasmart-mcp-tools-list"),
                "tools/list",
                new AgentMcpToolsListResultView(
                        pageTools,
                        cursor == null || cursor.isBlank() ? null : cursor,
                        nextCursor,
                        pageSize,
                        pageTools.size(),
                        filteredTools.size()
                ),
                true,
                false,
                PAYLOAD_POLICY,
                List.of(
                        "当前接口只用于工具发现，不代表 tools/call 已启用。",
                        "响应不包含内部执行端点、下游服务名、参数示例、工具参数值、Prompt、资源正文或模型输出。",
                        "真实工具调用必须重新进入 permission-admin、tool preflight、confirmation/outbox、worker pre-check 和 runtime event。"
                ),
                List.of(
                        "下一步可实现 MCP initialize/capabilities，但仍保持 callEnabled=false。",
                        "开放 tools/call 前需要补齐协议调用 runtime event、限流、审批和幂等补偿。",
                        "若外部 Agent 高频发现工具，应增加目录指纹、ETag 或缓存失效通知。"
                )
        );
    }

    /**
     * 构建 A2A public Agent Card。
     *
     * <p>Public Agent Card 面向外部 Agent 自动发现，因此比内部 preview 更严格：
     * 只暴露 publicationState=READY 的 Skill；非 READY、禁用、缺审批策略、缺审计或缺隔离声明的 Skill
     * 不会出现在公开卡片里。管理员排查这些能力时应使用 5.25 的 `/mcp-a2a/preview` 诊断视图。</p>
     *
     * @param domain 可选业务域过滤；公开根路径通常不传，内部管理接口可用于验证某个域的卡片效果
     * @param riskLevel 可选风险等级过滤；真实生产通常应由权限策略控制，而不是由外部 Agent 自行传参
     * @return A2A Agent Card 风格只读响应
     */
    public AgentA2aPublicAgentCardView buildA2aPublicAgentCard(String domain, String riskLevel) {
        AgentSkillPublicationManifestView manifest = publicationManifestService.buildManifest(false, domain, riskLevel);
        Map<String, AgentSkillDescriptorView> descriptorByCode = skillRegistryService
                .listSkillDescriptors(domain, riskLevel, true)
                .stream()
                .collect(Collectors.toMap(
                        AgentSkillDescriptorView::skillCode,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<AgentA2aPublicSkillView> publicSkills = manifest.skills()
                .stream()
                .filter(item -> READY_PUBLICATION_STATES.contains(item.publicationState()))
                .map(item -> toA2aPublicSkill(item, descriptorByCode.get(item.skillCode())))
                .toList();

        return new AgentA2aPublicAgentCardView(
                "DataSmart Govern Master Agent",
                "企业级数据治理 Master Agent，负责把数据接入、质量规则、任务治理、权限边界、模型网关和工具执行编排成可审计工作流。",
                List.of(new AgentA2aAgentInterfaceView(
                        "https://datasmart-govern.example.com/api/agent/a2a/v1",
                        "HTTP+JSON",
                        null,
                        A2A_PROTOCOL_VERSION
                )),
                new AgentA2aAgentProviderView(
                        "https://datasmart-govern.example.com/docs/agent-runtime",
                        "DataSmart Govern"
                ),
                A2A_AGENT_CARD_VERSION,
                "https://datasmart-govern.example.com/docs/agent-runtime/a2a-agent-card",
                new AgentA2aAgentCapabilitiesView(
                        false,
                        false,
                        true,
                        List.of(orderedMap(Map.of(
                                "uri", "urn:datasmart:agent-card:governance:v1",
                                "description", "声明 DataSmart Agent Card 只暴露 READY Skill，真实调用需重新经过权限、审批、审计和工具沙箱。",
                                "required", false
                        )))
                ),
                buildSecuritySchemes(),
                List.of(Map.of("bearerAuth", List.of("agent-runtime:discover"))),
                List.of("text/plain", "application/json"),
                List.of("application/json", "text/markdown"),
                publicSkills,
                List.of(),
                null
        );
    }

    /**
     * 将内部工具 descriptor 转换为 MCP Tool。
     *
     * <p>此处最重要的是“schema 只描述结构，不携带值”。例如 datasourceId 是敏感字段时，
     * 我们仍然可以告诉外部 Agent 需要 `datasourceId` 这个字段，但不能给出真实 ID、样例 ID、内部填充路径或
     * 上下文解析结果。真实调用时，DataSmart 会在审批和权限校验后由系统注入或要求用户确认。</p>
     */
    private AgentMcpListedToolView toMcpListedTool(AgentToolDescriptorView descriptor) {
        return new AgentMcpListedToolView(
                toMcpToolName(descriptor.toolCode()),
                nullSafe(descriptor.displayName(), descriptor.toolCode()),
                safeText(descriptor.description()),
                buildInputSchema(descriptor.parameters()),
                buildMcpAnnotations(descriptor),
                Map.of("taskSupport", taskSupport(descriptor))
        );
    }

    private Map<String, Object> buildInputSchema(List<AgentToolParameterDescriptorView> parameters) {
        List<AgentToolParameterDescriptorView> safeParameters = parameters == null ? List.of() : parameters;
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (AgentToolParameterDescriptorView parameter : safeParameters) {
            if (parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", toJsonSchemaType(parameter.type()));
            property.put("description", parameterDescription(parameter));
            property.put("x-datasmart-resolution", nullSafe(parameter.resolution(), "USER_REQUIRED"));
            property.put("x-datasmart-sensitive", Boolean.TRUE.equals(parameter.sensitive()));
            properties.put(parameter.name(), property);
            if (Boolean.TRUE.equals(parameter.required())) {
                required.add(parameter.name());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private String parameterDescription(AgentToolParameterDescriptorView parameter) {
        if (Boolean.TRUE.equals(parameter.sensitive())) {
            return "敏感参数：只描述字段结构，真实值必须由 DataSmart 在权限校验、审批或上下文注入后处理。";
        }
        String description = safeText(parameter.description());
        return description.isBlank()
                ? "工具输入参数。真实执行前仍需 DataSmart 进行权限、审批、限流和参数校验。"
                : description;
    }

    private Map<String, Object> buildMcpAnnotations(AgentToolDescriptorView descriptor) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        boolean readOnly = Boolean.TRUE.equals(descriptor.governance().readOnly());
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", !readOnly);
        annotations.put("idempotentHint", Boolean.TRUE.equals(descriptor.invocation().idempotent()));
        annotations.put("openWorldHint", false);
        annotations.put("datasmart.riskLevel", descriptor.governance().riskLevel());
        annotations.put("datasmart.requiresApproval", Boolean.TRUE.equals(descriptor.governance().requiresApproval()));
        annotations.put("datasmart.tenantScoped", Boolean.TRUE.equals(descriptor.governance().tenantScoped()));
        annotations.put("datasmart.projectScoped", Boolean.TRUE.equals(descriptor.governance().projectScoped()));
        annotations.put("datasmart.allowedActions", descriptor.governance().allowedActions() == null
                ? List.of()
                : descriptor.governance().allowedActions());
        annotations.put("datasmart.payloadPolicy", PAYLOAD_POLICY);
        return annotations;
    }

    /**
     * 根据 DataSmart 工具执行模式映射 MCP taskSupport。
     *
     * <p>审批型、高风险异步型和草稿型工具都应标记为 required，表示未来真实调用需要任务生命周期支撑；
     * 只读同步工具可以 optional，但仍不代表本接口允许直接调用。</p>
     */
    private String taskSupport(AgentToolDescriptorView descriptor) {
        String executionMode = descriptor.invocation().executionMode();
        if (Boolean.TRUE.equals(descriptor.governance().requiresApproval())
                || "APPROVAL_REQUIRED".equals(executionMode)
                || "ASYNC_TASK".equals(executionMode)
                || "DRAFT_ONLY".equals(executionMode)) {
            return "required";
        }
        return "optional";
    }

    private AgentA2aPublicSkillView toA2aPublicSkill(AgentSkillPublicationItemView item,
                                                     AgentSkillDescriptorView descriptor) {
        List<String> permissions = item.requiredPermissions() == null ? List.of() : item.requiredPermissions();
        return new AgentA2aPublicSkillView(
                item.skillCode(),
                nullSafe(item.displayName(), item.skillCode()),
                descriptor == null ? "DataSmart 已发布治理能力。" : safeText(descriptor.description()),
                buildA2aTags(item, descriptor),
                descriptor == null || descriptor.examples() == null ? List.of() : descriptor.examples().stream()
                        .map(this::safeText)
                        .filter(value -> !value.isBlank())
                        .limit(3)
                        .toList(),
                List.of("text/plain", "application/json"),
                List.of("application/json", "text/markdown"),
                List.of(Map.of("bearerAuth", permissions.isEmpty()
                        ? List.of("agent-runtime:task:submit")
                        : permissions))
        );
    }

    private List<String> buildA2aTags(AgentSkillPublicationItemView item, AgentSkillDescriptorView descriptor) {
        List<String> tags = new ArrayList<>();
        tags.add(nullSafe(item.domain(), "agent-runtime"));
        tags.add("datasmart-governance");
        tags.add("ready");
        if (descriptor != null && descriptor.triggerKeywords() != null) {
            descriptor.triggerKeywords().stream()
                    .map(this::safeText)
                    .filter(value -> !value.isBlank())
                    .limit(4)
                    .forEach(tags::add);
        }
        return tags.stream().distinct().toList();
    }

    private Map<String, Object> buildSecuritySchemes() {
        Map<String, Object> bearer = new LinkedHashMap<>();
        bearer.put("description", "通过 DataSmart Gateway 签发或校验的 Bearer JWT；卡片只声明认证方式，不包含 token。");
        bearer.put("scheme", "Bearer");
        bearer.put("bearerFormat", "JWT");

        Map<String, Object> securityScheme = new LinkedHashMap<>();
        securityScheme.put("httpAuthSecurityScheme", bearer);

        Map<String, Object> schemes = new LinkedHashMap<>();
        schemes.put("bearerAuth", securityScheme);
        return schemes;
    }

    private boolean matchesDomain(String domain, String toolType, String toolCode) {
        if (domain == null || domain.isBlank()) {
            return true;
        }
        String expected = normalizeForLooseMatch(domain);
        return normalizeForLooseMatch(toolType).contains(expected)
                || normalizeForLooseMatch(toolCode).contains(expected);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        String normalized = cursor.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("offset:")) {
            normalized = normalized.substring("offset:".length());
        }
        try {
            return Math.max(0, Integer.parseInt(normalized));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String toJsonSchemaType(String type) {
        if (type == null || type.isBlank()) {
            return "string";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "number", "decimal", "double", "float" -> "number";
            case "integer", "int", "long" -> "integer";
            case "boolean", "bool" -> "boolean";
            case "object", "map" -> "object";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    private String toMcpToolName(String toolCode) {
        String safeName = MCP_TOOL_NAME_UNSAFE.matcher(nullSafe(toolCode, "datasmart.tool")).replaceAll("_");
        if (safeName.length() <= 128) {
            return safeName;
        }
        return safeName.substring(0, 128);
    }

    /**
     * 低敏文本净化。
     *
     * <p>协议发现入口通常会被外部 Agent、调试工具和前端缓存读取，因此比内部管理接口更需要保守。
     * 当前方法会删除 URL、凭证提示和高风险查询样例；生产环境后续应替换为统一脱敏组件，并按租户策略配置。</p>
     */
    private String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value
                .replaceAll("(?i)https?://\\S+", "[external-url-redacted]")
                .replace("api-key", "[credential-redacted]")
                .replace("API_KEY", "[credential-redacted]")
                .replace("secret", "[secret-redacted]")
                .replace("DROP TABLE", "高风险查询动作")
                .replace("drop table", "高风险查询动作")
                .replace("SQL", "查询语句")
                .replace("sql", "查询语句");
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    private Map<String, Object> orderedMap(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private String normalizeForLooseMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String nullSafe(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? Objects.toString(fallback, "") : preferred;
    }
}
