/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentCommandSafetyPrecheckDecisionPolicy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;
import java.util.Set;

/**
 * 命令安全预检决策策略。
 *
 * <p>该类把“哪些 issueCode 会阻断、哪些 issueCode 会进入审批、最终 decision 字符串是什么”
 * 从主服务中拆出来。这样做不是为了抽象炫技，而是为了让 {@link AgentToolActionCommandSafetyPrecheckService}
 * 保持专注：主服务负责收集事实，本类负责把事实翻译成稳定决策。</p>
 *
 * <p>后续如果 permission-admin 或租户套餐需要调整审批/阻断语义，可以优先修改这里，而不必重新阅读
 * 命令文本、路径和预算检查的全部流程。</p>
 */
final class AgentCommandSafetyPrecheckDecisionPolicy {

    static final String DECISION_ALLOW = "ALLOW_CONTROLLED_EXECUTION";
    static final String DECISION_APPROVAL = "REQUIRE_HUMAN_APPROVAL";
    static final String DECISION_BLOCK = "BLOCKED_BY_COMMAND_SAFETY";

    /**
     * 命中后必须 fail-closed 的问题码。
     *
     * <p>这些问题不是“需要用户确认一下就能继续”的风险，而是控制面无法证明安全的硬阻断项。</p>
     */
    private static final Set<String> BLOCKING_CODES = Set.of(
            "PRECHECK_POLICY_DISABLED",
            "COMMAND_LINE_REQUIRED",
            "COMMAND_LINE_TOO_LONG",
            "DANGEROUS_COMMAND_FRAGMENT",
            "WORKSPACE_ROOT_REQUIRED",
            "WORKING_DIRECTORY_OUTSIDE_WORKSPACE",
            "REFERENCED_PATH_OUTSIDE_WORKSPACE",
            "BLOCKED_PATH_FRAGMENT",
            "PATH_NORMALIZATION_FAILED",
            "TENANT_SCOPE_MISMATCH",
            "PROJECT_SCOPE_NOT_AUTHORIZED",
            "APPROVAL_FACT_UNSAFE"
    );

    /**
     * 命中后需要 Human-in-the-loop 或审批事实回查的问题码。
     */
    private static final Set<String> APPROVAL_CODES = Set.of(
            "UNKNOWN_COMMAND_REQUIRES_APPROVAL",
            "WRITE_COMMAND_REQUIRES_APPROVAL",
            "NETWORK_COMMAND_REQUIRES_APPROVAL",
            "PATH_PARENT_SEGMENT_REQUIRES_REVIEW",
            "APPROVAL_FACT_REQUIRES_SERVER_VERIFICATION"
    );

    private AgentCommandSafetyPrecheckDecisionPolicy() {
    }

    static boolean blocked(List<String> issueCodes) {
        return containsAny(issueCodes, BLOCKING_CODES);
    }

    static boolean requiresApproval(List<String> issueCodes) {
        return !blocked(issueCodes) && containsAny(issueCodes, APPROVAL_CODES);
    }

    static String decision(List<String> issueCodes) {
        if (blocked(issueCodes)) {
            return DECISION_BLOCK;
        }
        if (requiresApproval(issueCodes)) {
            return DECISION_APPROVAL;
        }
        return DECISION_ALLOW;
    }

    private static boolean containsAny(List<String> issueCodes, Set<String> expectedCodes) {
        for (String issueCode : issueCodes) {
            if (expectedCodes.contains(issueCode)) {
                return true;
            }
        }
        return false;
    }
}
