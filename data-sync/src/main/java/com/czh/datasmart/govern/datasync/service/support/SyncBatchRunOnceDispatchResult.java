/**
 * @Author : Cui
 * @Date: 2026/06/29 13:02
 * @Description DataSmart Govern Backend - SyncBatchRunOnceDispatchResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * data-sync 派发 datasource-management run-once 后的低敏调度结果。
 *
 * <p>该对象用于单元测试、内部诊断和未来 worker API 的最小返回摘要。它不包含字段清单、对象定位、SQL、
 * checkpoint 原始值、行数据、连接信息或远端响应正文，只表达“是否派发、是否完成、是否失败、失败/阻断原因码”。</p>
 *
 * @param dispatched 是否已经真正调用 datasource-management run-once。前置校验失败时为 false。
 * @param completed 是否已经根据远端结果调用 data-sync complete 回调。
 * @param failed 是否已经根据前置校验或远端结果调用 data-sync fail 回调。
 * @param dispatchStatus 派发状态，例如 DISPATCHED_AND_COMPLETED、FAILED_BEFORE_REMOTE_CALL。
 * @param executionId 当前 execution ID，用于调用方关联状态机。
 * @param remoteRunStatus 远端 runStatus 的低敏枚举；未调用远端时为空。
 * @param issueCodes 低敏问题码集合，可用于后续运营台筛选和测试断言。
 * @param payloadPolicy 载荷策略说明，提醒调用方不要把该结果扩展为敏感执行详情。
 */
public record SyncBatchRunOnceDispatchResult(boolean dispatched,
                                             boolean completed,
                                             boolean failed,
                                             String dispatchStatus,
                                             Long executionId,
                                             String remoteRunStatus,
                                             List<String> issueCodes,
                                             String payloadPolicy) {

    /**
     * 固定载荷策略。
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_RUN_ONCE_DISPATCH_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE";
}
