/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentApprovalFactTrustedRegistrationGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.config.AgentApprovalFactTrustProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * 审批事实内部登记接口的 fail-closed 服务身份守卫。
 *
 * <p>该守卫只解决“谁可以写审批事实”，不判断用户是否原本有权操作业务资源。完成服务身份校验后，
 * 后续流程仍需保存并核对 userId、sessionId、runId、delegationId、工具和资源范围。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentApprovalFactTrustedRegistrationGuard {

    private final AgentApprovalFactTrustProperties properties;

    /**
     * 要求调用方同时满足来源服务白名单和共享凭据校验。
     *
     * <p>两个条件使用逻辑与，任意配置缺失、Header 缺失或不匹配都会拒绝。token 使用固定时序比较，
     * 避免普通字符串比较泄露明显的前缀匹配时间差。</p>
     *
     * @param sourceService 调用方通过内部 Header 声明的服务身份
     * @param presentedToken 调用方提交的内部共享凭据
     * @throws PlatformBusinessException 来源或凭据不可信时抛出 FORBIDDEN
     */
    public void requireTrusted(String sourceService, String presentedToken) {
        String configuredToken = text(properties.getSharedToken());
        String source = text(sourceService);
        String token = text(presentedToken);
        boolean sourceAllowed = source != null && properties.getAllowedSourceServices().stream()
                .anyMatch(allowed -> allowed != null && allowed.equalsIgnoreCase(source));
        boolean tokenMatches = configuredToken != null && token != null && MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!sourceAllowed || !tokenMatches) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "仅受信任的 Agent Runtime、审批服务或管理控制面可以登记审批事实");
        }
    }

    /**
     * 在服务身份通过基础认证后，继续验证该来源是否有权作出最终审批决定。
     *
     * <p>{@code PENDING} 只表示运行时已提出一个需要人工或审批系统处理的动作，所以任何基础受信来源
     * 都可以登记。{@code APPROVED}/{@code REJECTED} 则会直接改变高风险工具能否继续执行，必须由审批服务
     * 或 permission-admin 管理控制面产生。未知状态由业务服务归一为 PENDING，守卫仍按非决定状态处理，
     * 不会因为一个未识别的字符串意外赋予批准能力。</p>
     *
     * @param sourceService 已通过 {@link #requireTrusted(String, String)} 校验的来源服务名
     * @param requestedStatus 调用方希望持久化的审批状态
     * @throws PlatformBusinessException 来源不是审批决策者时拒绝写入最终决定
     */
    public void requireDecisionAuthority(String sourceService, String requestedStatus) {
        String normalizedStatus = text(requestedStatus);
        if (!isFinalDecision(normalizedStatus)) {
            return;
        }
        String source = text(sourceService);
        Set<String> decisionSources = properties.getApprovalDecisionSourceServices();
        boolean allowed = source != null && decisionSources != null && decisionSources.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.trim().equalsIgnoreCase(source));
        if (!allowed) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前受信服务只能登记待审批事实，不能作出 APPROVED 或 REJECTED 审批决定");
        }
    }

    /**
     * 仅把明确的终态当作需要额外授权的审批决定。
     *
     * <p>服务层会把未知状态归一为 PENDING；这里保持相同的保守语义，避免守卫与领域服务对状态词典
     * 的解释不一致。使用 Locale.ROOT 可以保证不同部署语言环境下的大小写转换结果稳定。</p>
     */
    private boolean isFinalDecision(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        return "APPROVED".equals(normalized) || "REJECTED".equals(normalized);
    }

    /** 统一清理 Header 和配置文本；空白内容返回 null，确保校验不会把空字符串视为合法凭据。 */
    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
