/**
 * @Author : Cui
 * @Date: 2026/07/31 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionFailureView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 面向用户和 Agent 失败恢复链路的低敏工具失败详情。
 *
 * <p>错误码只适合程序分类，不能单独作为用户提示。本视图同时提供工具节点、人话原因、业务问题项和
 * 可执行建议，让前端无需理解每个微服务的内部异常结构，也让失败事实可以稳定进入后续模型诊断。</p>
 */
public record AgentToolExecutionFailureView(
        String auditId,
        String toolCode,
        String errorCode,
        String message,
        String outputSummary,
        List<String> details,
        List<String> suggestions) {
}
