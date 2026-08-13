/**
 * @Author : Cui
 * @Date: 2026/08/13 14:20
 * @Description DataSmart Govern 后端 - AgentAutopilotRecoveryFactsVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对自动重试事实执行范围有限、结果确定的格式和一致性校验。
 *
 * <p>该映射来自 Python，因此只是未受信任的传输数据。本类不判断底层诊断是否真实；data-sync 会为此独立
 * 读取自己的执行账本。本类只负责阻止不完整或相互矛盾的映射在 Java 侧被当作可重试候选，避免越过诊断
 * 真实性和持久状态的业务边界。</p>
 */
public final class AgentAutopilotRecoveryFactsVerifier {

    private static final String TRANSIENT_FAILURE_CLASS = "TRANSIENT_CONNECTOR_OR_WORKER";
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z0-9_.:-]{1,96}");
    private static final Set<String> NON_TRANSIENT_CODES = Set.of(
            "TARGET_DUPLICATE_KEY", "TARGET_NOT_NULL_VIOLATION", "SCHEMA_COLUMN_MISMATCH",
            "TARGET_COLUMN_TOO_NARROW", "TYPE_OR_FORMAT_CONVERSION_FAILED",
            "DATASOURCE_PERMISSION_DENIED", "DATASOURCE_CREDENTIAL_INVALID",
            "DATA_CONTRACT_VIOLATION", "SCOPE_MISMATCH");

    private AgentAutopilotRecoveryFactsVerifier() {
    }

    /**
     * 判断给定投影在自身内部是否一致，且是否可作为受限重试的候选。
     *
     * <p>每个条件都故意写得明确：故障类别必须是瞬时连接器或工作进程类别，两个重试标记必须是字面量
     * 布尔值，至少存在一个失败对象，且根因列表必须是数量受限的安全代码集合，其中不能包含已知的非瞬时
     * 原因。模型置信度和自由文本错误信息刻意不参与本判断。</p>
     *
     * @param facts 来自 Python 的未受信任低敏感投影
     * @return 仅当该映射可作为候选继续接受控制平面检查时返回 {@code true}
     */
    public static boolean eligibleForAutomaticRetry(Map<String, Object> facts) {
        if (facts == null || !TRANSIENT_FAILURE_CLASS.equals(code(facts.get("failureClass")))) {
            return false;
        }
        if (!Boolean.TRUE.equals(facts.get("retryable"))
                || !Boolean.TRUE.equals(facts.get("eligibleForAutomaticRetry"))) {
            return false;
        }
        int failedObjectCount = positiveInt(facts.get("failedObjectCount"));
        if (failedObjectCount <= 0 || failedObjectCount > 1_000_000) {
            return false;
        }
        Object rawRootCauses = facts.get("rootCauseCodes");
        if (!(rawRootCauses instanceof Collection<?> causes) || causes.isEmpty()) {
            return false;
        }
        boolean transientCause = false;
        for (Object rawCause : causes) {
            String cause = code(rawCause);
            if (cause.isBlank() || !SAFE_CODE.matcher(cause).matches()
                    || NON_TRANSIENT_CODES.contains(cause)) {
                return false;
            }
            if (cause.contains("CONNECTOR") || cause.contains("NETWORK") || cause.contains("WORKER")
                    || cause.contains("TIMEOUT") || cause.contains("UNAVAILABLE")) {
                transientCause = true;
            }
        }
        return transientCause;
    }

    /** 在不把自由文本当作代码接受的前提下，规范化传输中的代码值。 */
    private static String code(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase().replace('-', '_');
    }

    /** 解析有上界的正计数；拒绝布尔值和小数文本。 */
    private static int positiveInt(Object value) {
        if (value instanceof Boolean || value == null) {
            return 0;
        }
        try {
            int result = Integer.parseInt(String.valueOf(value));
            return result > 0 ? result : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
