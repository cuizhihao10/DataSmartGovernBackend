/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolAdapterPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentCardPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aSkillPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentExternalProtocolAdapterPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentExternalProtocolAdapterPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpAdapterPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpPromptPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpResourcePreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolPreviewView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP/A2A 外部协议适配预览服务。
 *
 * <p>这个服务的职责是“把 DataSmart 内部能力目录投影成外部 Agent 协议草案”。它不启动协议服务器，
 * 不处理 JSON-RPC，不接收 A2A task，不执行工具，也不读取任何资源正文。这样的边界非常重要：
 * MCP 负责 Agent 到工具/资源/Prompt 的连接，A2A 负责 Agent 到 Agent 的发现与协作；但 DataSmart
 * 的真实商业化约束还包括租户隔离、项目授权、审批、审计、工具沙箱、runtime event 回放和模型网关治理。
 * 如果过早把外部协议直接连到执行层，后续很容易出现绕过权限和审计的“协议旁路”。</p>
 *
 * <p>本服务的输入来自三个已经存在的控制面事实源：
 * 1. Skill Publication Manifest：判断哪些 Skill 达到 READY，可进入外部 Agent Card；
 * 2. Skill Registry：补充 Skill 描述、示例、权限和记忆依赖；
 * 3. Tool Registry：补充 MCP Tool 元数据，但只输出低敏摘要，不输出内部 endpoint 和工具参数值。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentExternalProtocolAdapterPreviewService {

    private static final String PREVIEW_SCHEMA_VERSION =
            "datasmart.agent-runtime.external-protocol-adapter-preview.v1";
    private static final String MCP_PREVIEW_SCHEMA_VERSION =
            "datasmart.agent-runtime.mcp-adapter-preview.v1";
    private static final String MCP_PROTOCOL_VERSION = "2025-11-25";
    private static final String A2A_PROTOCOL_VERSION = "1.0.0";
    private static final String PAYLOAD_POLICY =
            "METADATA_ONLY_NO_TOOL_ARGUMENTS_NO_PROMPT_BODY_NO_RESOURCE_BODY_NO_MODEL_OUTPUT";
    private static final Set<String> READY_PUBLICATION_STATES = Set.of("READY");

    private final AgentSkillPublicationManifestService publicationManifestService;
    private final AgentSkillRegistryService skillRegistryService;
    private final AgentToolRegistryService toolRegistryService;

    /**
     * 构建 MCP/A2A 外部协议适配预览。
     *
     * <p>该方法面向管理台、架构评审、Python Runtime 启动诊断和后续协议适配开发。它支持按治理域、
     * 风险等级和是否包含非 READY 能力查看目录，但真实对外 Agent Card 仍只暴露 READY Skill。
     * 这样做可以同时满足两类场景：
     * - 运行时安全视角：外部 Agent 只能发现已满足审计、审批、隔离要求的能力；
     * - 管理诊断视角：管理员可以看到为什么某些 Skill 暂时不能暴露给外部协议。</p>
     *
     * @param domain 可选治理域过滤，例如 DATA_QUALITY、TASK_MANAGEMENT
     * @param riskLevel 可选风险等级过滤，例如 LOW、MEDIUM、HIGH
     * @param includeNonReady 是否在诊断视图中包含非 READY 或禁用 Skill；默认 false，避免外部协议误发现
     * @return 只读、低敏、不可执行的协议映射预览
     */
    public AgentExternalProtocolAdapterPreviewResponse buildPreview(String domain,
                                                                    String riskLevel,
                                                                    Boolean includeNonReady) {
        boolean shouldIncludeNonReady = Boolean.TRUE.equals(includeNonReady);
        AgentSkillPublicationManifestView manifest = publicationManifestService.buildManifest(
                shouldIncludeNonReady,
                domain,
                riskLevel
        );
        List<AgentSkillDescriptorView> skillDescriptors = skillRegistryService.listSkillDescriptors(
                domain,
                riskLevel,
                !shouldIncludeNonReady
        );
        Map<String, AgentSkillDescriptorView> descriptorByCode = skillDescriptors.stream()
                .collect(Collectors.toMap(
                        AgentSkillDescriptorView::skillCode,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<AgentToolDescriptorView> toolDescriptors = toolRegistryService.listToolDescriptors(null, null, true);

        AgentMcpAdapterPreviewView mcp = buildMcpPreview(toolDescriptors);
        AgentA2aAgentCardPreviewView a2a = buildA2aPreview(manifest.skills(), descriptorByCode);

        return new AgentExternalProtocolAdapterPreviewResponse(
                PREVIEW_SCHEMA_VERSION,
                Instant.now(),
                manifest.contentFingerprint(),
                manifest.skillCount(),
                toolDescriptors.size(),
                buildPolicy(),
                mcp,
                a2a,
                AgentExternalProtocolStaticPreviewCatalog.buildMappings(),
                AgentExternalProtocolStaticPreviewCatalog.protocolReferenceUrls(),
                AgentExternalProtocolStaticPreviewCatalog.productExpansionNotes(),
                recommendedNextSteps(shouldIncludeNonReady, manifest.skills(), toolDescriptors)
        );
    }

    /**
     * 构建 MCP 侧的 tools/resources/prompts 预览。
     *
     * <p>这里遵循“目录可见、正文不可见”的原则：tools 只输出低敏治理摘要，resources 只输出目录，
     * prompts 只输出模板名和参数名。真实 MCP Server 需要补齐 initialize/capability negotiation、
     * tools/list、tools/call、resources/list、resources/read、prompts/list、prompts/get 等协议处理，
     * 并且每一步都要回到 DataSmart 的权限与审计链路。</p>
     */
    private AgentMcpAdapterPreviewView buildMcpPreview(List<AgentToolDescriptorView> toolDescriptors) {
        List<AgentMcpToolPreviewView> tools = toolDescriptors.stream()
                .map(this::toMcpToolPreview)
                .toList();
        List<AgentMcpResourcePreviewView> resources =
                AgentExternalProtocolStaticPreviewCatalog.buildMcpResources();
        List<AgentMcpPromptPreviewView> prompts =
                AgentExternalProtocolStaticPreviewCatalog.buildMcpPrompts();

        return new AgentMcpAdapterPreviewView(
                MCP_PREVIEW_SCHEMA_VERSION,
                MCP_PROTOCOL_VERSION,
                "datasmart-govern-agent-runtime",
                orderedBooleans(Map.of(
                        "tools", true,
                        "resources", true,
                        "prompts", true,
                        "sampling", false,
                        "elicitation", false
                )),
                orderedStrings(Map.of(
                        "tools", "tools/list",
                        "resources", "resources/list",
                        "resourceTemplates", "resources/templates/list",
                        "prompts", "prompts/list"
                )),
                tools.size(),
                resources.size(),
                prompts.size(),
                tools,
                resources,
                prompts,
                List.of(
                        "当前是 MCP capability projection，不是完整 MCP JSON-RPC Server。",
                        "真实 tools/call 必须先进入 DataSmart tool preflight、审批、沙箱、审计和 runtime event 链路。",
                        "真实 resources/read 和 prompts/get 必须按租户、项目、角色、数据敏感级别做二次授权与脱敏。"
                )
        );
    }

    /**
     * 将内部工具 descriptor 转成 MCP Tool 低敏摘要。
     *
     * <p>这里刻意不输出 invocation.targetEndpoint、targetService、具体参数示例、工具参数值和下游 URL。
     * 外部协议目录只需要知道“有这个能力、风险是什么、是否需要审批、schema 应从哪里受控读取”，
     * 不应该知道内部微服务如何寻址，更不能看到某次执行的实际实参。</p>
     */
    private AgentMcpToolPreviewView toMcpToolPreview(AgentToolDescriptorView descriptor) {
        List<AgentToolParameterDescriptorView> parameters = descriptor.parameters() == null
                ? List.of()
                : descriptor.parameters();
        long requiredParameterCount = parameters.stream()
                .filter(parameter -> Boolean.TRUE.equals(parameter.required()))
                .count();
        long sensitiveParameterCount = parameters.stream()
                .filter(parameter -> Boolean.TRUE.equals(parameter.sensitive()))
                .count();

        return new AgentMcpToolPreviewView(
                descriptor.toolCode(),
                nullSafe(descriptor.displayName(), descriptor.toolCode()),
                safeText(descriptor.description()),
                "/agent-runtime/tools/" + descriptor.toolCode() + "/descriptor",
                Math.toIntExact(requiredParameterCount),
                Math.toIntExact(sensitiveParameterCount),
                taskSupport(descriptor),
                descriptor.governance().riskLevel(),
                descriptor.governance().requiresApproval(),
                descriptor.governance().readOnly(),
                descriptor.governance().tenantScoped(),
                descriptor.governance().projectScoped(),
                descriptor.governance().allowedActions() == null ? List.of() : descriptor.governance().allowedActions(),
                PAYLOAD_POLICY
        );
    }

    /**
     * 根据 DataSmart 工具执行模式给出 MCP taskSupport 草案。
     *
     * <p>MCP 最新规范中 tools 可以声明 task-augmented execution 倾向。DataSmart 当前尚未实现真实 MCP
     * task execution，因此这里只做语义提示：同步只读工具可以 optional，高风险审批工具必须 required，
     * 其他异步任务型工具也倾向 required，避免外部 Agent 把长任务误当作同步函数调用。</p>
     */
    private String taskSupport(AgentToolDescriptorView descriptor) {
        String executionMode = descriptor.invocation().executionMode();
        if (Boolean.TRUE.equals(descriptor.governance().requiresApproval())
                || "APPROVAL_REQUIRED".equals(executionMode)) {
            return "required";
        }
        if ("ASYNC_TASK".equals(executionMode) || "DRAFT_ONLY".equals(executionMode)) {
            return "required";
        }
        return "optional";
    }

    /**
     * 构建 A2A Agent Card 预览。
     *
     * <p>A2A Agent Card 的关键价值是让外部 Agent 发现 DataSmart Master Agent 能力，并决定是否委派任务。
     * 因此外部卡片必须比内部 Skill Registry 更严格：只有 READY Skill 才能进入卡片；非 READY Skill
     * 只应该在管理诊断视图中出现，不能让外部 Agent 误以为可以调用。</p>
     */
    private AgentA2aAgentCardPreviewView buildA2aPreview(List<AgentSkillPublicationItemView> publicationItems,
                                                         Map<String, AgentSkillDescriptorView> descriptorByCode) {
        List<AgentA2aSkillPreviewView> skills = publicationItems.stream()
                .filter(item -> READY_PUBLICATION_STATES.contains(item.publicationState()))
                .map(item -> toA2aSkillPreview(item, descriptorByCode.get(item.skillCode())))
                .toList();
        return new AgentA2aAgentCardPreviewView(
                A2A_PROTOCOL_VERSION,
                "/.well-known/agent-card.json",
                "DataSmart Govern Master Agent",
                "企业级数据治理 Master Agent，负责把数据接入、质量、任务、权限、模型网关和工具执行治理编排成可审计工作流。",
                "/agent-runtime/protocol-adapters/a2a/tasks",
                "HTTP+JSON",
                List.of("HTTP+JSON", "JSONRPC_PLANNED", "GRPC_PLANNED"),
                orderedBooleans(Map.of(
                        "streaming", false,
                        "pushNotifications", false,
                        "authenticatedExtendedCard", true,
                        "extensions", true
                )),
                List.of("text/plain", "application/json"),
                List.of("application/json", "text/markdown"),
                List.of(
                        "Bearer JWT through gateway",
                        "Tenant/project scoped permission-admin decision",
                        "Future signed Agent Card with key rotation"
                ),
                skills.size(),
                skills,
                "PUBLIC_CAPABILITY_ONLY_NO_INTERNAL_STATE_NO_MEMORY_BODY_NO_TOOL_ARGUMENTS",
                "生产环境应使用 JWS 签名 Agent Card，并把公钥、kid、过期时间和轮换策略纳入平台安全治理。"
        );
    }

    private AgentA2aSkillPreviewView toA2aSkillPreview(AgentSkillPublicationItemView publicationItem,
                                                       AgentSkillDescriptorView descriptor) {
        List<String> tags = descriptor == null
                ? List.of(publicationItem.domain())
                : buildSkillTags(descriptor);
        List<String> examples = descriptor == null ? List.of() : safeList(descriptor.examples()).stream()
                .map(this::safeText)
                .toList();
        String description = descriptor == null
                ? "DataSmart 已发布 Agent Skill。"
                : safeText(descriptor.description());
        return new AgentA2aSkillPreviewView(
                publicationItem.skillCode(),
                nullSafe(publicationItem.displayName(), publicationItem.skillCode()),
                description,
                tags,
                examples,
                publicationItem.requiredTools() == null ? 0 : publicationItem.requiredTools().size(),
                publicationItem.requiredPermissions() == null ? List.of() : publicationItem.requiredPermissions(),
                publicationItem.riskLevel(),
                publicationItem.approvalPolicy(),
                publicationItem.tenantScoped(),
                publicationItem.projectScoped(),
                List.of("submitted", "working", "input-required", "completed", "failed", "canceled"),
                PAYLOAD_POLICY
        );
    }

    private List<String> buildSkillTags(AgentSkillDescriptorView descriptor) {
        List<String> tags = new ArrayList<>();
        tags.add(descriptor.domain());
        tags.addAll(safeList(descriptor.triggerKeywords()).stream()
                .map(this::safeText)
                .filter(value -> !value.isBlank())
                .limit(5)
                .toList());
        return tags;
    }

    private AgentExternalProtocolAdapterPolicyView buildPolicy() {
        return new AgentExternalProtocolAdapterPolicyView(
                true,
                false,
                false,
                true,
                true,
                true,
                true,
                PAYLOAD_POLICY,
                "EVERY_REAL_PROTOCOL_CALL_MUST_WRITE_LOW_SENSITIVE_RUNTIME_EVENT_AND_TOOL_AUDIT",
                List.of(
                        "MCP initialize/capability negotiation",
                        "MCP tools/call execution",
                        "MCP resources/read body access",
                        "MCP prompts/get body access",
                        "A2A real task endpoint",
                        "A2A streaming and push notification",
                        "Signed public Agent Card endpoint"
                )
        );
    }

    private List<String> recommendedNextSteps(boolean includeNonReady,
                                              List<AgentSkillPublicationItemView> publicationItems,
                                              List<AgentToolDescriptorView> toolDescriptors) {
        long nonReadyCount = publicationItems.stream()
                .filter(item -> !READY_PUBLICATION_STATES.contains(item.publicationState()))
                .count();
        long approvalToolCount = toolDescriptors.stream()
                .filter(tool -> Boolean.TRUE.equals(tool.governance().requiresApproval()))
                .count();

        List<String> nextSteps = new ArrayList<>();
        nextSteps.add("优先实现 MCP tools/list 只读端点，并继续保持 schema 引用与工具执行分离。");
        nextSteps.add("实现 A2A public Agent Card 只读端点，但仅暴露 READY Skill 和低敏能力摘要。");
        nextSteps.add("为真实协议调用设计 runtime event 类型，确保外部 Agent 的发现、委派、取消和失败都可追踪。");
        if (includeNonReady && nonReadyCount > 0) {
            nextSteps.add("当前诊断视图包含非 READY Skill，应先补齐审批、审计、隔离或禁用原因，再考虑外部暴露。");
        }
        if (approvalToolCount > 0) {
            nextSteps.add("存在需要审批的工具，真实 MCP tools/call 必须接入 confirmation/outbox，不能同步直连执行。");
        }
        return nextSteps;
    }

    private Map<String, Boolean> orderedBooleans(Map<String, Boolean> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, String> orderedStrings(Map<String, String> source) {
        return new LinkedHashMap<>(source);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 低敏文本净化。
     *
     * <p>目录型接口不应该回传 URL、密钥提示、查询语句片段或过长文本。当前方法只做轻量替换，
     * 不是最终安全网；生产环境还需要统一脱敏组件、敏感词策略和基于字段语义的审计过滤。</p>
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

    private String nullSafe(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? Objects.toString(fallback, "") : preferred;
    }
}
