/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 受控命令 worker 回执接收结果。
 *
 * <p>响应保持非常克制：内部 worker 只需要知道 agent-runtime 是否接受了回执、是否命中幂等重复、
 * 对应的 runtime event 类型和 identityKey。完整 timeline、低敏索引和审计视图仍然通过专用查询接口读取。
 * 这样可以避免“写回接口顺手变成查询接口”，也避免在 worker 回调响应里泄露过多治理细节。</p>
 *
 * @param accepted true 表示请求合同通过校验，并且 agent-runtime 已经尝试写入 runtime event projection。
 * @param duplicate true 表示 identityKey 已存在，本次属于 worker 重试造成的幂等重复回写。
 * @param identityKey agent-runtime 用于 runtime event projection 去重的稳定键。
 * @param eventType 写入的 runtime event 类型，方便 worker 日志按事件类型聚合排障。
 * @param outcome 低敏执行结果摘要；与请求中的 outcome 归一化后保持一致。
 * @param sideEffectExecuted true 表示回执声明真实副作用已发生；用于 worker 自检与审计对账。
 * @param message 面向内部调用方的低敏处理说明。
 */
public record AgentToolActionCommandWorkerReceiptResponse(
        boolean accepted,
        boolean duplicate,
        String identityKey,
        String eventType,
        String outcome,
        boolean sideEffectExecuted,
        String message
) {
}
