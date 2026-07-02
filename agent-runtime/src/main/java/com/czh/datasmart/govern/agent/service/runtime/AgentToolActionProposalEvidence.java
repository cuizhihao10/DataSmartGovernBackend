/**
 * @Author : Cui
 * @Date: 2026/07/02 03:20
 * @Description DataSmart Govern Backend - AgentToolActionProposalEvidence.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 工具动作命令提案的低敏证据聚合结果。
 *
 * <p>commandType/idempotencyKey 是后续 writer 的稳定输入；accepted、missing、rejected 分别服务确认页、
 * 审计解释和 fail-closed 判断。该对象禁止承载工具实参或 payload 正文，只能保存代码和受控引用。
 */
record AgentToolActionProposalEvidence(
        String commandType,
        String idempotencyKey,
        List<String> accepted,
        List<String> missing,
        List<String> rejected
) {
}
