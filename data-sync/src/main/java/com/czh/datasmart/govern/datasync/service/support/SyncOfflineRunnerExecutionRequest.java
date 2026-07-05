/**
 * @Author : Cui
 * @Date: 2026/07/05 14:52
 * @Description DataSmart Govern Backend - SyncOfflineRunnerExecutionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;

/**
 * 专用离线 Runner adapter 的执行请求。
 *
 * <p>这个请求对象是 data-sync 控制面与后续 DataX-style 专用 Runner 之间的“模块内 SPI 请求”。
 * 它和公开 REST 响应不同，可以携带 {@link SyncBatchRunnerBridgePlan} 这类内部桥接计划，但 adapter 仍必须遵守
 * 低敏原则：不得把 bridge plan 中的对象定位、字段映射、SQL 管理策略、checkpoint 摘要或异常详情直接写入普通日志、
 * Prometheus 高基数标签、前端响应或 task-management receipt。</p>
 *
 * <p>为什么要把上下文集中成一个 request，而不是让 adapter 自己按 templateId 回查：</p>
 * <p>1. worker loop 已经完成 claim、模板读取和合同生成，如果 adapter 重新回查，容易形成不一致快照；</p>
 * <p>2. data-sync 是 task/execution/checkpoint 的控制面所有者，adapter 只应该消费受控合同，不应该猜测状态机；</p>
 * <p>3. 后续接入外部 runner 时，可以把本对象转换为 HTTP、Kafka 或 gRPC 消息，而不影响 worker loop。</p>
 *
 * @param bridgePlan data-sync 内部桥接计划，可能包含对象定位和字段映射，只允许服务端受控链路使用。
 * @param runnerContract 低敏离线 Runner 作业合同，是 adapter 做能力判断和调度的主输入。
 * @param execution 当前 execution 事实，用于关联回调、进度和最终报告。
 * @param task 当前任务事实，用于读取调度、租户、项目和状态归属。
 * @param template 当前模板事实，用于 adapter 在受控环境中展开 reader/writer 配置。
 * @param workerPlan claim 阶段产生的低敏 worker 计划，可用于诊断和幂等校验。
 * @param actorContext 当前操作者或服务账号上下文，用于审计、权限和 trace 串联。
 */
public record SyncOfflineRunnerExecutionRequest(SyncBatchRunnerBridgePlan bridgePlan,
                                                SyncOfflineRunnerJobContract runnerContract,
                                                SyncExecution execution,
                                                SyncTask task,
                                                SyncTemplate template,
                                                SyncWorkerExecutionPlanView workerPlan,
                                                SyncActorContext actorContext) {
}
