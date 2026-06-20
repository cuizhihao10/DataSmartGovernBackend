/**
 * @Author : Cui
 * @Date: 2026/06/20 23:18
 * @Description DataSmart Govern Backend - DataSyncAgentInternalController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasource.controller.dto.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSyncAgentExecuteResponse;
import com.czh.datasmart.govern.datasource.service.agent.DataSyncAgentTaskExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * data-sync Agent 内部命令入口。
 *
 * <p>这个 Controller 是 task-management worker 调用 datasource-management 的专用入口，
 * 不是前端、普通用户或模型可以直接调用的公开 API。它的职责非常克制：</p>
 *
 * <p>1. 校验调用方是否来自受信的 task-management 服务账户上下文。</p>
 * <p>2. 接收 data-sync.execute 低敏命令。</p>
 * <p>3. 委托服务层完成幂等 receipt、同步任务创建和入队。</p>
 * <p>4. 使用 PlatformApiResponse 返回跨微服务通用响应，确保 task-management 的 RestClient 可以稳定解析。</p>
 *
 * <p>为什么不用普通 /sync/tasks 创建接口组合调用？</p>
 * <p>普通接口面向用户操作，缺少跨服务 commandId/idempotencyKey 语义。
 * Agent worker 场景必须把“创建任务 + 入队”视为一个幂等副作用，否则 HTTP 超时、Kafka 重放或 dispatcher 重试
 * 可能制造多个同步任务，最终造成重复搬运数据。</p>
 */
@RestController
@RequiredArgsConstructor
public class DataSyncAgentInternalController {

    /**
     * 当前唯一允许的上游服务。
     *
     * <p>后续如果引入专门的 agent-runtime 到 datasource 直连链路，应新增白名单和签名校验，
     * 而不是放宽为任意 SOURCE_SERVICE。</p>
     */
    private static final String TRUSTED_SOURCE_SERVICE = "task-management";

    /**
     * 内部副作用入口要求服务账号身份。
     *
     * <p>这里校验的是 Header 上下文，不读取请求体中的 actorId。
     * actorId 表示原始业务用户，SERVICE_ACCOUNT 表示 task-management worker 这个机器主体。</p>
     */
    private static final String TRUSTED_ACTOR_ROLE = "SERVICE_ACCOUNT";

    private final DataSyncAgentTaskExecutionService executionService;

    /**
     * 接收 task-management 发来的 data-sync.execute 内部命令。
     *
     * @param request 低敏执行命令，必须包含 commandId、idempotencyKey、tenantId、toolCode 和模板 ID。
     * @param sourceService 调用方服务名，必须为 task-management。
     * @param actorRole 调用方机器身份角色，必须为 SERVICE_ACCOUNT。
     * @param traceId 链路追踪 ID，会原样进入 PlatformApiResponse，便于跨服务排障。
     * @return 标准平台响应，data 中只包含低敏同步任务引用和幂等结果。
     */
    @PostMapping("/internal/data-sync/agent/tasks/execute")
    public ResponseEntity<PlatformApiResponse<DataSyncAgentExecuteResponse>> executeAgentDataSyncTask(
            @Valid @RequestBody DataSyncAgentExecuteRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        if (!isTrustedInternalCaller(sourceService, actorRole)) {
            PlatformApiResponse<DataSyncAgentExecuteResponse> response = PlatformApiResponse.error(
                    PlatformErrorCode.FORBIDDEN,
                    "data-sync Agent 内部执行入口只允许 task-management SERVICE_ACCOUNT 调用",
                    traceId
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        DataSyncAgentExecuteResponse data = executionService.execute(request);
        return ResponseEntity.ok(PlatformApiResponse.success("DataSync Agent 内部命令已接收并入队", data, traceId));
    }

    /**
     * 校验内部调用方。
     *
     * <p>当前项目还没有完整的服务间 mTLS、HMAC 或服务网格鉴权，所以这里先做最小的上下文校验。
     * 这不是最终安全方案，但比完全暴露内部入口更安全，也为后续接入 gateway 签名和服务账号证书保留了清晰位置。</p>
     */
    private boolean isTrustedInternalCaller(String sourceService, String actorRole) {
        return TRUSTED_SOURCE_SERVICE.equalsIgnoreCase(trim(sourceService))
                && TRUSTED_ACTOR_ROLE.equalsIgnoreCase(trim(actorRole));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
