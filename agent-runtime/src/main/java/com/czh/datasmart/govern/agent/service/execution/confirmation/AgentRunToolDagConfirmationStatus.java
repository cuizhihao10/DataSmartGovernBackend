/**
 * @Author : Cui
 * @Date: 2026/06/01 14:21
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

/**
 * DAG selected-node 确认记录状态。
 *
 * <p>当前只落地 CONFIRMED，是为了先把“确认事实”持久化，不急着再造一套执行状态机。
 * 后续如果接入审批撤销、执行前策略版本变化、确认过期、管理员作废等能力，可以在该枚举上扩展，
 * 但真正的 command 投递状态仍应继续由 command outbox 管理。</p>
 */
public enum AgentRunToolDagConfirmationStatus {

    /**
     * 调用方已经显式确认，且服务端在确认瞬间重新 dry-run、校验 fingerprint 后写入了 outbox。
     */
    CONFIRMED
}
