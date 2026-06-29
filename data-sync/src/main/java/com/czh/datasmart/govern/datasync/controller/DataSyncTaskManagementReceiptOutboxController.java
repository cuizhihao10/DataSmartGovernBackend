/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.TaskManagementReceiptOutboxDispatchRequest;
import com.czh.datasmart.govern.datasync.controller.dto.TaskManagementReceiptOutboxDispatchResult;
import com.czh.datasmart.govern.datasync.service.support.DataSyncExecutorServiceAccountSignatureSupport;
import com.czh.datasmart.govern.datasync.service.support.DataSyncTaskManagementReceiptOutboxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * task-management receipt outbox 运维与 internal 补偿入口。
 *
 * <p>该 Controller 只暴露“派发已到期 outbox”能力，不允许外部传入 receipt payload 或目标地址。
 * 真实 payload 必须来自 data-sync 本地 outbox 表，确保补偿动作可审计、可重试、可死信，而不是任意 HTTP 转发。</p>
 *
 * <p>生产环境应通过 gateway 内网路由、OIDC service account、HMAC、mTLS 或 service mesh 保护该入口。
 * 当前复用 data-sync 执行器服务账号签名组件，和 worker loop 手动触发保持一致。</p>
 */
@RestController
@RequestMapping({"/task-management-receipt-outbox", "/internal/task-management-receipt-outbox"})
@RequiredArgsConstructor
public class DataSyncTaskManagementReceiptOutboxController {

    private final DataSyncTaskManagementReceiptOutboxService outboxService;
    private final DataSyncExecutorServiceAccountSignatureSupport signatureSupport;

    /**
     * 手动派发已到期 receipt outbox。
     *
     * <p>返回结果只包含扫描、尝试、成功、重试、死信、跳过数量和低敏 issueCode。
     * 不返回 outbox payload、HTTP 响应、内部 URL、SQL、字段映射或样本数据。</p>
     */
    @PostMapping("/dispatch-due")
    public PlatformApiResponse<TaskManagementReceiptOutboxDispatchResult> dispatchDue(
            @RequestBody(required = false) TaskManagementReceiptOutboxDispatchRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "TASK_MANAGEMENT_RECEIPT_OUTBOX_DISPATCH_DUE");
        SyncActorContext actorContext = new SyncActorContext(tenantId, actorId, actorRole, traceId);
        Integer limit = request == null ? null : request.getLimit();
        TaskManagementReceiptOutboxDispatchResult result = outboxService.dispatchDue(limit, actorContext);
        return PlatformApiResponse.success("task-management receipt outbox due 记录派发完成", result, traceId);
    }
}
