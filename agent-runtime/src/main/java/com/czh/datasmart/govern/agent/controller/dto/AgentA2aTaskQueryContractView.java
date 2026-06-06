/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskQueryContractView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task 查询接口契约草案。
 *
 * <p>该对象解释未来只读 task 查询应该如何映射 A2A `GetTask`/`ListTasks`/`SubscribeToTask` 能力。
 * 当前仍是管理端 preview 路由，因此不使用真实 task id，不读取持久化事实，只展示路径、参数、权限和错误映射设计。</p>
 *
 * @param managementPath 当前 DataSmart 管理预览路径
 * @param protocolEquivalent 对应的 A2A 协议能力，例如 GetTask、ListTasks 或 SubscribeToTask
 * @param method HTTP 方法
 * @param requestParameters 当前 preview 支持的请求参数
 * @param responseShape 响应形态说明
 * @param authorizationPolicy 查询权限策略
 * @param historyPolicy historyLength、分页、sequence 和断线恢复策略
 * @param errorMappings 未来真实接口应映射的错误类别
 * @param currentBoundary 当前 preview 和真实 endpoint 的边界差异
 */
public record AgentA2aTaskQueryContractView(
        String managementPath,
        String protocolEquivalent,
        String method,
        List<String> requestParameters,
        String responseShape,
        List<String> authorizationPolicy,
        List<String> historyPolicy,
        List<String> errorMappings,
        List<String> currentBoundary
) {
}
