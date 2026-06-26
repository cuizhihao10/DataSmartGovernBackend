/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantVerificationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * artifact 正文读取授权事实验证服务。
 *
 * <p>该服务是 body-read-grant 链路的“服务端事实回查门”。它解决一个关键安全问题：
 * 调用方可以提交一个看起来像 grantDecisionReference 的字符串，但只有服务端 store 里真实存在、
 * 未过期、未撤销、且与当前重新授权结果绑定到同一组低敏上下文的记录，才允许进入 final-check 或 object-store probe。</p>
 *
 * <p>验证流程刻意分两步：第一步由 grant service 重新计算“当前是否还允许读取”；第二步由本服务验证
 * “调用方提交的 previousGrantDecisionReference 是否确实是服务端之前签发给同一上下文的事实”。
 * 这比单纯比较字符串更稳，因为 grant service 每次签发都会生成新的过期时间和新引用，而旧引用仍应通过
 * store 中的历史事实来判断是否可被继续使用。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactBodyReadGrantVerificationService {

    private final AgentToolActionArtifactBodyReadGrantStore grantStore;

    /**
     * 验证 previousGrantDecisionReference 对应的服务端 fact 是否仍可用。
     *
     * @param previousGrantDecisionReference 调用方从上一阶段 body-read-grants 拿到并提交的低敏引用。
     * @param currentGrantDecision 当前请求上下文重新经过 grant service 后得到的低敏决策。
     * @return verified=true 表示可以继续后续控制面；false 表示必须 fail-closed。
     */
    public AgentToolActionArtifactBodyReadGrantVerificationResult verifyStoredGrant(
            String previousGrantDecisionReference,
            AgentToolActionArtifactBodyReadGrantResponse currentGrantDecision) {
        String reference = safeText(previousGrantDecisionReference);
        if (reference == null) {
            return deniedMissingReference();
        }

        AgentToolActionArtifactBodyReadGrantRecord record =
                grantStore.findByReference(reference).orElse(null);
        if (record == null) {
            return AgentToolActionArtifactBodyReadGrantVerificationResult.denied(
                    "DENIED_STORED_BODY_READ_GRANT_NOT_FOUND",
                    null,
                    List.of("STORED_BODY_READ_GRANT_LOOKUP_COMPLETED"),
                    List.of("STORED_BODY_READ_GRANT_NOT_FOUND"),
                    List.of(
                            "请先调用 body-read-grants 获取服务端签发的 grantDecisionReference，再进入 final-check 或 object-store probe。",
                            "不要伪造 grant 引用；即使格式正确，只要服务端 grant store 不存在该事实，也会被拒绝。"
                    )
            );
        }

        long nowEpochMs = Instant.now().toEpochMilli();
        if (record.status() == AgentToolActionArtifactBodyReadGrantStatus.REVOKED) {
            return AgentToolActionArtifactBodyReadGrantVerificationResult.denied(
                    "DENIED_STORED_BODY_READ_GRANT_REVOKED",
                    record,
                    List.of("STORED_BODY_READ_GRANT_FOUND", "STORED_BODY_READ_GRANT_REVOKED"),
                    List.of("STORED_BODY_READ_GRANT_REVOKED"),
                    List.of("该 grant 已被服务端撤销，请重新完成审批、授权或 artifact 风险复核后再发起读取。")
            );
        }
        if (record.expiredAt(nowEpochMs)) {
            AgentToolActionArtifactBodyReadGrantRecord expired = record.expire(nowEpochMs);
            grantStore.save(expired);
            return AgentToolActionArtifactBodyReadGrantVerificationResult.denied(
                    "DENIED_STORED_BODY_READ_GRANT_EXPIRED",
                    expired,
                    List.of("STORED_BODY_READ_GRANT_FOUND", "STORED_BODY_READ_GRANT_EXPIRED"),
                    List.of("STORED_BODY_READ_GRANT_EXPIRED"),
                    List.of("该 grant 已超过服务端有效期，请重新调用 body-read-grants 生成新的短期读取授权事实。")
            );
        }
        if (!record.matchesDecision(currentGrantDecision)) {
            return AgentToolActionArtifactBodyReadGrantVerificationResult.denied(
                    "DENIED_STORED_BODY_READ_GRANT_CONTEXT_MISMATCH",
                    record,
                    List.of("STORED_BODY_READ_GRANT_FOUND", "STORED_BODY_READ_GRANT_CONTEXT_COMPARED"),
                    List.of("STORED_BODY_READ_GRANT_CONTEXT_MISMATCH"),
                    List.of(
                            "previousGrantDecisionReference 与当前 command/artifact/run/session/readPurpose/contentMode 不一致。",
                            "请勿复用其他命令、其他 artifact、其他项目或其他读取模式下签发的 grant。"
                    )
            );
        }

        return AgentToolActionArtifactBodyReadGrantVerificationResult.verified(
                record,
                List.of(
                        "STORED_BODY_READ_GRANT_FOUND",
                        "STORED_BODY_READ_GRANT_ACTIVE",
                        "STORED_BODY_READ_GRANT_CONTEXT_MATCHED"
                )
        );
    }

    private AgentToolActionArtifactBodyReadGrantVerificationResult deniedMissingReference() {
        return AgentToolActionArtifactBodyReadGrantVerificationResult.denied(
                "DENIED_STORED_BODY_READ_GRANT_REFERENCE_REQUIRED",
                null,
                List.of(),
                List.of("STORED_BODY_READ_GRANT_REFERENCE_REQUIRED"),
                List.of("final-check 和 object-store probe 必须携带 body-read-grants 返回的 previousGrantDecisionReference。")
        );
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
