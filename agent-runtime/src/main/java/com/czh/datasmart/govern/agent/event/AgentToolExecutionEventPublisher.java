/**
 * @Author : Cui
 * @Date: 2026/05/28 00:58
 * @Description DataSmart Govern Backend - AgentToolExecutionEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;

/**
 * Agent 工具执行事件发布端口。
 *
 * <p>这是一个典型的“端口/适配器”边界：业务服务只表达“工具审计状态已经变化，需要对外发布事实”，
 * 但并不关心事实最终写入 Kafka、数据库 outbox、WebSocket、审计中心还是测试内存集合。</p>
 *
 * <p>为什么要先抽接口，而不是直接在 {@code AgentToolExecutionAuditService} 中注入 KafkaTemplate：
 * 1. 降低耦合：状态机推进属于核心业务，Kafka 只是当前阶段的一种基础设施实现；
 * 2. 便于测试：单元测试可以注入收集型 publisher，断言状态事件是否按顺序产生；
 * 3. 便于演进：商业化版本很可能升级为“本地事务 + outbox 表 + 后台投递器”，以保证状态落库和事件发布一致；
 * 4. 便于多通道：同一事件未来可能同时进入 Kafka、审计中心、指标系统和实时 UI 推送链路。</p>
 */
public interface AgentToolExecutionEventPublisher {

    /**
     * 发布工具执行状态变更事件。
     *
     * @param previousState 状态变更前的状态；创建初始计划事件时可能为空，表示该记录刚刚进入审计链路。
     * @param record 已完成状态变更后的审计记录；发布方应只读取该对象，不应该继续修改业务状态。
     */
    void publishStateChanged(AgentToolExecutionState previousState, AgentToolExecutionAuditRecord record);
}
