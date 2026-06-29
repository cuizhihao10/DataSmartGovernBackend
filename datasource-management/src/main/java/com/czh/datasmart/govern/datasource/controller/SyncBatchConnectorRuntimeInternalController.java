/**
 * @Author : Cui
 * @Date: 2026/06/29 12:05
 * @Description DataSmart Govern Backend - SyncBatchConnectorRuntimeInternalController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchConnectorRuntimeRunOnceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 批量连接器运行时内部控制器。
 *
 * <p>该 Controller 暴露 datasource-management 面向 data-sync 的最小执行器入口：
 * {@code POST /internal/sync-batch-runs/run-once}。它只允许可信服务账号调用，并且只返回低敏执行摘要。</p>
 *
 * <p>路由职责边界：</p>
 * <p>1. Controller 负责 internal 服务账号入口校验和统一响应包装；</p>
 * <p>2. {@link SyncBatchConnectorRuntimeRunOnceService} 负责单批 read/write 编排；</p>
 * <p>3. data-sync 负责根据响应回写自己的 progress、checkpoint、complete 或 fail。</p>
 *
 * <p>为什么只提供 internal 路由：</p>
 * <p>run-once 会触发真实数据读取和写入，属于有副作用操作。它不能像 capability-snapshot 一样提供公开低敏查询入口，
 * 必须先经过 data-sync 的任务状态机、租约、模板校验、权限和后续审批策略。</p>
 */
@RestController
@RequestMapping("/internal/sync-batch-runs")
@RequiredArgsConstructor
public class SyncBatchConnectorRuntimeInternalController {

    /**
     * 允许调用 run-once 的服务。
     *
     * <p>当前只放行 data-sync，因为它是同步 execution 的控制面所有者。
     * task-management、agent-runtime 或前端都不应直接越过 data-sync 调用真实读写入口。</p>
     */
    private static final Set<String> TRUSTED_SOURCE_SERVICES = Set.of("data-sync");

    /**
     * internal 路由要求调用方声明服务账号身份。
     */
    private static final String TRUSTED_ACTOR_ROLE = "SERVICE_ACCOUNT";

    /**
     * 单批执行服务。
     */
    private final SyncBatchConnectorRuntimeRunOnceService runOnceService;

    /**
     * 执行一次受控批量读写。
     *
     * @param request 内部 run-once 请求，包含执行计划、字段清单、checkpoint 起点和累计统计。
     * @param sourceService 调用方服务名，必须是 data-sync。
     * @param actorRole 调用方角色，必须是 SERVICE_ACCOUNT。
     * @return 低敏执行摘要，不包含真实行数据、SQL、连接串、凭据或 checkpoint 原始值。
     */
    @PostMapping("/run-once")
    public ResponseEntity<ApiResponse<SyncBatchRunOnceInternalResponse>> runOnce(
            @Valid @RequestBody SyncBatchRunOnceInternalRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole) {
        if (!isTrustedInternalCaller(sourceService, actorRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    HttpStatus.FORBIDDEN.value(),
                    "批量连接器 run-once internal 路由仅允许 data-sync 服务账号调用"
            ));
        }
        SyncBatchRunOnceInternalResponse response = runOnceService.runOnce(request);
        return ResponseEntity.ok(ApiResponse.success("批量连接器 run-once 执行完成", response));
    }

    /**
     * 校验服务间调用身份。
     *
     * <p>这只是当前阶段的最小 fail-closed 保护。生产环境应继续升级为 gateway HMAC、mTLS、服务网格身份或 permission-admin
     * 机器账号策略，并把 traceId、tenantId、actorId 纳入审计。</p>
     */
    private boolean isTrustedInternalCaller(String sourceService, String actorRole) {
        return TRUSTED_SOURCE_SERVICES.stream().anyMatch(service -> service.equalsIgnoreCase(trim(sourceService)))
                && TRUSTED_ACTOR_ROLE.equalsIgnoreCase(trim(actorRole));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
