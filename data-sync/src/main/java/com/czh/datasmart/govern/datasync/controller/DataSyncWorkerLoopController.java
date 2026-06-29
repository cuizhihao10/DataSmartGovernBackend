/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunResult;
import com.czh.datasmart.govern.datasync.service.DataSyncWorkerLoopService;
import com.czh.datasmart.govern.datasync.service.support.DataSyncExecutorServiceAccountSignatureSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * data-sync worker loop 运维与 internal 触发入口。
 *
 * <p>该控制器暴露两个等价路径：</p>
 * <p>1. {@code /sync-workers/run-once}：便于本地、运维台或测试环境手动触发一轮 worker loop；</p>
 * <p>2. {@code /internal/sync-workers/run-once}：便于未来调度中心、独立 worker 服务或平台内部控制面调用。</p>
 *
 * <p>为什么这里仍然校验执行器服务账号签名：</p>
 * <p>worker loop 会真实认领 execution 并可能触发源端读取与目标端写入，它不是普通查询接口。
 * 即使当前本地默认关闭签名校验，生产环境也应开启 HMAC、mTLS、OIDC service account 或 service mesh 身份，
 * 避免外部客户端伪造请求触发数据搬运。</p>
 */
@RestController
@RequestMapping({"/sync-workers", "/internal/sync-workers"})
@RequiredArgsConstructor
public class DataSyncWorkerLoopController {

    private final DataSyncWorkerLoopService workerLoopService;
    private final DataSyncExecutorServiceAccountSignatureSupport signatureSupport;

    /**
     * 手动或 internal 触发一轮 worker loop。
     *
     * <p>该接口的返回值只包含低敏调度摘要，不返回字段映射、SQL、checkpoint 原始值、连接信息或远端响应正文。
     * 如果本轮没有可认领任务，返回成功响应并说明队列已空，而不是抛业务异常；这方便调度中心把“空队列”视为正常心跳。</p>
     */
    @PostMapping("/run-once")
    public PlatformApiResponse<SyncWorkerLoopRunResult> runOnce(
            @RequestBody(required = false) SyncWorkerLoopRunRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "WORKER_LOOP_RUN_ONCE");
        SyncActorContext actorContext = new SyncActorContext(tenantId, actorId, actorRole, traceId);
        return PlatformApiResponse.success("data-sync worker loop 单轮执行完成",
                workerLoopService.runOnce(request, actorContext), traceId);
    }
}
