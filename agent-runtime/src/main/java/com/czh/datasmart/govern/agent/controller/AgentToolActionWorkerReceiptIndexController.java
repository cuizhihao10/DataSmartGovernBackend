/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionWorkerReceiptIndexQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionWorkerReceiptIndexQueryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * worker receipt 低敏索引只读控制器。
 *
 * <p>本 Controller 是 Agent Host 排障控制面的一个小而明确的入口：运维、审计或智能网关可以按 commandId
 * 查询“worker/dry-run 是否已经写回低敏 receipt”。它不是通用 runtime event 查询，也不是工具恢复执行入口。
 * 因此路由独立放在 `/tool-action-worker-receipts` 下，避免继续膨胀通用事件 Controller。</p>
 *
 * <p>业务边界：
 * 1. 只读查询，不执行工具；
 * 2. 不写 outbox，不派发 worker，不修改 checkpoint；
 * 3. 必须提供 commandId，禁止无界扫描；
 * 4. 服务层会继续用 gateway/permission-admin Header 做租户、项目、actor 范围收口。</p>
 *
 * <p>安全边界：响应字段由 {@link AgentToolActionWorkerReceiptIndexQueryService} 白名单裁剪，
 * 不返回 receipt message、payload body、工具参数、SQL、prompt、样本数据、模型输出、凭证、token 或内部 endpoint。
 * eventIdentityKey 也只会以短指纹形式出现。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-worker-receipts", "/api/agent/tool-action-worker-receipts"})
@RequiredArgsConstructor
public class AgentToolActionWorkerReceiptIndexController {

    private final AgentToolActionWorkerReceiptIndexQueryService receiptIndexQueryService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 按 commandId 查询 worker receipt 低敏索引。
     *
     * <p>路径使用 `/receipts` 而不是直接挂在 runtime events 下，是为了强调它查询的是专用 host fact index，
     * 不是原始事件流。调用方可以继续传 tenant/project/actor/run/session 缩小范围，但这些参数只能缩小可见性，
     * 不能扩大 Header 中的可信数据范围。</p>
     *
     * <p>典型使用方式：
     * - 智能网关恢复前，检查某个 command 是否已经有 worker/dry-run receipt；
     * - 管理台排障时，按 runId/sessionId/toolCode 进一步定位最新低敏结果；
     * - 审计台确认某个执行前阻断是来自 worker receipt，而不是调用方自报。</p>
     */
    @GetMapping("/receipts")
    public PlatformApiResponse<AgentToolActionWorkerReceiptIndexQueryResponse> queryReceipts(
            @RequestParam(value = "commandId", required = false) String commandId,
            @RequestParam(value = "toolCode", required = false) String toolCode,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                receiptIndexQueryService.queryReceipts(
                        commandId,
                        toolCode,
                        tenantId,
                        projectId,
                        actorId,
                        runId,
                        sessionId,
                        limit,
                        accessContext
                ),
                traceId
        );
    }
}
