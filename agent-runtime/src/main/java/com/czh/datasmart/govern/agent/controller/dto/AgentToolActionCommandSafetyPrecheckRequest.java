/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentToolActionCommandSafetyPrecheckRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具动作命令安全预检请求。
 *
 * <p>该请求面向“模型、MCP tools/call、A2A action 或未来 OpenClaw-style runner 提出要运行一条命令”
 * 的场景。它不是执行请求，也不是 outbox 写入请求：服务端只会读取命令和路径做安全判定，不会启动进程、
 * 不会写文件、不会联网、不会创建审批单，也不会投递 worker。</p>
 *
 * <p>为什么仍然允许请求携带 commandLine：safe-cmd 策略必须检查命令本身是否包含危险片段、联网行为、
 * 写入行为或未知命令。为了避免泄露，响应对象不会回显 commandLine，也不会把真实路径值返回给前端。</p>
 *
 * @param tenantId 调用方声明的租户 ID。服务端会结合可信 Header 做范围收口，防止跨租户命令预检。
 * @param projectId 调用方声明的项目 ID。显式 PROJECT 数据范围下必须落在 authorizedProjectIds 内。
 * @param actorId 调用方声明的 actor ID。仅用于低敏关联，不作为最终认证事实。
 * @param requestId Agent 请求 ID，用于把一次自然语言请求、工具计划和命令预检串起来。
 * @param runId Agent run ID，用于后续和 execution graph、outbox、worker receipt 关联。
 * @param sessionId Agent session ID，用于工作区、短期状态和审计链路关联。
 * @param clientRequestId 调用方幂等请求 ID。当前只做低敏关联，真实幂等仍应由 outbox/worker 控制。
 * @param commandSource 命令来源，例如 MODEL_TOOL_CALL、MCP_TOOLS_CALL、A2A_ACTION、HUMAN_REQUEST。
 * @param commandLine 原始命令行。只用于服务端安全判定，响应、日志和投影都不应回显。
 * @param executionMode 调用方期望的执行模式，例如 READ_ONLY、WRITE、NETWORK、EXEC。该字段仅辅助风险归类。
 * @param workspaceRoot Agent 工作区根路径。安全预检需要它判断 workingDirectory/referencedPaths 是否越界。
 * @param workingDirectory 命令计划运行目录。必须位于 workspaceRoot 内，响应只返回路径分类，不返回真实值。
 * @param referencedPaths 命令显式涉及的路径列表，例如输入文件、输出文件、脚本文件。响应不会回显路径值。
 * @param writeRequested 调用方是否声明该命令会写文件、生成 artifact 或改变状态。
 * @param networkRequested 调用方是否声明该命令需要联网、下载依赖或访问远端资源。
 * @param approvalConfirmed 调用方是否声明已经完成 Human-in-the-loop。注意：这不是最终审批事实，只是预检输入。
 * @param approvalFactId 审批事实引用。预检只检查引用形态安全，真实 worker 仍必须回查 permission-admin。
 * @param timeoutSeconds 请求的命令超时时间，单位秒。服务端会按配置裁剪为 normalizedTimeoutSeconds。
 * @param outputByteLimitBytes 请求的 stdout/stderr 输出字节上限。服务端会按配置裁剪。
 */
public record AgentToolActionCommandSafetyPrecheckRequest(
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        String clientRequestId,
        String commandSource,
        String commandLine,
        String executionMode,
        String workspaceRoot,
        String workingDirectory,
        List<String> referencedPaths,
        Boolean writeRequested,
        Boolean networkRequested,
        Boolean approvalConfirmed,
        String approvalFactId,
        Integer timeoutSeconds,
        Integer outputByteLimitBytes
) {
}
