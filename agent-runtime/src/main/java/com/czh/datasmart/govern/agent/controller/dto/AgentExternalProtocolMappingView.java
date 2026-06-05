/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolMappingView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * DataSmart 内部概念到外部 Agent 协议概念的映射说明。
 *
 * <p>这个视图服务于学习和架构治理：它把内部的 Skill Manifest、Tool Control Plane、Handoff DAG、
 * Runtime Events、Model Gateway 等概念，解释成 MCP/A2A 中更通用的协议概念。这样后续实现真实协议
 * 时不会把“工具调用”“Agent 协作”“事件回放”“模型路由”混在一个服务里。</p>
 *
 * @param internalConcept DataSmart 内部概念
 * @param mcpMapping 对应的 MCP 概念或能力
 * @param a2aMapping 对应的 A2A 概念或能力
 * @param designIntent 设计意图，说明为什么这样映射更适合商业化产品
 * @param executionBoundary 执行边界，说明哪些事情仍必须回到 Java/Python 受控链路完成
 */
public record AgentExternalProtocolMappingView(
        String internalConcept,
        String mcpMapping,
        String a2aMapping,
        String designIntent,
        String executionBoundary
) {
}
