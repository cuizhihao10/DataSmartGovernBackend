/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionManualResolutionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Agent 工具真实提交事实人工对账请求。
 *
 * <p>`UNKNOWN` 表示 agent-runtime 已经开始真实下游调用，但因为超时、连接中断或响应不可判定，
 * 无法确认 data-quality/task-management 是否已经创建任务。此时系统不能自动重放真实副作用，
 * 必须先由运维或管理员根据下游任务表、幂等键、日志和告警完成对账，再通过该请求把事实推进为稳定终态。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 该请求只允许写低敏结论，不允许上传下游响应全文、异常正文、SQL、prompt、样本、凭据或内部 URL；</p>
 * <p>2. `dryRun` 默认 true，调用方必须显式传 `false` 才会真正更新事实，降低误操作风险；</p>
 * <p>3. 当前只允许把 UNKNOWN 推进到 SUBMITTED 或 REJECTED，不能覆盖已经稳定的终态；</p>
 * <p>4. `downstreamTaskId` 只有在确认下游已经创建任务时才需要，确认未创建时必须保持为空。</p>
 *
 * @param targetStatus 人工对账后的目标状态，仅支持 SUBMITTED 或 REJECTED。
 * @param downstreamTaskId 对账确认已经创建的下游 task-management 任务 ID；targetStatus=SUBMITTED 时必填。
 * @param downstreamTaskStatus 下游任务当前状态摘要，例如 PENDING/RUNNING/SUCCEEDED/FAILED。
 * @param outcome 低敏结果码；为空时服务端按 targetStatus 兜底生成。
 * @param resolutionReasonCode 机器可聚合的对账原因码，例如 DOWNSTREAM_TASK_FOUND 或 DOWNSTREAM_TASK_NOT_FOUND。
 * @param operatorNote 面向运维的低敏备注，禁止写业务正文、SQL、prompt、样本、凭据、URL 或异常全文。
 * @param dryRun 是否只预览不落库；默认 true。
 * @param tenantId 可选租户收口条件，不能扩大 Header 中的可信数据范围。
 * @param projectId 可选项目收口条件，PROJECT 范围下必须落在授权集合内。
 * @param actorId 可选触发者收口条件，SELF 范围下会被强制收口到当前 actor。
 * @param runId 可选 Agent run 收口条件，避免跨 run 误处理旧事实。
 * @param sessionId 可选 Agent session 收口条件，避免跨会话误处理旧事实。
 */
public record AgentToolActionSubmissionManualResolutionRequest(
        @Size(max = 40)
        String targetStatus,

        Long downstreamTaskId,

        @Size(max = 80)
        String downstreamTaskStatus,

        @Size(max = 120)
        String outcome,

        @Size(max = 120)
        String resolutionReasonCode,

        @Size(max = 220)
        String operatorNote,

        Boolean dryRun,

        @Size(max = 80)
        String tenantId,

        @Size(max = 80)
        String projectId,

        @Size(max = 120)
        String actorId,

        @Size(max = 180)
        String runId,

        @Size(max = 180)
        String sessionId
) {
}
