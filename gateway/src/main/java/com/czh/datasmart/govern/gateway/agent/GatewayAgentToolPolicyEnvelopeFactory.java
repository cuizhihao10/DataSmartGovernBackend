/**
 * @Author : Cui
 * @Date: 2026/06/06 23:09
 * @Description DataSmart Govern Backend - GatewayAgentToolPolicyEnvelopeFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.agent;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 工具治理策略 envelope 工厂。
 *
 * <p>该类承担三件事：
 * 1. 从 gateway 已经清理和注入的 Header 中构造 permission-admin 评估请求；
 * 2. 在远程策略不可用或未启用时生成一份保守本地 fallback；
 * 3. 把远程/本地视图裁剪成 `X-DataSmart-Tool-Policy-Envelope` 可承载的低敏 JSON。</p>
 *
 * <p>为什么不把这些逻辑写在过滤器里：
 * - 过滤器应该只负责编排“清理 Header -> 评估策略 -> 写入 Header -> 继续转发”；
 * - 策略字段裁剪、本地 fallback 和 JSON 大小限制都是可独立测试的业务规则；
 * - 这样能避免 gateway 过滤器继续膨胀成大文件，也符合当前项目“解耦、单文件尽量小于 500 行”的规范。</p>
 */
@Component
@RequiredArgsConstructor
public class GatewayAgentToolPolicyEnvelopeFactory {

    private static final String LOCAL_POLICY_SOURCE = "GATEWAY_LOCAL_FALLBACK";
    private static final String READINESS_SOURCE = "gateway-local-fallback";

    /**
     * Jackson 序列化器。
     */
    private final ObjectMapper objectMapper;

