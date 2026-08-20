/**
 * @Author : Cui
 * @Date: 2026/08/19 20:50
 * @Description DataSmart Govern Backend - AgentRunPublicVariablesProjector.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Agent Run 返回给 HTTP 客户端之前的变量投影器。
 *
 * <p>Run 内部变量同时承担两种职责：一部分是给 Java/Python/工具执行链路使用的服务器事实，另一部分是
 * 前端展示规划进度所需的低敏摘要。两者不能直接共用一个 JSON Map。特别是确认 claim、幂等 receipt 和
 * Autopilot 原始授权快照包含内部完整性字段、权限范围或策略摘要，原样返回会把控制面实现细节暴露给普通
 * 查询调用者。</p>
 *
 * <p>本类只在响应边界做复制和收缩，不修改 Run、不写数据库、不改变授权判断。未知的普通业务变量暂时保留，
 * 以兼容已有 Agent Console；已知的内部变量则 fail-closed：宁可不显示，也不把原始控制事实返回到浏览器。</p>
 */
public final class AgentRunPublicVariablesProjector {

    /** 内部一次性确认 claim，不是用户界面展示字段。 */
    private static final String CONFIRMATION_CLAIM = "confirmedExecutionClaim";

    /** 内部确认结果 receipt，可能包含幂等和执行边界摘要。 */
    private static final String CONFIRMATION_RECEIPT = "confirmedExecutionReceipt";

    /** 持久化的完整 Autopilot 授权键；对外只返回 AgentAutopilotSnapshotView。 */
    private static final String AUTOPILOT_AUTHORIZATION = "autopilotAuthorization";

    private AgentRunPublicVariablesProjector() {
    }

