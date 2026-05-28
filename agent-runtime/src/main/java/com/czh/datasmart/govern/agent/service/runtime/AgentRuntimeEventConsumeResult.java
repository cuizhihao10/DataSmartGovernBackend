/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumeResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Agent runtime event 消费结果。
 *
 * <p>Kafka listener 本身只需要知道“是否抛异常”，但业务层需要更细的结果：
 * - accepted：事件被首次接收并写入投影；
 * - duplicate：事件已经处理过，本次属于 Kafka 重复投递；
 * - rejected：消息格式或契约不满足要求，不能进入控制面投影。</p>
 *
 * <p>把结果对象显式返回，单元测试可以不启动 Kafka 容器就验证消费语义；
 * 后续接入指标时，也可以直接按该结果分类打点。</p>
 */
public record AgentRuntimeEventConsumeResult(
        boolean accepted,
        boolean duplicate,
        boolean rejected,
        String identityKey,
        String reason
) {

    public static AgentRuntimeEventConsumeResult accepted(String identityKey) {
        return new AgentRuntimeEventConsumeResult(true, false, false, identityKey, "ACCEPTED");
    }

    public static AgentRuntimeEventConsumeResult duplicate(String identityKey) {
        return new AgentRuntimeEventConsumeResult(false, true, false, identityKey, "DUPLICATE");
    }

    public static AgentRuntimeEventConsumeResult rejected(String reason) {
        return new AgentRuntimeEventConsumeResult(false, false, true, null, reason);
    }
}
