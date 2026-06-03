/**
 * @Author : Cui
 * @Date: 2026/06/03 23:21
 * @Description DataSmart Govern Backend - AgentToolSandboxPolicyService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.sandbox;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 工具调用沙箱策略服务。
 *
 * <p>该服务是“模型计划工具”和“Java 真实执行工具”之间的控制面沙箱。
 * 它不调用下游服务，也不推进审计状态；只根据会话事实、Run 事实、工具审计事实和工具目录配置，
 * 生成一个可解释的 {@link AgentToolSandboxVerdict}。</p>
 *
 * <p>设计原则：</p>
 * <p>1. 策略集中：所有工具执行入口都可以复用这里的规则，避免每个适配器重复写安全逻辑；</p>
 * <p>2. 默认保守：一旦出现注册表漂移、目标服务伪造、参数体量异常、非幂等重试等问题，默认阻断；</p>
 * <p>3. 低敏可观测：返回 issueCodes 和中文 reasons，但不返回完整参数值，避免诊断接口成为敏感数据泄露点；</p>
 * <p>4. 可扩展：后续可以把容器隔离、SQL dry-run、HTTP egress policy、工具健康熔断、租户配额接入同一 verdict。</p>
 */
@Service
public class AgentToolSandboxPolicyService {

    private static final String SANDBOX_DISABLED = "SANDBOX_DISABLED";
    private static final String READ_ONLY_SYNC = "READ_ONLY_SYNC";
    private static final String APPROVAL_BOUND = "APPROVAL_BOUND";
    private static final String ASYNC_TASK_BOUND = "ASYNC_TASK_BOUND";
    private static final String DRAFT_ONLY_REVIEW = "DRAFT_ONLY_REVIEW";
    private static final String POLICY_ALLOWED = "POLICY_ALLOWED";
    private static final String BLOCKED = "BLOCKED";

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Spring 运行时构造函数。
     *
     * <p>复用容器中的 ObjectMapper，保证参数字节估算与项目其他 JSON 序列化配置保持一致。
     * 这里的序列化只用于估算体量和稳定测试，不会把参数 JSON 写入日志。</p>
     */
    @Autowired
    public AgentToolSandboxPolicyService(AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 单元测试便捷构造函数。
     *
     * <p>测试通常不启动 Spring 容器，因此允许直接传入 AgentRuntimeProperties。
     * 这里创建的 ObjectMapper 只服务沙箱本身，不会影响项目全局配置。</p>
     */
    public AgentToolSandboxPolicyService(AgentRuntimeProperties properties) {
        this(properties, new ObjectMapper());
    }

    /**
     * 执行工具调用沙箱预检。
     *
     * @param session 当前会话事实，提供租户、项目、工作空间和 actor 边界。
     * @param run 当前运行事实，提供 runId 和运行上下文。
     * @param audit 即将执行或被查询的工具审计事实。
     * @return 低敏、可解释、可被执行入口硬拦截的沙箱判定。
     */
    public AgentToolSandboxVerdict inspect(AgentSessionRecord session,
                                           AgentRunRecord run,
                                           AgentToolExecutionAuditRecord audit) {
        AgentRuntimeProperties.ToolSandboxProperties sandbox = sandboxProperties();
        AgentRuntimeProperties.ToolDefinitionProperties definition = findToolDefinition(audit.getToolCode());
        ArgumentSize argumentSize = estimateArgumentBytes(audit.getPlanArguments());
        List<String> issueCodes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        if (!Boolean.TRUE.equals(sandbox.getEnabled())) {
            reasons.add("工具调用沙箱当前已关闭，本次只依赖基础执行守卫；生产环境不建议关闭该能力。");
            actions.add("如进入生产或客户试点，请开启 datasmart.agent-runtime.tool-sandbox.enabled 并配置目标服务范围。");
            return verdict(audit, sandbox, definition, argumentSize, true, SANDBOX_DISABLED, issueCodes, reasons, actions);
        }

        inspectControlPlane(session, run, audit, issueCodes, reasons, actions);
        inspectRegistry(audit, definition, sandbox, issueCodes, reasons, actions);
        inspectTargetService(audit, sandbox, issueCodes, reasons, actions);
        inspectArguments(audit, definition, sandbox, argumentSize, issueCodes, reasons, actions);
        inspectExecutionRisk(audit, definition, sandbox, issueCodes, reasons, actions);

        boolean allowed = issueCodes.isEmpty();
        if (allowed) {
            reasons.add("工具调用沙箱预检通过：控制面边界、工具目录、目标服务、参数体量、审批和幂等重试规则均未发现阻断项。");
            actions.add("可以继续进入 AgentToolExecutionGuard 后续流程和具体工具适配器；适配器仍需执行下游资源权限与响应脱敏。");
        }
        return verdict(audit, sandbox, definition, argumentSize, allowed,
                allowed ? isolationMode(audit) : BLOCKED,
                issueCodes, reasons, actions);
    }

    /**
     * 执行前硬校验入口。
     *
     * <p>查询接口可以只调用 inspect(...) 展示 verdict；真实执行入口必须调用 requireAllowed(...)。
     * 这样可以避免“前端看到了沙箱阻断，但另一个入口仍绕过策略执行”的治理漏洞。</p>
     */
    public void requireAllowed(AgentSessionRecord session,
                               AgentRunRecord run,
                               AgentToolExecutionAuditRecord audit) {
        AgentToolSandboxVerdict verdict = inspect(session, run, audit);
        if (Boolean.TRUE.equals(verdict.allowed())) {
            return;
        }
        throw new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                "工具调用沙箱策略拒绝执行，toolCode=" + audit.getToolCode()
                        + "，issueCodes=" + verdict.issueCodes()
                        + "，reasons=" + verdict.reasons()
        );
    }

