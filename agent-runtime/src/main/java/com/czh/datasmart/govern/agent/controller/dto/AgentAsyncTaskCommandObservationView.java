/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandObservationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * 统一生命周期图读取单条异步命令时使用的低敏观察投影。
 *
 * <p>该 DTO 不返回 payload、幂等键、目标地址、topic、actor、租户、项目或错误正文。对象级授权由 URL 中的
 * sessionId 先行裁决；commandId 如果不属于同一 session/run，只返回 found=false，避免泄露其他会话事实。</p>
 *
 * @param found 精确身份是否存在
 * @param status outbox 当前有限状态
 * @param attemptCount 已发生投递尝试次数
 * @param publishedAt outbox 标记投递成功的时间；不代表消费者已经处理
 * @param updatedAt 最近状态更新时间
 * @param sourceStatus 固定低敏来源码
 */
public record AgentAsyncTaskCommandObservationView(
        boolean found,
        String status,
        Integer attemptCount,
        Instant publishedAt,
        Instant updatedAt,
        String sourceStatus) {

    public static AgentAsyncTaskCommandObservationView missing() {
        return new AgentAsyncTaskCommandObservationView(
                false, null, null, null, null, "COMMAND_NOT_FOUND");
    }
}
