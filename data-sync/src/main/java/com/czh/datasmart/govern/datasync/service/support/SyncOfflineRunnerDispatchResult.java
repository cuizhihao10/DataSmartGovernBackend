/**
 * @Author : Cui
 * @Date: 2026/07/05 14:42
 * @Description DataSmart Govern Backend - SyncOfflineRunnerDispatchResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 离线 Runner 调度门面的低敏结果。
 *
 * <p>这个结果比 {@link SyncBatchRunOnceDispatchResult} 多了 Runner 合同状态，因为新的执行入口先要回答
 * “当前作业是否真的能交给最小 run-once bridge”，再回答“远端 run-once 是否完成”。这样 worker loop、运维台和测试
 * 可以区分：</p>
 * <p>1. 合同层阻断：例如需要审批、需要 checkpoint handoff、需要专用 DataX-style Runner；</p>
 * <p>2. 最小 run-once 已派发但远端失败；</p>
 * <p>3. 最小 run-once 已派发并完成。</p>
 *
 * <p>该结果仍然不携带 SQL、对象名、字段列表、连接信息、checkpoint 原始值或远端响应正文，只保留低敏状态码。</p>
 *
 * @param dispatched 是否已经真正调用下游 run-once 或专用 Runner。当前阶段只有最小 run-once 会置为 true。
 * @param completed 是否已经完成 data-sync complete 回写。
 * @param failed 是否已经完成 data-sync fail 回写。
 * @param dispatchStatus 离线 Runner 门面的派发状态。
 * @param executionId 当前 execution ID。
 * @param remoteRunStatus 下游 run-once 的低敏状态；未调用下游时为空。
 * @param runnerContractStatus Runner 合同状态，例如 MINIMAL_BRIDGE_END_TO_END_SUPPORTED 或 WAITING_APPROVAL。
 * @param issueCodes 低敏问题码集合。
 * @param payloadPolicy 载荷策略说明。
 */
public record SyncOfflineRunnerDispatchResult(boolean dispatched,
                                              boolean completed,
                                              boolean failed,
                                              String dispatchStatus,
                                              Long executionId,
                                              String remoteRunStatus,
                                              String runnerContractStatus,
                                              List<String> issueCodes,
                                              String payloadPolicy) {

    /**
     * 固定载荷策略。
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_OFFLINE_RUNNER_DISPATCH_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE";
}