    /**
     * 生成浏览器可见的变量副本。
     *
     * @param internalVariables Run 聚合中的服务器内部变量
     * @return 不会与内部 Map 共享可变状态的低敏变量视图
     */
    public static Map<String, Object> project(Map<String, Object> internalVariables) {
        if (internalVariables == null || internalVariables.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        internalVariables.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null
                    || CONFIRMATION_CLAIM.equals(key) || CONFIRMATION_RECEIPT.equals(key)) {
                return;
            }
            if (AUTOPILOT_AUTHORIZATION.equals(key)) {
                // 原始授权 Map 只在服务端使用；转换失败时不返回原文，避免损坏数据变成信息泄露渠道。
                if (value instanceof Map<?, ?> rawMap) {
                    Map<String, Object> durable = stringKeyMap(rawMap);
                    try {
                        projected.put(key, AgentAutopilotSnapshotView.fromDurableAuthorization(durable));
                    } catch (IllegalArgumentException ignored) {
                        // 数据损坏应由审计/诊断发现，公开接口保持最小暴露面。
                    }
                }
                return;
            }
            if ("toolPlans".equals(key)) {
                // toolPlans 是历史页面最容易误当成“可再次执行表单”的字段。只保留计划结构和
                // 低敏统计，真实 arguments/governanceHints/parameterValidation 必须通过内部控制面读取。
                projected.put(key, projectToolPlans(value));
                return;
            }
            projected.put(key, value);
        });
        return Map.copyOf(projected);
    }

    /** 把 Jackson/JDBC 反序列化得到的任意键 Map 收敛为授权视图所需的字符串键 Map。 */
    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                normalized.put(String.valueOf(key), value);
            }
        });
        return normalized;
    }

    /**
     * 投影一组工具计划，确保嵌套 Map 不会绕过顶层变量脱敏。
     *
     * <p>这里采用白名单字段，而不是复制未知字段。原因是 Python Runtime 的计划合同会持续演进，
     * 未知字段可能在未来携带 SQL、连接引用、模型 prompt 或内部审批快照；公开响应宁可少显示，也
     * 不能因为前端尚未升级就把新字段原样广播。</p>
     */
    private static List<Map<String, Object>> projectToolPlans(Object rawValue) {
        if (!(rawValue instanceof Collection<?> plans)) {
            return List.of();
        }
        List<Map<String, Object>> projectedPlans = new ArrayList<>();
        for (Object rawPlan : plans) {
            if (!(rawPlan instanceof Map<?, ?> plan)) {
                continue;
            }
            Map<String, Object> safePlan = new LinkedHashMap<>();
            copyIfPresent(plan, safePlan, "sequence");
            copyIfPresent(plan, safePlan, "toolCode");
            copyIfPresent(plan, safePlan, "toolName");
            copyIfPresent(plan, safePlan, "riskLevel");
            copyIfPresent(plan, safePlan, "executionMode");
            copyIfPresent(plan, safePlan, "requiresApproval");
            copyIfPresent(plan, safePlan, "requiresHumanApproval");
            copyIfPresent(plan, safePlan, "reason");

            Map<String, Object> arguments = mapValue(plan.get("arguments"));
            Map<String, Object> governanceHints = mapValue(plan.get("governanceHints"));
            Map<String, Object> parameterValidation = mapValue(plan.get("parameterValidation"));
            safePlan.put("argumentFields", safeArgumentKeys(arguments, governanceHints));
            safePlan.put("argumentCount", arguments.size());
            safePlan.put("sensitiveArgumentCount", sensitiveArgumentCount(governanceHints));
            safePlan.put("governanceHintKeys", sortedKeys(governanceHints));
            safePlan.put("parameterValidationSummary", parameterValidationSummary(parameterValidation));
            projectedPlans.add(Map.copyOf(safePlan));
        }
        return List.copyOf(projectedPlans);
    }

    /** 复制白名单标量字段，不复制原始嵌套对象。 */
    private static void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    /** 把任意 JSON object 收敛成字符串键 Map；非 object 值按空 Map 处理。 */
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        return stringKeyMap(rawMap);
    }

    /** 返回稳定排序的字段名列表，避免公开响应因 Map 顺序变化产生无意义 diff。 */
    private static List<String> sortedKeys(Map<String, Object> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(values.keySet().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .toList()));
    }

    /** 返回非敏感参数名；敏感参数只用数量表达，避免字段名本身泄露连接或业务细节。 */
    private static List<String> safeArgumentKeys(
            Map<String, Object> arguments,
            Map<String, Object> governanceHints) {
        Set<String> sensitive = sensitiveArgumentNames(governanceHints);
        return sortedKeys(arguments).stream()
                .filter(key -> !sensitive.contains(key.toLowerCase()))
                .toList();
    }

    /** 构造敏感参数名的内部比较集合，不将集合原样放入公开 Map。 */
    private static Set<String> sensitiveArgumentNames(Map<String, Object> governanceHints) {
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(item -> item.toString().trim().toLowerCase())
                .filter(item -> !item.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 只统计治理提示中的敏感字段数量，不公开敏感字段名和值。 */
    private static int sensitiveArgumentCount(Map<String, Object> governanceHints) {
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        if (!(raw instanceof Collection<?> values)) {
            return 0;
        }
        return (int) values.stream()
                .filter(Objects::nonNull)
                .map(item -> item.toString().trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
    }

    /** 返回参数校验统计，不返回缺失字段名称、非法值或默认值。 */
    private static Map<String, Object> parameterValidationSummary(Map<String, Object> validation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("missingFieldCount", collectionSize(validation.get("missingFields")));
        summary.put("invalidFieldCount", collectionSize(validation.get("invalidFields")));
        summary.put("warningFieldCount", collectionSize(validation.get("warningFields")));
        Object passed = validation.get("passed");
        if (passed instanceof Boolean) {
            summary.put("passed", passed);
        }
        return Map.copyOf(summary);
    }

    private static int collectionSize(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return 0;
        }
        return (int) values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(item -> !item.isBlank())
                .distinct()
                .count();
    }
}
