/**
 * @Author : Cui
 * @Date: 2026/07/05 16:19
 * @Description DataSmart Govern Backend - SyncBatchRunOnceRemoteExecutionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * datasource-management run-once 远端执行结果。
 *
 * <p>这个结果位于“远端 Reader/Writer 批次循环”和“data-sync 生命周期回写”之间。
 * 过去 {@link SyncBatchRunOnceDispatchService} 调用远端后会立刻 complete/fail 当前 execution，
 * 这对单对象同步是正确的，但对 OBJECT_LIST 多对象 fan-out 不适合：如果第一张表完成后就 complete，
 * 后面的表还没同步，用户却会看到整个 execution 成功。</p>
 *
 * <p>因此本 record 把“远端已经跑完一个对象”与“整个 execution 是否终态”解耦：
 * 单对象路径会把它立即转换成生命周期 complete/fail；多对象路径会累加多个对象的结果，
 * 只有全部对象成功后才统一 complete，任一对象失败则统一 fail。</p>
 *
 * @param remoteCalled 是否已经调用过 datasource-management。配置关闭或本地校验失败时为 false。
 * @param completed 当前对象的 run-once 是否完成。
 * @param failed 当前对象的 run-once 是否失败或被 fail-closed。
 * @param dispatchStatus 低敏派发状态。
 * @param executionId execution ID。
 * @param remoteRunStatus 远端低敏运行状态。
 * @param totalRecordsRead 当前对象累计读取条数。
 * @param totalRecordsWritten 当前对象累计写入条数。
 * @param totalFailedRecordCount 当前对象累计失败条数。
 * @param errorType 失败类型，只允许低敏分类。
 * @param errorCode 失败原因码。
 * @param errorMessage 失败说明，不包含 SQL、连接串、对象映射原文、字段值或样本。
 * @param retryable 当前错误是否建议重试。
 * @param issueCodes 低敏问题码。
 * @param payloadPolicy 载荷边界说明。
 */
public record SyncBatchRunOnceRemoteExecutionResult(
        boolean remoteCalled,
        boolean completed,
        boolean failed,
        String dispatchStatus,
        Long executionId,
        String remoteRunStatus,
        Long totalRecordsRead,
        Long totalRecordsWritten,
        Long totalFailedRecordCount,
        String errorType,
        String errorCode,
        String errorMessage,
        boolean retryable,
        List<String> issueCodes,
        String payloadPolicy
) {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_RUN_ONCE_REMOTE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE";

    public SyncBatchRunOnceRemoteExecutionResult {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        payloadPolicy = PAYLOAD_POLICY;
    }
}
