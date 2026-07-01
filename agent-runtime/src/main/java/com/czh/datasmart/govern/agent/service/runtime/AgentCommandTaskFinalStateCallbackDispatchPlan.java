/**
 * @Author : Cui
 * @Date: 2026/07/02 01:30
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatchPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * task-management 终态回调的一次不可变投递计划。
 *
 * <p>该对象从 {@link AgentCommandTaskFinalStateCallbackDispatchService} 中拆出，专门承载“是否允许投递、
 * 目标操作、幂等键、低敏 body 和问题/建议”的值语义。服务类继续负责对账、HTTP 调用和响应映射，
 * 计划对象只负责不可变状态派生，避免网络副作用与数据转换继续堆叠在同一个文件。</p>
 *
 * <p>所有 {@code with...} 方法都会返回新对象而不是修改旧计划。这个设计便于 dry-run、下游拒绝和
 * 网络异常分支保留原始判定事实，也防止多个请求并发时共享可变集合。</p>
 *
 * @param dispatchable       是否具备投递所需的完整对账事实
 * @param deliveryStatus     当前投递阶段的稳定状态码
 * @param taskId             task-management 任务标识
 * @param taskRunId          本次任务运行标识
 * @param executorId         持有当前运行租约的执行器标识
 * @param callbackStatus     对账服务建议的终态或可见性状态
 * @param targetOperation    task-management 回调操作名，不包含内部 endpoint
 * @param idempotencyKey     下游用于拒绝重复副作用的幂等键
 * @param body               已裁剪的回调 DTO，不携带命令、输出正文或 artifact 正文
 * @param issueCodes         低基数问题码
 * @param recommendedActions 面向运维与控制面的恢复建议
 */
record CallbackDispatchPlan(boolean dispatchable,
                            String deliveryStatus,
                            Long taskId,
                            Long taskRunId,
                            String executorId,
                            String callbackStatus,
                            String targetOperation,
                            String idempotencyKey,
                            Object body,
                            List<String> issueCodes,
                            List<String> recommendedActions) {

    /**
     * 构造不可投递计划。
     *
     * <p>缺少回执、幂等键或回调关联事实时必须 fail-closed，不能猜测 taskId 或 executorId。</p>
     */
    static CallbackDispatchPlan skipped(String status, List<String> issues, List<String> actions) {
        return new CallbackDispatchPlan(
                false, status, null, null, null, null, null, null, null,
                List.copyOf(issues), List.copyOf(actions)
        );
    }

    /**
     * 把可投递计划转换为 dry-run 结果。
     *
     * <p>计划内容保持不变，只更新状态并补充“尚未写入”的明确说明。</p>
     */
    CallbackDispatchPlan asDryRun() {
        return new CallbackDispatchPlan(
                true, "DRY_RUN_READY", taskId, taskRunId, executorId, callbackStatus,
                targetOperation, idempotencyKey, body, issueCodes,
                append(recommendedActions, "当前为 dry-run，未向 task-management 写入状态；确认无误后可设置 dryRun=false。")
        );
    }

    /** 返回只替换投递状态的新计划。 */
    CallbackDispatchPlan withStatus(String status) {
        return new CallbackDispatchPlan(
                dispatchable, status, taskId, taskRunId, executorId, callbackStatus,
                targetOperation, idempotencyKey, body, issueCodes, recommendedActions
        );
    }

    /** 返回追加一个低基数问题码的新计划。 */
    CallbackDispatchPlan withIssue(String issue) {
        return new CallbackDispatchPlan(
                dispatchable, deliveryStatus, taskId, taskRunId, executorId, callbackStatus,
                targetOperation, idempotencyKey, body, append(issueCodes, issue), recommendedActions
        );
    }

    /** 返回追加一个恢复建议的新计划。 */
    CallbackDispatchPlan withAction(String action) {
        return new CallbackDispatchPlan(
                dispatchable, deliveryStatus, taskId, taskRunId, executorId, callbackStatus,
                targetOperation, idempotencyKey, body, issueCodes, append(recommendedActions, action)
        );
    }

    private static List<String> append(List<String> values, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.add(value);
        return List.copyOf(copy);
    }
}
