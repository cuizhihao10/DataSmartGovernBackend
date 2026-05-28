/**
 * @Author : Cui
 * @Date: 2026/05/13 22:44
 * @Description DataSmart Govern Backend - AgentToolExecutionDecisionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工具执行人工决策请求。
 *
 * <p>该请求用于高风险工具计划的“确认/拒绝”入口。
 * 当前阶段还没有接入完整审批流引擎，因此先用一个轻量请求承载最关键的审计字段：
 * 谁做了决定、为什么做这个决定。
 *
 * <p>后续进入商业化审批流后，可以扩展为审批单 ID、审批节点 ID、审批意见附件、审批策略命中详情等字段。
 *
 * @param operatorId 做出人工决策的用户 ID。真实生产环境应优先来自网关鉴权上下文，这里先显式传入用于控制面骨架验证。
 * @param comment 决策说明。建议记录用户为什么同意或拒绝，便于审计复盘和风险追踪。
 */
public record AgentToolExecutionDecisionRequest(String operatorId,
                                                String comment) {
}
