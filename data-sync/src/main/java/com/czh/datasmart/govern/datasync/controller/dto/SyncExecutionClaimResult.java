/**
 * @Author : Cui
 * @Date: 2026/05/08 21:52
 * @Description DataSmart Govern Backend - SyncExecutionClaimResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;

/**
 * 同步执行认领结果。
 *
 * @param claimed 是否认领成功
 * @param message 结果说明
 * @param execution 被认领的执行记录
 * @param task 对应同步任务定义
 * @param workerPlan claim 成功后的低敏执行计划。
 *                   <p>它只描述同步模式、连接器类型、checkpoint 建议、配置块是否存在、执行前阻断项和 worker 下一步动作，
 *                   不返回字段映射正文、过滤条件正文、SQL、样本数据、连接串、账号、密钥或内部 endpoint。
 *                   这样 worker 可以先按统一协议判断“能不能跑”，真正读取连接凭据和配置正文则留给受控 connector runtime。</p>
 */
public record SyncExecutionClaimResult(
        boolean claimed,
        String message,
        SyncExecution execution,
        SyncTask task,
        SyncWorkerExecutionPlanView workerPlan
) {
}
