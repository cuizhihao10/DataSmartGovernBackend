/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentA2aAgentProviderView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * A2A AgentProvider 视图。
 *
 * <p>Provider 信息用于帮助外部 Agent 和管理员判断该 Agent 由谁维护、到哪里查看文档。
 * 这里使用公开文档占位地址，不能填写 Nacos、内网网关、容器主机名或任何客户环境内部地址。</p>
 *
 * @param url 提供方公开文档地址或产品主页
 * @param organization 提供方组织名称
 */
public record AgentA2aAgentProviderView(
        String url,
        String organization
) {
}
