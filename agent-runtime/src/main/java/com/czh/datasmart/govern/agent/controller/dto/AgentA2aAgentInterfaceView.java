/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentA2aAgentInterfaceView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * A2A AgentInterface 视图。
 *
 * <p>A2A 允许一个 Agent 同时暴露多种协议绑定，例如 HTTP+JSON、JSONRPC 或 gRPC。
 * DataSmart 当前只把 HTTP+JSON 作为公开草案，其余传输方式应等到 task lifecycle、streaming、push
 * notification 和鉴权策略稳定后再开放。</p>
 *
 * @param url 公开访问入口。当前使用示例公开域名，不暴露内部服务地址
 * @param protocolBinding 协议绑定类型，例如 HTTP+JSON
 * @param tenant 可选租户路由提示。公开卡片默认不暴露具体租户，保持为空
 * @param protocolVersion A2A 协议版本，例如 1.0
 */
public record AgentA2aAgentInterfaceView(
        String url,
        String protocolBinding,
        String tenant,
        String protocolVersion
) {
}
