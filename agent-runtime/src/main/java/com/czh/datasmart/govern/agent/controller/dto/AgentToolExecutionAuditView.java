/**
 * @Author : Cui
 * @Date: 2026/05/13 23:42
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Agent 工具执行审计视图。
 *
 * <p>该视图用于回答“某次 Agent Run 准备调用哪些工具、风险多高、是否需要审批、属于哪个租户/项目、由谁触发”。
 * 当前阶段还不执行真实工具，但先生成审计计划，可以避免未来接入工具后出现不可解释的黑盒调用。
 */
public record AgentToolExecutionAuditView(String auditId,
                                          String sessionId,
                                          String runId,
                                          String bindingId,
                                          String toolCode,
                                          String toolType,
                                          String targetService,
                                          String targetEndpoint,
                                          Long targetResourceId,
                                          Long tenantId,
                                          Long projectId,
                                          Long workspaceId,
                                          String actorId,
                                          String riskLevel,
                                          String executionMode,
                                          Boolean requiresApproval,
                                          Boolean readOnly,
                                          Boolean idempotent,
                                          List<String> allowedActions,
                                          String planReason,
                                          @JsonIgnore
                                          Map<String, Object> planArguments,
                                          @JsonIgnore
                                          Map<String, Object> governanceHints,
                                          @JsonIgnore
                                          Map<String, Object> parameterValidation,
                                          String state,
                                          String traceId,
                                          String message,
                                          String approvalOperatorId,
                                          @JsonProperty(value = "approvalComment", access = JsonProperty.Access.WRITE_ONLY)
                                          String approvalComment,
                                          LocalDateTime approvalTime,
                                          LocalDateTime executionStartTime,
                                          LocalDateTime executionFinishTime,
                                          String outputSummary,
                                          String errorCode,
                                          LocalDateTime createTime,
                                          LocalDateTime updateTime) {

    /**
     * 返回计划参数的字段名，而不是字段值。
     *
     * <p>字段名可以帮助前端解释“本次计划涉及哪些输入”，但连接串、SQL、文件路径和过滤值必须
     * 留在 agent-runtime 内部参数接口中。这个方法由 Jackson 作为公开响应属性调用，Java 内部仍可
     * 通过 record 原生 accessor {@code planArguments()} 读取真实参数。</p>
     */
    @JsonProperty(value = "argumentFields", access = JsonProperty.Access.READ_ONLY)
    public List<String> argumentFields() {
        Set<String> sensitiveKeys = sensitiveArgumentKeys();
        return sortedKeys(planArguments).stream()
                .filter(key -> !sensitiveKeys.contains(key.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** 返回计划参数数量，便于前端展示规模而不接触具体值。 */
    @JsonProperty(value = "argumentCount", access = JsonProperty.Access.READ_ONLY)
    public int argumentCount() {
        return planArguments == null ? 0 : planArguments.size();
    }

    /** 返回被治理提示标记为敏感的参数数量，不返回敏感字段名称或值。 */
    @JsonProperty(value = "sensitiveArgumentCount", access = JsonProperty.Access.READ_ONLY)
    public int sensitiveArgumentCount() {
        if (governanceHints == null) {
            return 0;
        }
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        return distinctTextCount(raw);
    }

    /** 提取敏感参数的内部比较集合；集合本身不会序列化到公开响应。 */
    private Set<String> sensitiveArgumentKeys() {
        if (governanceHints == null) {
            return Set.of();
        }
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        if (!(raw instanceof Collection<?> collection)) {
            return Set.of();
        }
        return collection.stream()
                .filter(Objects::nonNull)
                .map(item -> item.toString().trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 返回治理提示键名，避免把审批策略、连接引用等内部值返回浏览器。 */
    @JsonProperty(value = "governanceHintKeys", access = JsonProperty.Access.READ_ONLY)
    public List<String> governanceHintKeys() {
        return sortedKeys(governanceHints);
    }

    /**
     * 返回参数校验的低敏统计摘要。
     *
     * <p>只保留键名和各类问题数量；具体缺失字段、非法值和默认值由控制面内部接口或下一轮澄清合同
     * 提供，普通审计列表不应成为 schema 细节和业务值的泄露渠道。</p>
     */
    @JsonProperty(value = "parameterValidationSummary", access = JsonProperty.Access.READ_ONLY)
    public Map<String, Object> parameterValidationSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("missingFieldCount", collectionSize(parameterValidation, "missingFields"));
        summary.put("invalidFieldCount", collectionSize(parameterValidation, "invalidFields"));
        summary.put("warningFieldCount", collectionSize(parameterValidation, "warningFields"));
        Object passed = parameterValidation == null ? null : parameterValidation.get("passed");
        if (passed instanceof Boolean) {
            summary.put("passed", passed);
        }
        return Map.copyOf(summary);
    }

    /** 审批备注可能由用户自由输入，公开审计只返回是否存在备注，不返回原文。 */
    @JsonProperty(value = "approvalCommentPresent", access = JsonProperty.Access.READ_ONLY)
    public boolean approvalCommentPresent() {
        return approvalComment != null && !approvalComment.isBlank();
    }

    private static List<String> sortedKeys(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new TreeSet<>(values.keySet().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .toList()));
    }

    private static int distinctTextCount(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return 0;
        }
        return (int) collection.stream()
                .filter(Objects::nonNull)
                .map(item -> item.toString().trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .distinct()
                .count();
    }

    private static int collectionSize(Map<String, Object> values, String key) {
        if (values == null) {
            return 0;
        }
        return distinctTextCount(values.get(key));
    }
}