    private void inspectControlPlane(AgentSessionRecord session,
                                     AgentRunRecord run,
                                     AgentToolExecutionAuditRecord audit,
                                     List<String> issueCodes,
                                     List<String> reasons,
                                     List<String> actions) {
        if (!Objects.equals(session.getSessionId(), run.getSessionId())
                || !Objects.equals(session.getSessionId(), audit.getSessionId())
                || !Objects.equals(run.getRunId(), audit.getRunId())) {
            addIssue(issueCodes, reasons, actions,
                    "CONTROL_PLANE_MISMATCH",
                    "工具审计记录与当前 session/run 控制面链路不一致，可能是跨会话重放或调用方拼错 auditId。",
                    "拒绝执行，并要求调用方重新读取当前 Run 的工具审计列表。");
        }
        if (!Objects.equals(session.getTenantId(), audit.getTenantId())
                || !Objects.equals(session.getProjectId(), audit.getProjectId())
                || !Objects.equals(session.getWorkspaceId(), audit.getWorkspaceId())
                || !Objects.equals(session.getActorId(), audit.getActorId())) {
            addIssue(issueCodes, reasons, actions,
                    "BUSINESS_BOUNDARY_MISMATCH",
                    "工具审计记录的租户、项目、工作空间或 actor 与会话边界不一致，存在跨边界执行风险。",
                    "拒绝执行，并检查 AgentPlan ingestion、审批页和 execute 调用是否透传了错误上下文。");
        }
    }

