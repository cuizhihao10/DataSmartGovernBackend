/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandOutboxDispatchService;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxClaimRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxClaimResult;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxDiagnosticsRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxDiagnosticsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DataSync worker command outbox 内部控制器。
 *
 * <p>本控制器提供两个内部入口：</p>
 * <p>1. {@code POST /internal/data-sync-worker-command-outbox/claim}：给内部 dispatcher 领取待投递命令；</p>
 * <p>2. {@code GET /internal/data-sync-worker-command-outbox/diagnostics}：给运维控制面查看 outbox 状态和低敏排障信息。</p>
 *
 * <p>安全边界：</p>
 * <p>这些接口属于内部控制面，不应该直接暴露给普通用户或公网。生产环境应由 gateway、服务网格、内网 ACL 或服务账号权限
 * 限制访问。Controller 只负责解析 HTTP 参数和返回统一响应，真正的状态抢占、低敏过滤和诊断统计都在 Service 层完成。</p>
 */
@RestController
@RequestMapping("/internal/data-sync-worker-command-outbox")
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxController {

    private final DataSyncWorkerCommandOutboxDispatchService dispatchService;

    /**
     * 领取待投递的 DataSync worker outbox 命令。
     *
     * <p>该路由有副作用：成功领取的记录会从 PENDING/DEFERRED 推进到 DISPATCHING，并递增 attemptCount。
     * 因此它适合被后台 dispatcher、补偿任务或管理员手动排障工具调用，不适合作为普通查询接口。</p>
     *
     * @param request 领取请求，包含 executorId、租户/项目过滤、limit 和是否包含 DEFERRED。
     * @return 本次成功领取的低敏命令视图。
     */
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<DataSyncWorkerOutboxClaimResult>> claim(
            @RequestBody DataSyncWorkerOutboxClaimRequest request) {
        DataSyncWorkerOutboxClaimResult result = dispatchService.claimDispatchCandidates(request);
        return ResponseEntity.ok(ApiResponse.success("DataSync worker outbox 领取完成", result));
    }

    /**
     * 查询 DataSync worker outbox 低敏诊断信息。
     *
     * <p>该路由无业务副作用，用于运维判断命令是否堆积、是否等待重试、是否已经收到下游 receipt。
     * 返回结果不会包含 payload_json、SQL、工具实参、连接串、样本数据、prompt、模型输出或错误正文。</p>
     *
     * @param tenantId 可选租户过滤。
     * @param projectId 可选项目过滤。
     * @param taskId 可选 task-management 任务 ID。
     * @param commandId 可选 Agent command ID。
     * @param status 可选 outbox 状态。
     * @param limit 最近记录返回数量上限。
     * @return 低敏聚合统计和最近 outbox 记录。
     */
    @GetMapping("/diagnostics")
    public ResponseEntity<ApiResponse<DataSyncWorkerOutboxDiagnosticsResult>> diagnostics(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "commandId", required = false) String commandId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        DataSyncWorkerOutboxDiagnosticsRequest request = new DataSyncWorkerOutboxDiagnosticsRequest(
                tenantId,
                projectId,
                taskId,
                commandId,
                status,
                limit
        );
        DataSyncWorkerOutboxDiagnosticsResult result = dispatchService.diagnose(request);
        return ResponseEntity.ok(ApiResponse.success("DataSync worker outbox 诊断查询完成", result));
    }
}
