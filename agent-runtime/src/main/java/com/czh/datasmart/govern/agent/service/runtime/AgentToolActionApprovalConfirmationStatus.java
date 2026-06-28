/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * 工具动作审批确认事实状态。
 *
 * <p>该枚举服务于 `tool-action-confirmation:` 这一类 Host 控制面确认事实。它和 permission-admin 的通用审批单
 * 不是同一个概念：permission-admin 负责“谁有权审批、审批流程怎么走”，这里负责“某一次工具动作真实执行前，
 * 用户确认的 payloadReference 和服务端 payload 元数据是什么”。两者未来可以打通，但不应该混成一个表或一个服务。</p>
 */
public enum AgentToolActionApprovalConfirmationStatus {

    /**
     * 已确认。
     *
     * <p>表示确认人已经围绕服务端 `agent-payload:` 引用、payload body 可用性、大小、策略、tenant/project/actor/run/tool
     * 等低敏元数据做过确认。writer 只有看到 CONFIRMED 且未过期、作用域匹配的记录，才可以把确认事实当作 outbox 证据。</p>
     */
    CONFIRMED,

    /**
     * 已撤销。
     *
     * <p>当前阶段还没有撤销接口，但先保留状态位，方便后续接入审批中心、人工撤回、风险策略升级或安全事件处置时，
     * 不必修改 outbox verifier 的状态模型。</p>
     */
    REVOKED,

    /**
     * 已被新确认取代。
     *
     * <p>当同一个 proposal 或 payloadReference 因策略版本、payload 摘要、审批意见变化而重新确认时，
     * 旧确认可以进入 SUPERSEDED。当前内存实现暂不主动改状态，但 verifier 已按“只接受 CONFIRMED”设计。</p>
     */
    SUPERSEDED
}