    private void inspectRegistry(AgentToolExecutionAuditRecord audit,
                                 AgentRuntimeProperties.ToolDefinitionProperties definition,
                                 AgentRuntimeProperties.ToolSandboxProperties sandbox,
                                 List<String> issueCodes,
                                 List<String> reasons,
                                 List<String> actions) {
        if (definition == null) {
            if (Boolean.TRUE.equals(sandbox.getRequireRegisteredTool())) {
                addIssue(issueCodes, reasons, actions,
                        "TOOL_NOT_REGISTERED",
                        "工具未命中 Agent 工具目录，不能确认 targetService、riskLevel、approval 和 inputSchema 是否可信。",
                        "将工具补充到 datasmart.agent-runtime.tool-registry，或仅在本地实验环境关闭 require-registered-tool。");
            }
            return;
        }
        if (!Boolean.TRUE.equals(definition.getEnabled())) {
            addIssue(issueCodes, reasons, actions,
                    "TOOL_DISABLED",
                    "工具目录中该工具已被禁用，可能处于维护、灰度下线或客户环境裁剪状态。",
                    "不要执行该工具；如确需恢复，先由管理员更新工具目录并完成风险复核。");
        }
        if (!Objects.equals(trim(definition.getTargetService()), trim(audit.getTargetService()))) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_MISMATCH",
                    "工具审计记录中的 targetService 与工具目录不一致，可能表示工具计划被篡改或目录版本漂移。",
                    "拒绝执行，并以工具目录为事实源重新生成工具审计记录。");
        }
        if (!Objects.equals(trim(definition.getTargetEndpoint()), trim(audit.getTargetEndpoint()))) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_ENDPOINT_MISMATCH",
                    "工具审计记录中的 targetEndpoint 与工具目录不一致，下游路径可能被调用方伪造。",
                    "拒绝执行，并检查 AgentPlan ingestion 是否正确继承了工具目录端点。");
        }
        if (definition.getReadOnly() != null && !Objects.equals(definition.getReadOnly(), audit.getReadOnly())) {
            addIssue(issueCodes, reasons, actions,
                    "READ_ONLY_FLAG_MISMATCH",
                    "工具审计记录的 readOnly 标记与工具目录不一致，可能把写工具伪装成只读工具。",
                    "拒绝执行，并重新从工具目录继承 readOnly、riskLevel 和 approval 配置。");
        }
        if (riskDowngraded(definition.getRiskLevel(), audit.getRiskLevel())) {
            addIssue(issueCodes, reasons, actions,
                    "RISK_LEVEL_DOWNGRADE",
                    "工具审计记录中的风险等级低于工具目录声明，存在绕过审批或自动执行策略的风险。",
                    "拒绝执行，并以工具目录风险等级重新生成审计计划。");
        }
    }

    private void inspectTargetService(AgentToolExecutionAuditRecord audit,
                                      AgentRuntimeProperties.ToolSandboxProperties sandbox,
                                      List<String> issueCodes,
                                      List<String> reasons,
                                      List<String> actions) {
        String targetService = trim(audit.getTargetService());
        if (targetService == null || targetService.isBlank()) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_EMPTY",
                    "工具缺少 targetService，执行框架无法确认应该调用哪个受控业务模块。",
                    "补齐工具目录 target-service 后重新规划。");
            return;
        }
        if (containsIgnoreCase(sandbox.getBlockedTargetServices(), targetService)) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_BLOCKED",
                    "目标服务已被沙箱 blockedTargetServices 显式阻断，可能处于维护或事故止血状态。",
                    "不要继续执行；等待管理员解除阻断或改用替代工具。");
        }
        List<String> allowedTargetServices = sandbox.getAllowedTargetServices() == null
                ? List.of()
                : sandbox.getAllowedTargetServices();
        if (!allowedTargetServices.isEmpty() && !containsIgnoreCase(allowedTargetServices, targetService)) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_NOT_ALLOWED",
                    "目标服务不在沙箱 allowedTargetServices 白名单内，当前环境不允许 Agent 调用该服务。",
                    "由管理员确认该服务是否应开放给 Agent 工具调用，再更新白名单配置。");
        }
        if (Boolean.TRUE.equals(sandbox.getRequireKnownTargetService())
                && !properties.getToolServiceBaseUrls().containsKey(targetService)) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_BASE_URL_MISSING",
                    "目标服务未配置 toolServiceBaseUrls，执行器无法确认其受控内网地址或路由。",
                    "补充 datasmart.agent-runtime.tool-service-base-urls." + targetService + " 后再执行。");
        }
    }

    private void inspectArguments(AgentToolExecutionAuditRecord audit,
                                  AgentRuntimeProperties.ToolDefinitionProperties definition,
                                  AgentRuntimeProperties.ToolSandboxProperties sandbox,
                                  ArgumentSize argumentSize,
                                  List<String> issueCodes,
                                  List<String> reasons,
                                  List<String> actions) {
        if (argumentSize.serializationFailed()) {
            addIssue(issueCodes, reasons, actions,
                    "ARGUMENT_SERIALIZATION_FAILED",
                    "工具参数无法被稳定序列化，控制面不能确认参数体量和审计展示边界。",
                    "简化参数结构，避免传入不可序列化对象，再重新规划工具调用。");
        }
        if (argumentSize.bytes() > safeMaxArgumentBytes(sandbox)) {
            addIssue(issueCodes, reasons, actions,
                    "ARGUMENT_BYTES_EXCEED_LIMIT",
                    "工具参数体量超过沙箱上限，可能导致审计污染、网关内存压力或敏感上下文过量外传。",
                    "将大对象改为 MinIO/数据库资源引用，或拆分为异步任务后再提交。");
        }
        if (hasMissingParameters(audit.getParameterValidation())) {
            addIssue(issueCodes, reasons, actions,
                    "MISSING_PARAMETERS",
                    "参数校验结果仍包含 missingFields，当前工具调用输入不完整。",
                    "让用户补充缺失字段，或由 Agent 重新规划并从上下文、记忆、元数据中补齐。");
        }
        if (definition != null
                && Boolean.TRUE.equals(sandbox.getBlockSensitiveArgumentsWithoutApproval())
                && !hasApproval(audit)
                && containsSensitiveArgument(definition, audit.getPlanArguments())) {
            addIssue(issueCodes, reasons, actions,
                    "SENSITIVE_ARGUMENT_REQUIRES_APPROVAL",
                    "工具参数中包含目录标记的敏感字段，但当前审计记录没有人工审批事实。",
                    "先展示敏感参数摘要并完成人工确认，再允许继续执行。");
        }
    }

    private void inspectExecutionRisk(AgentToolExecutionAuditRecord audit,
                                      AgentRuntimeProperties.ToolDefinitionProperties definition,
                                      AgentRuntimeProperties.ToolSandboxProperties sandbox,
                                      List<String> issueCodes,
                                      List<String> reasons,
                                      List<String> actions) {
        AgentToolExecutionMode mode = parseExecutionMode(audit.getExecutionMode());
        Long timeoutMs = definition == null ? null : definition.getTimeoutMs();
        Integer maxRetries = definition == null ? null : definition.getMaxRetries();
        if (mode == AgentToolExecutionMode.SYNC && timeoutMs != null && timeoutMs > safeMaxSyncTimeoutMs(sandbox)) {
            addIssue(issueCodes, reasons, actions,
                    "SYNC_TIMEOUT_EXCEEDS_LIMIT",
                    "同步工具目录超时时间超过沙箱上限，继续同步执行会占用 HTTP 请求线程和下游连接池。",
                    "将该工具改为 ASYNC_TASK、Kafka command 或调低 timeout-ms 后再进入同步执行。");
        }
        if (Boolean.TRUE.equals(sandbox.getBlockNonIdempotentRetry())
                && definition != null
                && !Boolean.TRUE.equals(definition.getIdempotent())
                && maxRetries != null
                && maxRetries > 0) {
            addIssue(issueCodes, reasons, actions,
                    "NON_IDEMPOTENT_RETRY_BLOCKED",
                    "工具目录声明非幂等但配置了自动重试，重复调用可能造成重复任务或重复写入。",
                    "把 max-retries 调整为 0，或先实现业务幂等键、去重表和补偿流程。");
        }
        if (!Boolean.TRUE.equals(audit.getReadOnly()) && !hasApproval(audit)) {
            addIssue(issueCodes, reasons, actions,
                    "WRITE_TOOL_REQUIRES_APPROVAL",
                    "非只读工具没有人工审批人，不能由模型计划直接触发写操作。",
                    "由具备权限的用户审批后再执行，或改为 DRAFT_ONLY 草稿流程。");
        }
        if (isHighRisk(audit.getRiskLevel()) && !hasApproval(audit)) {
            addIssue(issueCodes, reasons, actions,
                    "HIGH_RISK_REQUIRES_APPROVAL",
                    "HIGH/CRITICAL 风险工具没有人工审批事实，不能自动或直接执行。",
                    "先进入人工确认/审批流程，并保留 operatorId 与审批说明。");
        }
        if (hasApprovalRequiredAction(audit, sandbox) && !hasApproval(audit)) {
            addIssue(issueCodes, reasons, actions,
                    "APPROVAL_REQUIRED_ACTION",
                    "工具 allowedActions 包含需要审批的高危动作关键词，当前缺少人工审批事实。",
                    "按审批流处理该工具，或把危险动作拆分成草稿生成与人工提交两个阶段。");
        }
    }

    private AgentToolSandboxVerdict verdict(AgentToolExecutionAuditRecord audit,
                                            AgentRuntimeProperties.ToolSandboxProperties sandbox,
                                            AgentRuntimeProperties.ToolDefinitionProperties definition,
                                            ArgumentSize argumentSize,
                                            boolean allowed,
                                            String isolationMode,
                                            List<String> issueCodes,
                                            List<String> reasons,
                                            List<String> actions) {
        return new AgentToolSandboxVerdict(
                audit.getAuditId(),
                audit.getToolCode(),
                Boolean.TRUE.equals(sandbox.getEnabled()),
                allowed,
                isolationMode,
                audit.getRiskLevel(),
                audit.getExecutionMode(),
                audit.getTargetService(),
                argumentSize.bytes(),
                safeMaxArgumentBytes(sandbox),
                definition == null ? null : definition.getTimeoutMs(),
                safeMaxSyncTimeoutMs(sandbox),
                definition == null ? null : definition.getMaxRetries(),
                List.copyOf(issueCodes),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    private String isolationMode(AgentToolExecutionAuditRecord audit) {
        AgentToolExecutionMode mode = parseExecutionMode(audit.getExecutionMode());
        if (hasApproval(audit)) {
            return APPROVAL_BOUND;
        }
        if (mode == AgentToolExecutionMode.ASYNC_TASK) {
            return ASYNC_TASK_BOUND;
        }
        if (mode == AgentToolExecutionMode.DRAFT_ONLY) {
            return DRAFT_ONLY_REVIEW;
        }
        if (mode == AgentToolExecutionMode.SYNC && Boolean.TRUE.equals(audit.getReadOnly())) {
            return READ_ONLY_SYNC;
        }
        return POLICY_ALLOWED;
    }

    private AgentRuntimeProperties.ToolSandboxProperties sandboxProperties() {
        if (properties.getToolSandbox() == null) {
            properties.setToolSandbox(new AgentRuntimeProperties.ToolSandboxProperties());
        }
        return properties.getToolSandbox();
    }

    private AgentRuntimeProperties.ToolDefinitionProperties findToolDefinition(String toolCode) {
        if (toolCode == null || properties.getToolRegistry() == null) {
            return null;
        }
        return properties.getToolRegistry().get(toolCode);
    }

    private ArgumentSize estimateArgumentBytes(Map<String, Object> arguments) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments)
                    .getBytes(StandardCharsets.UTF_8);
            return new ArgumentSize(bytes.length, false);
        } catch (JsonProcessingException exception) {
            return new ArgumentSize(Integer.MAX_VALUE, true);
        }
    }

    private boolean hasMissingParameters(Map<String, Object> parameterValidation) {
        if (parameterValidation == null || parameterValidation.isEmpty()) {
            return false;
        }
        Object missingFields = parameterValidation.get("missingFields");
        return missingFields instanceof Collection<?> collection && !collection.isEmpty();
    }

    private boolean containsSensitiveArgument(AgentRuntimeProperties.ToolDefinitionProperties definition,
                                              Map<String, Object> arguments) {
        if (definition.getInputSchema() == null || arguments == null || arguments.isEmpty()) {
            return false;
        }
        Set<String> argumentNames = normalizedSet(arguments.keySet());
        return definition.getInputSchema().stream()
                .filter(field -> Boolean.TRUE.equals(field.getSensitive()))
                .map(field -> normalize(field.getName()))
                .anyMatch(argumentNames::contains);
    }

    private boolean hasApprovalRequiredAction(AgentToolExecutionAuditRecord audit,
                                              AgentRuntimeProperties.ToolSandboxProperties sandbox) {
        if (audit.getAllowedActions() == null || audit.getAllowedActions().isEmpty()) {
            return false;
        }
        Set<String> requiredActions = normalizedSet(sandbox.getApprovalRequiredActions());
        return audit.getAllowedActions().stream()
                .map(this::normalize)
                .anyMatch(requiredActions::contains);
    }

    private boolean riskDowngraded(AgentToolRiskLevel definitionRisk, String auditRisk) {
        AgentToolRiskLevel normalizedAuditRisk = parseRiskLevel(auditRisk);
        if (definitionRisk == null || normalizedAuditRisk == null) {
            return false;
        }
        return normalizedAuditRisk.ordinal() < definitionRisk.ordinal();
    }

    private boolean isHighRisk(String riskLevel) {
        AgentToolRiskLevel risk = parseRiskLevel(riskLevel);
        return risk == AgentToolRiskLevel.HIGH || risk == AgentToolRiskLevel.CRITICAL;
    }

    private AgentToolRiskLevel parseRiskLevel(String riskLevel) {
        try {
            return AgentToolRiskLevel.valueOf(normalize(riskLevel));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private AgentToolExecutionMode parseExecutionMode(String executionMode) {
        try {
            return AgentToolExecutionMode.valueOf(normalize(executionMode));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean hasApproval(AgentToolExecutionAuditRecord audit) {
        return audit.getApprovalOperatorId() != null && !audit.getApprovalOperatorId().isBlank();
    }

    private int safeMaxArgumentBytes(AgentRuntimeProperties.ToolSandboxProperties sandbox) {
        return sandbox.getMaxArgumentBytes() == null || sandbox.getMaxArgumentBytes() <= 0
                ? 64 * 1024
                : sandbox.getMaxArgumentBytes();
    }

    private long safeMaxSyncTimeoutMs(AgentRuntimeProperties.ToolSandboxProperties sandbox) {
        return sandbox.getMaxSyncTimeoutMs() == null || sandbox.getMaxSyncTimeoutMs() <= 0
                ? 30000L
                : sandbox.getMaxSyncTimeoutMs();
    }

    private void addIssue(List<String> issueCodes,
                          List<String> reasons,
                          List<String> actions,
                          String code,
                          String reason,
                          String action) {
        issueCodes.add(code);
        reasons.add(reason);
        actions.add(action);
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null || values.isEmpty() || expected == null) {
            return false;
        }
        String normalizedExpected = normalize(expected);
        return values.stream().map(this::normalize).anyMatch(normalizedExpected::equals);
    }

    private Set<String> normalizedSet(Collection<String> values) {
        Set<String> result = new HashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(normalize(value));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private record ArgumentSize(Integer bytes, boolean serializationFailed) {
    }
}
