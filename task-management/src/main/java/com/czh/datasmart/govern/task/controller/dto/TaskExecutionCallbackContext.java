/**
 * @Author : Cui
 * @Date: 2026/05/07 00:18
 * @Description DataSmart Govern Backend - TaskExecutionCallbackContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

/**
 * 执行器回调上下文。
 *
 * <p>该对象不是普通用户操作上下文，而是“某个 worker 正在回写某次执行结果”的技术上下文。
 * task-management 的执行器回调入口，例如进度上报、完成、失败、延迟回队列，都不能只相信 taskId。
 * 在真实生产环境中，taskId 很容易被日志、前端页面或排障工具暴露；如果回调只看 taskId，
 * 错误脚本或恶意请求就可能直接把别人的任务改成 SUCCESS/FAILED。
 *
 * <p>因此回调必须同时携带：
 * executorId：证明是哪一个执行器实例在回写；
 * runId：证明回写的是哪一次执行尝试；
 * idempotencyKey：证明重复请求属于同一次业务回调，而不是新的状态推进；
 * actorContext：证明 HTTP 调用方具备 SERVICE_ACCOUNT/OPERATOR 等执行器级角色。
 */
public record TaskExecutionCallbackContext(
        Long runId,
        String executorId,
        String idempotencyKey,
        TaskActorContext actorContext
) {
}
