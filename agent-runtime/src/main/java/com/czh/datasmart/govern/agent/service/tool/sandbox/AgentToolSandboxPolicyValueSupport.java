/**
 * @Author : Cui
 * @Date: 2026/07/02 01:30
 * @Description DataSmart Govern Backend - AgentToolSandboxPolicyValueSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.sandbox;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sandbox 策略值规范化支撑。
 *
 * <p>该类只处理枚举解析、集合规范化和安全默认值，不读取数据库、不修改审计状态，也不决定工具是否
 * 可以执行。最终 allow/block 决策仍由 {@link AgentToolSandboxPolicyService} 统一完成。这样拆分后，
 * 主服务可以专注解释业务规则，而字符串大小写、空值和配置兜底不会淹没策略主流程。</p>
 *
 * <p>所有方法保持包级可见和无状态，防止它被误用为跨模块通用工具。输入异常时返回空值或保守默认值，
 * 上层策略会据此 fail-closed，而不是把未知枚举当作低风险。</p>
 */
final class AgentToolSandboxPolicyValueSupport {

    private AgentToolSandboxPolicyValueSupport() {
    }

    /** 判断配置列表是否包含目标值，比较时忽略大小写和首尾空白。 */
    static boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null || values.isEmpty() || expected == null) {
            return false;
        }
        String normalizedExpected = normalize(expected);
        return values.stream().map(AgentToolSandboxPolicyValueSupport::normalize)
                .anyMatch(normalizedExpected::equals);
    }

    /** 把配置或请求集合转换为大写规范集合，便于执行稳定的白名单比较。 */
    static Set<String> normalizedSet(Collection<String> values) {
        Set<String> result = new HashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(normalize(value));
        }
        return result;
    }

    /** 解析工具风险枚举；未知值返回 null，由上层策略按不可信事实处理。 */
    static AgentToolRiskLevel parseRiskLevel(String riskLevel) {
        try {
            return AgentToolRiskLevel.valueOf(normalize(riskLevel));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 解析执行模式；未知值返回 null，禁止静默回退到同步或异步执行。 */
    static AgentToolExecutionMode parseExecutionMode(String executionMode) {
        try {
            return AgentToolExecutionMode.valueOf(normalize(executionMode));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 参数预算缺失或非法时回退到 64KB，避免无限制序列化工具参数。 */
    static int safeMaxArgumentBytes(AgentRuntimeProperties.ToolSandboxProperties sandbox) {
        return sandbox.getMaxArgumentBytes() == null || sandbox.getMaxArgumentBytes() <= 0
                ? 64 * 1024
                : sandbox.getMaxArgumentBytes();
    }

    /** 同步超时缺失或非法时回退到 30 秒，避免请求线程无限等待。 */
    static long safeMaxSyncTimeoutMs(AgentRuntimeProperties.ToolSandboxProperties sandbox) {
        return sandbox.getMaxSyncTimeoutMs() == null || sandbox.getMaxSyncTimeoutMs() <= 0
                ? 30000L
                : sandbox.getMaxSyncTimeoutMs();
    }

    /** 稳定的机器值规范化函数；null 转换为空串，不向调用方抛出 NPE。 */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 保留原始大小写的空值安全 trim，用于非枚举展示字段。 */
    static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
