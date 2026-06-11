/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledReceiptDelivery.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * 受控工具动作 dry-run receipt 投递结果。
 *
 * <p>这是 task-management 内部返回给调试/运维入口的轻量结果，不等同于 agent-runtime 的完整事件。
 * 它帮助调用方判断当前 dry-run 结果是否已经进入 timeline：如果 skipped，说明本次没有足够的
 * session/run 低敏上下文或配置关闭；如果 accepted=false，说明 agent-runtime 暂时未接收，但 dry-run
 * 状态机本身已经按配置 fail-open 继续推进。</p>
 *
 * @param attempted 是否实际尝试调用 agent-runtime。
 * @param accepted agent-runtime 是否接受该 receipt。
 * @param duplicate 是否命中 agent-runtime 幂等重复。
 * @param identityKey agent-runtime 返回的 runtime event 幂等键。
 * @param eventType agent-runtime 写入的 runtime event 类型。
 * @param message 面向运维的低敏投递说明。
 */
public record AgentToolActionControlledReceiptDelivery(
        boolean attempted,
        boolean accepted,
        boolean duplicate,
        String identityKey,
        String eventType,
        String message
) {

    public static AgentToolActionControlledReceiptDelivery skipped(String message) {
        return new AgentToolActionControlledReceiptDelivery(false, false, false, null, null, message);
    }

    public static AgentToolActionControlledReceiptDelivery failedOpen(String message) {
        return new AgentToolActionControlledReceiptDelivery(true, false, false, null, null, message);
    }
}
