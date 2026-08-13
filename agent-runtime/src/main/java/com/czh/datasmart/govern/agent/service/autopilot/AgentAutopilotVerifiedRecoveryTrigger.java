/**
 * @Author : Cui
 * @Date: 2026/08/11 19:45
 * @Description DataSmart Govern Backend - AgentAutopilotVerifiedRecoveryTrigger.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;

import java.time.OffsetDateTime;

/**
 * Kafka 事件与 PostgreSQL session/run/authorization 完成一致性校验后的可信工作包。
 *
 * <p>后续 Python 调用和 data-sync 写操作只能接收该类型，不能直接接收原始 Kafka JSON。这样代码结构
 * 会迫使调用方先经过验证器，也便于单元测试区分“不可信传输对象”和“可进入策略层的事实”。</p>
 */
public record AgentAutopilotVerifiedRecoveryTrigger(
        AgentAutopilotRecoveryTriggerEvent event,
        AgentSessionRecord session,
        AgentRunRecord rootRun,
        AgentAutopilotAuthorizationSnapshot authorization,
        OffsetDateTime deadlineAt,
        OffsetDateTime recoveryStartedAt) {
}
