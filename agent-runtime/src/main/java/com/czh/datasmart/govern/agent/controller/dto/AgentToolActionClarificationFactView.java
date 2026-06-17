/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionClarificationFactRecord;

import java.time.Instant;
import java.util.List;

/**
 * 澄清事实登记结果视图。
 *
 * <p>该视图返回给登记调用方，用于确认事实已经进入 Java 控制面。
 * 它仍然只包含低敏元数据：factId、租户/项目/actor、run/session/command/tool、状态、过期时间和枚举码。
 * 不返回用户澄清原文，也不返回任何工具参数、SQL、prompt、payload、模型输出或内部 endpoint。</p>
 */
public record AgentToolActionClarificationFactView(
        /** 澄清事实 ID。登记调用方本来已经持有该 ID，返回它用于幂等确认和前端状态展示。 */
        String clarificationFactId,

        /** Agent 会话 ID，用于展示该事实属于哪一次对话窗口。 */
        String sessionId,

        /** Agent run ID，用于恢复预检时校验同一 run。 */
        String runId,

        /** 受控工具动作 commandId；可能为空，表示澄清发生在 command 生成前。 */
        String commandId,

        /** 工具编码。 */
        String toolCode,

        /** 工具治理策略版本。 */
        String requestedPolicyVersion,

        /** 租户 ID。 */
        String tenantId,

        /** 项目 ID。 */
        String projectId,

        /** 操作者 ID。 */
        String actorId,

        /** 当前事实状态。 */
        String status,

        /** 当前时间点是否已过期。 */
        Boolean expired,

        /** 低敏证据码。 */
        List<String> evidenceCodes,

        /** 低敏问题码。 */
        List<String> issueCodes,

        /** payload 安全策略说明，强调不包含事实正文。 */
        String payloadPolicy,

        /** 事实过期时间。 */
        Instant expiresAt,

        /** 首次登记时间。 */
        Instant createdAt,

        /** 最近更新时间。 */
        Instant updatedAt
) {

    /**
     * 从领域记录转换为 API 视图。
     *
     * <p>转换时需要传入 now，是因为“是否过期”不是存储字段，而是相对于当前时间的判断结果。
     * API 调用方看到 expired=true 时，应重新要求用户补充或重新登记澄清事实。</p>
     */
    public static AgentToolActionClarificationFactView from(AgentToolActionClarificationFactRecord record,
                                                            Instant now,
                                                            String payloadPolicy) {
        return new AgentToolActionClarificationFactView(
                record.clarificationFactId(),
                record.sessionId(),
                record.runId(),
                record.commandId(),
                record.toolCode(),
                record.requestedPolicyVersion(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.status(),
                record.expiredAt(now),
                record.evidenceCodes(),
                record.issueCodes(),
                payloadPolicy,
                record.expiresAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
