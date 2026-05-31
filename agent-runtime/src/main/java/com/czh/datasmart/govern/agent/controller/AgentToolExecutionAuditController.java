/**
 * @Author : Cui
 * @Date: 2026/05/13 23:55
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandPlanView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolPlanDagView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.service.AgentSessionService;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionResultQueryService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunToolAutoExecutionService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunAsyncTaskCommandPlanningService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunToolPlanDagService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunToolExecutionPolicyService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final AgentToolExecutionResultQueryService resultQueryService;
    private final AgentRunToolExecutionPolicyService executionPolicyService;
    private final AgentRunToolAutoExecutionService autoExecutionService;
    private final AgentRunAsyncTaskCommandPlanningService asyncTaskCommandPlanningService;
    private final AgentRunToolPlanDagService toolPlanDagService;

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
     * 查询某次 Agent Run 的工具执行策略预检。
     *
     * <p>该接口是“自动执行真实工具”之前的安全观察窗：它只读取当前 Run、工具审计、参数校验和执行模式，
     * 然后告诉调用方每个工具现在应该自动执行、等待审批、补齐参数、交给异步执行器、等待结果，还是阻断整个 Run。</p>
     *
     * <p>为什么单独提供这个路由，而不是让前端或 Python Runtime 自己解析工具审计列表：
     * 1. 工具审计状态只描述事实，不直接描述下一步策略；
     * 2. 自动执行、人工审批、失败重试、异步执行等规则应该由 Java 控制面统一解释，避免多端逻辑漂移；
     * 3. 该接口没有副作用，可以被前端频繁刷新，也可以被未来自动执行器作为执行前只读 preflight；
     * 4. 后续接入 permission-admin、租户级策略、工具健康度、队列容量后，只需要扩展策略服务，不需要改调用方判断。</p>
     *
     * <p>路由含义：
     * - sessionId：限定 Agent 会话和工作空间，避免跨会话读取策略；
     * - runId：限定本次编排尝试，避免把历史工具计划误判为当前可执行；
     * - execution-policy：强调返回的是“策略预检”，不是执行结果，也不会触发工具调用。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/execution-policy")
    public PlatformApiResponse<AgentRunToolExecutionPolicyView> getRunToolExecutionPolicy(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(executionPolicyService.inspectRunPolicy(sessionId, runId), traceId);
    }

    /**
     * 查询某次 Agent Run 的 ToolPlan DAG 预检。
     *
     * <p>该接口是多工具 Agent 编排能力的第一阶段，只读、不执行、不审批、不投递 Kafka。
     * 它把当前 Run 的工具审计记录解释成 DAG：节点、依赖边、并行组、失败策略、结果别名、拓扑顺序和 ready 节点。
     * 后续 Python Runtime、前端和 Java worker 都可以基于这份视图理解“下一批哪些工具可以执行、哪些必须等待前置结果”。</p>
     *
     * <p>兼容策略：
     * - 如果 Python ToolPlan 通过 governanceHints 提供 `planNodeId/dependsOn/parallelGroup/failurePolicy/resultAlias`，
     *   Java 会使用显式依赖构建 DAG；
     * - 如果没有显式依赖，Java 会按旧 toolPlans 列表顺序生成保守串行边，避免旧版本 Python Runtime 计划被误判为全并行。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/dag-plan")
    public PlatformApiResponse<AgentRunToolPlanDagView> getRunToolPlanDag(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(toolPlanDagService.inspectRunToolPlanDag(sessionId, runId), traceId);
    }

    /**
     * 受控自动执行某次 Run 中安全的同步工具候选。
     *
     * <p>该接口是 `execution-policy` 之后的第一阶段执行闭环，但它不是“模型想做什么就全部执行”的危险入口。
     * 服务端会重新读取 policy，并额外要求工具满足 LOW 风险、只读、幂等、无需审批、参数完整、Run 非终态等条件。
     * 因此调用方传入 auditIds 只能缩小执行范围，不能把高风险或写操作强行变成自动执行。</p>
     *
     * <p>典型使用方式：
     * 1. 前端先调用 execution-policy 展示候选和阻断原因；
     * 2. 用户或 Python Runtime 调用本接口，传入 `dryRun=true` 先确认本批次会处理哪些工具；
     * 3. 确认后再调用 `dryRun=false`，服务端按 `maxExecutions` 和配置上限执行安全候选；
     * 4. 执行完成后，调用批量 result 查询或二轮模型回填链路读取结果。</p>
     *
     * <p>当前版本只处理同步短耗时工具。`ASYNC_TASK` 不会在这里执行，后续应转成 task-management 任务、
     * Kafka command 或专用 worker，避免长耗时任务阻塞 HTTP 请求线程。</p>
     */
    @PostMapping("/{sessionId}/runs/{runId}/tool-executions/auto-execute-sync")
    public PlatformApiResponse<AgentRunToolAutoExecutionResponse> autoExecuteSyncToolExecutions(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestBody(required = false) AgentRunToolAutoExecutionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(autoExecutionService.executeEligibleSyncTools(sessionId, runId, request, traceId), traceId);
    }

    /**
     * 查询某次 Run 的 ASYNC_TASK 异步命令草案。
     *
     * <p>该接口仍然是只读 preflight，不会向 Kafka 发送消息，也不会创建 task-management 任务。
     * 它把异步工具从“只能显示 WAITING_ASYNC_EXECUTOR”推进到“已经有稳定 command envelope 草案”：
     * 包括 commandId、幂等键、topic、消费者模块、租户/项目/工作空间、目标服务、参数名快照和阻断原因。</p>
     *
     * <p>为什么不直接让 agent-runtime 调用 `/tasks` 创建任务：
     * 1. 长耗时工具需要消息队列、outbox、消费者去重、失败重放和死信，而不是同步 HTTP 嵌套调用；
     * 2. task-management 应作为统一运营底座，管理队列、租约、心跳、重试、暂停、恢复和管理员干预；
     * 3. 在真实投递前先固定 command 草案，能避免后续跨服务契约频繁推翻重构。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/async-command-plans")
    public PlatformApiResponse<AgentRunAsyncTaskCommandPlanView> getRunAsyncTaskCommandPlans(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(asyncTaskCommandPlanningService.planRunAsyncTaskCommands(sessionId, runId), traceId);
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

    /**
     * 批量查询某次 Run 的所有工具执行结果快照。
     *
     * <p>该接口面向 Python AI Runtime 的二轮推理准备阶段和前端审计页。相比逐个 auditId 查询，批量接口
     * 可以避免多工具 Agent 产生 N+1 HTTP 请求，从而降低延迟、连接数和重试复杂度。</p>
     *
     * <p>接口仍然只读：不会审批、不会执行工具、不会改变任何状态。调用方应把返回结果作为“当前事实快照”，
     * 再根据 state 判断是等待审批、等待执行、进入二轮推理，还是提示人工介入。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/results")
    public PlatformApiResponse<List<AgentToolExecutionResultView>> listRunToolExecutionResults(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(resultQueryService.listRunToolExecutionResults(sessionId, runId), traceId);
    }
}
