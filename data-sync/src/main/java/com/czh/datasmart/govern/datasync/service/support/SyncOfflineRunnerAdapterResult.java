/**
 * @Author : Cui
 * @Date: 2026/07/05 14:52
 * @Description DataSmart Govern Backend - SyncOfflineRunnerAdapterResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 专用离线 Runner adapter 的低敏派发结果。
 *
 * <p>该结果表示“adapter 是否成功接收或完成本次合同”，不等同于完整执行报告。
 * 对于生产级 DataX-style Runner，更常见的模式会是异步派发：adapter 返回 {@code dispatched=true}，
 * 真正的读取进度、写入进度、checkpoint 推进和最终成功/失败通过后续 callback、Kafka 事件或执行报告回写。</p>
 *
 * <p>如果某个 adapter 选择同步执行并在返回前已经完成 complete/fail 回写，则可以把 {@code completed} 或
 * {@code failed} 置为 true；但字段语义必须诚实，不能因为“已经提交到外部队列”就声称完成。这个边界对后续
 * 任务状态、重试、告警和审计非常重要。</p>
 *
 * @param dispatched 是否已经交给专用 Runner 或外部 worker。异步排队成功也可以视为已派发。
 * @param completed 是否已经完成 data-sync complete 回写。异步 runner 初始派发通常为 false。
 * @param failed 是否已经完成 data-sync fail 回写。仅当 adapter 确认并处理失败状态时才应为 true。
 * @param dispatchStatus adapter 派发状态码，必须是低敏、低基数、可统计的字符串。
 * @param executionId 当前 execution ID。
 * @param runnerStatus 专用 Runner 的低敏状态，例如 QUEUED、RUNNING、REJECTED、SUCCEEDED。
 * @param adapterCode adapter 编码，用于诊断当前由哪个实现承接合同。
 * @param issueCodes 低敏问题码集合，不允许放入表名、SQL hash、字段名、连接地址或异常全文。
 * @param payloadPolicy 载荷策略说明。
 */
public record SyncOfflineRunnerAdapterResult(boolean dispatched,
                                             boolean completed,
                                             boolean failed,
                                             String dispatchStatus,
                                             Long executionId,
                                             String runnerStatus,
                                             String adapterCode,
                                             List<String> issueCodes,
                                             String payloadPolicy) {

    /**
     * adapter 结果的固定低敏策略。
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_OFFLINE_RUNNER_ADAPTER_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE";
}