    /**
     * 从 Header 构造 permission-admin 工具策略评估请求。
     *
     * <p>当前不读取 request body，原因是 Spring Cloud Gateway 的 body 读取会引入缓存、背压和大请求体风险。
     * 这意味着 projectId/requestedToolRiskLevel 只能先从 Header 或配置中得到近似值。
     * 真正需要精确工具风险时，应让 agent-runtime 或 ToolPlan 草案阶段生成摘要，而不是让 gateway 解析完整 prompt。</p>
     */
    public GatewayAgentToolPolicyEnvelopeRequest requestFromHeaders(
            HttpHeaders headers,
            GatewayContextProperties.ToolPolicyEnvelope properties) {
        GatewayAgentToolPolicyEnvelopeRequest request = new GatewayAgentToolPolicyEnvelopeRequest();
        request.setTenantId(longValue(headers.getFirst(PlatformContextHeaders.TENANT_ID)));
        request.setProjectId(firstAuthorizedProjectId(headers.getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)));
        request.setWorkspaceKey(headers.getFirst(PlatformContextHeaders.WORKSPACE_ID));
        request.setActorRole(normalizeCode(headers.getFirst(PlatformContextHeaders.ACTOR_ROLE), "ORDINARY_USER"));
        request.setTenantPlanCode(normalizeCode(headers.getFirst(PlatformContextHeaders.TENANT_PLAN_CODE), "STANDARD"));
        request.setWorkspaceRiskLevel(normalizeCode(
                headers.getFirst(PlatformContextHeaders.WORKSPACE_RISK_LEVEL),
                "NORMAL"
        ));
        request.setWorkerBacklogLevel(normalizeCode(properties.getDefaultWorkerBacklogLevel(), "NORMAL"));
        request.setRequestedToolRiskLevel(normalizeCode(properties.getDefaultRequestedToolRiskLevel(), "LOW"));
        return request;
    }

    /**
     * 构造本地保守 fallback。
     *
     * <p>fallback 的定位是“permission-admin 不可用时仍能维持安全边界”，不是替代正式策略中心。
     * 因此它只做少量保守收敛：只读角色阻断自动执行、FREE/TRIAL 收紧预算、高风险 workspace 要审批、
     * CRITICAL 风险或 CRITICAL backlog 阻断自动/异步推进。</p>
     */
    public GatewayAgentToolPolicyEnvelopeView localFallback(
            GatewayAgentToolPolicyEnvelopeRequest request,
            GatewayContextProperties.ToolPolicyEnvelope properties) {
        int maxProposed = positive(properties.getDefaultMaxProposedToolCalls(), 5);
        int maxAuto = nonNegative(properties.getDefaultMaxAutoExecutableToolCalls());
        int maxHighRisk = nonNegative(properties.getDefaultMaxHighRiskToolCalls());
        int maxSingleBytes = positive(properties.getDefaultMaxSingleArgumentsBytes(), 16_384);
        int maxTotalBytes = Math.max(maxSingleBytes, positive(properties.getDefaultMaxTotalArgumentsBytes(), 48_000));
        int maxAsync = Math.min(2, maxAuto);
        List<String> influenceCodes = new ArrayList<>();

        String actorRole = normalizeCode(request.getActorRole(), "ORDINARY_USER");
        String tenantPlan = normalizeCode(request.getTenantPlanCode(), "STANDARD");
        String workspaceRisk = normalizeCode(request.getWorkspaceRiskLevel(), "NORMAL");
        String backlog = normalizeCode(request.getWorkerBacklogLevel(), "NORMAL");
        String requestedRisk = normalizeCode(request.getRequestedToolRiskLevel(), "LOW");

        if ("AUDITOR".equals(actorRole)) {
            maxAuto = 0;
            maxAsync = 0;
            maxHighRisk = 0;
            influenceCodes.add("READ_ONLY_ROLE_BLOCKS_AUTO_EXECUTION");
        }
        if ("FREE".equals(tenantPlan) || "TRIAL".equals(tenantPlan) || "COMMUNITY".equals(tenantPlan)) {
            maxProposed = Math.min(maxProposed, 4);
            maxAuto = Math.min(maxAuto, 1);
            maxAsync = 0;
            influenceCodes.add("TENANT_PLAN_LIMITS_TOOL_BUDGET");
        }
        if ("HIGH".equals(workspaceRisk) || "RESTRICTED".equals(workspaceRisk)) {
            maxAuto = Math.min(maxAuto, 1);
            maxHighRisk = 0;
            influenceCodes.add("WORKSPACE_RISK_REQUIRES_APPROVAL");
        }
        if ("CRITICAL".equals(workspaceRisk) || "LOCKED".equals(workspaceRisk)) {
            maxAuto = 0;
            maxAsync = 0;
            maxHighRisk = 0;
            influenceCodes.add("WORKSPACE_RISK_REDUCES_TOOL_BUDGET");
        }
        if ("HIGH".equals(backlog)) {
            maxAuto = Math.min(maxAuto, 1);
            maxAsync = Math.min(maxAsync, 1);
            influenceCodes.add("WORKER_BACKLOG_REDUCES_TOOL_BUDGET");
        }
        if ("CRITICAL".equals(backlog)) {
            maxAuto = 0;
            maxAsync = 0;
            maxHighRisk = 0;
            influenceCodes.add("WORKER_BACKLOG_BLOCKS_TOOL_BUDGET");
        }
        if ("HIGH".equals(requestedRisk) || "CRITICAL".equals(requestedRisk)) {
            maxHighRisk = 0;
            influenceCodes.add("REQUESTED_TOOL_RISK_REQUIRES_APPROVAL");
        }
        if (influenceCodes.isEmpty()) {
            influenceCodes.add("GATEWAY_LOCAL_DEFAULT_POLICY");
        }

        GatewayAgentToolPolicyEnvelopeView view = new GatewayAgentToolPolicyEnvelopeView();
        String policyVersion = policyVersion(request);
        view.setAllowed(true);
        view.setPolicySource(LOCAL_POLICY_SOURCE);
        view.setPolicyVersion(policyVersion);
        Map<String, Integer> budget = new LinkedHashMap<>();
        budget.put("maxProposedToolCalls", Math.max(1, maxProposed));
        budget.put("maxAutoExecutableToolCalls", Math.max(0, Math.min(maxAuto, maxProposed)));
        budget.put("maxHighRiskToolCalls", Math.max(0, Math.min(maxHighRisk, Math.max(0, maxAuto))));
        budget.put("maxSingleArgumentsBytes", maxSingleBytes);
        budget.put("maxTotalArgumentsBytes", maxTotalBytes);
        view.setToolCallBudget(budget);
        view.setToolExecutionReadinessPolicy(readinessPolicy(
                request,
                policyVersion,
                Math.max(0, Math.min(maxAuto, maxProposed)),
                Math.max(0, maxAsync),
                influenceCodes
        ));
        return view;
    }

    /**
     * 把策略视图裁剪并序列化为 Header JSON。
     */
    public String envelopeJson(
            GatewayAgentToolPolicyEnvelopeView view,
            GatewayAgentToolPolicyEnvelopeRequest request,
            int maxHeaderBytes) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("toolCallBudget", toolCallBudget(view));
        envelope.put("toolExecutionReadinessPolicy", readinessPolicy(view, request));
        try {
            String json = objectMapper.writeValueAsString(envelope);
            int byteSize = json.getBytes(StandardCharsets.UTF_8).length;
            if (byteSize > maxHeaderBytes) {
                throw new IllegalStateException("工具策略 envelope 超过 Header 字节上限：" + byteSize);
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化工具策略 envelope", exception);
        }
    }

    private Map<String, Object> toolCallBudget(GatewayAgentToolPolicyEnvelopeView view) {
        Map<String, Object> result = new LinkedHashMap<>();
        putText(result, "policyVersion", view.getPolicyVersion());
        Map<String, Integer> budget = view.getToolCallBudget();
        if (budget != null) {
            putNonNegative(result, "maxProposedToolCalls", budget.get("maxProposedToolCalls"));
            putNonNegative(result, "maxAutoExecutableToolCalls", budget.get("maxAutoExecutableToolCalls"));
            putNonNegative(result, "maxHighRiskToolCalls", budget.get("maxHighRiskToolCalls"));
            putNonNegative(result, "maxSingleArgumentsBytes", budget.get("maxSingleArgumentsBytes"));
            putNonNegative(result, "maxTotalArgumentsBytes", budget.get("maxTotalArgumentsBytes"));
        }
        return result;
    }

    private Map<String, Object> readinessPolicy(
            GatewayAgentToolPolicyEnvelopeView view,
            GatewayAgentToolPolicyEnvelopeRequest request) {
        GatewayAgentToolExecutionReadinessPolicyView readiness = view.getToolExecutionReadinessPolicy();
        if (readiness == null) {
            readiness = readinessPolicy(request, view.getPolicyVersion(), 0, 0, List.of("MISSING_REMOTE_READINESS_POLICY"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        putText(result, "source", readiness.getSource());
        putText(result, "policyVersion", readiness.getPolicyVersion());
        putText(result, "actorRole", readiness.getActorRole());
        putText(result, "tenantPlanCode", readiness.getTenantPlanCode());
        putText(result, "workspaceRiskLevel", readiness.getWorkspaceRiskLevel());
        putText(result, "workerBacklogLevel", readiness.getWorkerBacklogLevel());
        putNonNegative(result, "maxAutoSyncTools", readiness.getMaxAutoSyncTools());
        putNonNegative(result, "maxAsyncTools", readiness.getMaxAsyncTools());
        putBoolean(result, "highRiskRequiresApproval", readiness.getHighRiskRequiresApproval());
        putBoolean(result, "criticalRiskBlocked", readiness.getCriticalRiskBlocked());
        putBoolean(result, "allowDraftWithoutAllParameters", readiness.getAllowDraftWithoutAllParameters());
        if (readiness.getInfluenceCodes() != null && !readiness.getInfluenceCodes().isEmpty()) {
            result.put("influenceCodes", readiness.getInfluenceCodes());
        }
        return result;
    }

    private GatewayAgentToolExecutionReadinessPolicyView readinessPolicy(
            GatewayAgentToolPolicyEnvelopeRequest request,
            String policyVersion,
            int maxAutoSyncTools,
            int maxAsyncTools,
            List<String> influenceCodes) {
        String workspaceRisk = normalizeCode(request.getWorkspaceRiskLevel(), "NORMAL");
        String backlog = normalizeCode(request.getWorkerBacklogLevel(), "NORMAL");
        GatewayAgentToolExecutionReadinessPolicyView readiness = new GatewayAgentToolExecutionReadinessPolicyView();
        readiness.setSource(READINESS_SOURCE);
        readiness.setPolicyVersion(policyVersion);
        readiness.setActorRole(normalizeCode(request.getActorRole(), "ORDINARY_USER"));
        readiness.setTenantPlanCode(normalizeCode(request.getTenantPlanCode(), "STANDARD"));
        readiness.setWorkspaceRiskLevel(workspaceRisk);
        readiness.setWorkerBacklogLevel(backlog);
        readiness.setMaxAutoSyncTools(maxAutoSyncTools);
        readiness.setMaxAsyncTools(maxAsyncTools);
        readiness.setHighRiskRequiresApproval(true);
        readiness.setCriticalRiskBlocked(true);
        readiness.setAllowDraftWithoutAllParameters(!"CRITICAL".equals(workspaceRisk) && !"CRITICAL".equals(backlog));
        readiness.setInfluenceCodes(List.copyOf(influenceCodes));
        return readiness;
    }

    private String policyVersion(GatewayAgentToolPolicyEnvelopeRequest request) {
        return "gateway-tool-policy-envelope:local-v1:"
                + normalizeCode(request.getActorRole(), "ORDINARY_USER") + ":"
                + normalizeCode(request.getTenantPlanCode(), "STANDARD") + ":"
                + normalizeCode(request.getWorkspaceRiskLevel(), "NORMAL") + ":"
                + normalizeCode(request.getWorkerBacklogLevel(), "NORMAL") + ":"
                + normalizeCode(request.getRequestedToolRiskLevel(), "LOW");
    }

    private static void putText(Map<String, Object> result, String key, String value) {
        if (value != null && !value.isBlank()) {
            result.put(key, value.trim());
        }
    }

    private static void putNonNegative(Map<String, Object> result, String key, Integer value) {
        if (value != null && value >= 0) {
            result.put(key, value);
        }
    }

    private static void putBoolean(Map<String, Object> result, String key, Boolean value) {
        if (value != null) {
            result.put(key, value);
        }
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static Long longValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String firstAuthorizedProjectId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String item : value.split(",")) {
            String projectId = item.trim();
            if (!projectId.isBlank()) {
                return projectId;
            }
        }
        return null;
    }

    private static String normalizeCode(String value, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value;
        return resolved.trim().replace("-", "_").replace(" ", "_").toUpperCase(Locale.ROOT);
    }
}
