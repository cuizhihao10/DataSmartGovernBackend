/**
 * @Author : Cui
 * @Date: 2026/05/13 23:55
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.service.AgentSessionService;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 工具执行审计控制器。
 *
 * <p>该控制器当前提供工具计划查询与第一版人工确认/拒绝入口。
 * 它仍不执行真实工具；确认/拒绝只负责推进控制面状态，真实执行会在后续工具适配器阶段补齐。
 */
@RestController
@RequestMapping({"/agent-runtime/sessions", "/api/agent/sessions"})
@RequiredArgsConstructor
public class AgentToolExecutionAuditController {

    private final AgentToolExecutionAuditService auditService;
    private final AgentSessionService sessionService;

    /**
     * 查询某次 Agent Run 的工具执行审计记录。
     *
     * <p>典型用途：
     * 1. 前端展示“本次 Agent 准备使用哪些工具”；
     * 2. 审计员复核高风险工具是否进入 WAITING_APPROVAL；
     * 3. 运维排查为什么某个工具没有真实执行。
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions")
    public PlatformApiResponse<List<AgentToolExecutionAuditView>> listRunToolExecutions(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(auditService.listByRun(sessionId, runId), traceId);
    }

    /**
     * 人工确认某个高风险工具计划。
     *
     * <p>路由含义：
     * - sessionId：限定 Agent 会话，避免跨会话审批；
     * - runId：限定本次运行，避免把上一次运行的工具计划误确认；
     * - auditId：定位具体工具计划，例如 `task.create` 或未来的 `datasource.export`。
     *
     * <p>该接口只解除审批等待，不直接执行工具。
     * 真正执行工具时仍应由后续工具执行器读取审计记录并推进到 EXECUTING/SUCCEEDED/FAILED。
     */
    @PatchMapping("/{sessionId}/runs/{runId}/tool-executions/{auditId}/approve")
    public PlatformApiResponse<AgentToolExecutionAuditView> approveToolExecution(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("auditId") String auditId,
            @RequestBody AgentToolExecutionDecisionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(sessionService.approveToolExecution(sessionId, runId, auditId, request), traceId);
    }

    /**
     * 人工拒绝某个高风险工具计划。
     *
     * <p>拒绝后工具计划进入 SKIPPED。
     * 这和 FAILED 不同：FAILED 表示工具执行失败，SKIPPED 表示用户或策略在执行前决定不执行。
     */
    @PatchMapping("/{sessionId}/runs/{runId}/tool-executions/{auditId}/reject")
    public PlatformApiResponse<AgentToolExecutionAuditView> rejectToolExecution(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("auditId") String auditId,
            @RequestBody AgentToolExecutionDecisionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(sessionService.rejectToolExecution(sessionId, runId, auditId, request), traceId);
    }

    /**
     * 执行某个已规划工具。
     *
     * <p>该入口只允许执行已经处于 PLANNED 的工具计划。
     * 如果工具仍在 WAITING_APPROVAL，说明高风险确认未完成，服务层会拒绝执行。
     *
     * <p>当前执行仍是同步 HTTP 调用，适合低风险、短耗时工具。
     * 后续对于长耗时工具，例如批量扫描、同步任务创建、数据导出，应切换为异步任务或 Kafka 命令事件。
     */
    @PatchMapping("/{sessionId}/runs/{runId}/tool-executions/{auditId}/execute")
    public PlatformApiResponse<AgentToolExecutionResultView> executeToolExecution(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("auditId") String auditId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(sessionService.executeToolExecution(sessionId, runId, auditId, traceId), traceId);
    }

    /**
     * 查询某个工具执行结果快照。
     *
     * <p>该入口是 Python AI Runtime 多步 Agent loop 与 Java 控制面的只读桥梁。
     * Python 不应根据本地模拟结果长期推理，而应在 Java 真实执行链路可用时，通过 sessionId/runId/auditId
     * 查询受控执行结果，再把安全摘要按 tool_call_id 回填给模型。</p>
     *
     * <p>该接口不会触发执行，也不会推进审批状态；它只返回当前事实，适合轮询、回调补偿、审计回放和
     * 前端刷新。真实生产环境中建议通过 gateway 保护该路由，只允许服务账号、项目成员或审计员访问。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/{auditId}/result")
    public PlatformApiResponse<AgentToolExecutionResultView> getToolExecutionResult(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("auditId") String auditId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(sessionService.getToolExecutionResult(sessionId, runId, auditId), traceId);
    }
}
