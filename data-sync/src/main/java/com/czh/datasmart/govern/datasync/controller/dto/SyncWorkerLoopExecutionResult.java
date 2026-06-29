/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - SyncWorkerLoopExecutionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * worker loop 单条 execution 处理摘要。
 *
 * <p>该对象面向运维接口、调度指标和后续 task-management receipt 使用。
 * 它只保留低敏控制面事实：taskId、executionId、计划状态、派发状态和问题码；
 * 不返回字段清单、对象定位、过滤条件、SQL、连接信息、checkpoint 原始值、失败样本或远端响应正文。</p>
 *
 * @param taskId 同步任务 ID，用于调用方关联任务状态机
 * @param executionId 本次执行记录 ID，用于调用方追踪 execution 生命周期
 * @param claimed 是否已经完成租约认领
 * @param dispatched 是否已经调用 datasource-management run-once
 * @param completed 是否已经根据 run-once 结果回写 complete
 * @param failed 是否已经根据本地校验、远端结果或异常回写 fail
 * @param planStatus claim 返回的低敏执行计划状态，例如 READY_TO_RUN、BLOCKED
 * @param dispatchStatus run-once 派发摘要状态，例如 DISPATCHED_AND_COMPLETED、FAILED_BEFORE_REMOTE_CALL
 * @param outcome 面向运维人员的低敏结果分类，例如 COMPLETED、FAILED、NO_TEMPLATE、DISPATCH_EXCEPTION
 * @param issueCodes 低敏问题码集合，不包含异常堆栈、SQL、URL、字段值或样本数据
 */
public record SyncWorkerLoopExecutionResult(Long taskId,
                                            Long executionId,
                                            boolean claimed,
                                            boolean dispatched,
                                            boolean completed,
                                            boolean failed,
                                            String planStatus,
                                            String dispatchStatus,
                                            String outcome,
                                            List<String> issueCodes) {

    /**
     * 构造“不暴露可变集合”的结果对象。
     */
    public SyncWorkerLoopExecutionResult {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }
}
