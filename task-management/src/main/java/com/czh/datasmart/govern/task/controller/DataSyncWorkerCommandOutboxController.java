/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandDeliveryService;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandOutboxDispatchService;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxClaimRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxClaimResult;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxDiagnosticsRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxDiagnosticsResult;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxDispatchBatchRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerOutboxDispatchBatchResult;
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
 * <p>本控制器提供 task-management 内部 DataSync outbox 的控制面能力：</p>
 * <p>1. {@code POST /internal/data-sync-worker-command-outbox/claim}：只领取命令，不调用下游；</p>
 * <p>2. {@code POST /internal/data-sync-worker-command-outbox/dispatch-batch}：领取并立即投递一批命令；</p>
 * <p>3. {@code GET /internal/data-sync-worker-command-outbox/diagnostics}：查询低敏状态统计和最近记录。</p>
 *
 * <p>安全边界：</p>
 * <p>这些接口属于内部控制面，不应该暴露给普通用户或公网。Controller 只负责 HTTP 参数解析和统一响应，
 * 不返回 payload_json、SQL、工具实参、连接串、凭据、样本数据、prompt、模型输出、错误正文或下游内部地址。
 * 生产环境应继续通过 gateway、服务网格、ACL、mTLS 或服务账号签名限制访问。</p>
 */
@RestController
@RequestMapping("/internal/data-sync-worker-command-outbox")
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxController {

    /**
     * outbox claim/diagnostics 服务，负责并发安全领取和低敏诊断。
     */
    private final DataSyncWorkerCommandOutboxDispatchService dispatchService;

    /**
     * outbox delivery 服务，负责领取后调用 datasource-management 幂等入口并记录 receipt。
     */
    private final DataSyncWorkerCommandDeliveryService deliveryService;

    /**
     * 领取待投递的 DataSync worker outbox 命令。
     *
     * <p>该路由有副作用：成功领取的记录会从 PENDING/DEFERRED 推进到 DISPATCHING，并递增 attemptCount。
     * 它适合给外部 dispatcher、补偿器或排障工具使用；调用方领取后必须继续投递或在失败时做恢复策略，
     * 否则命令会停留在 DISPATCHING，直到后续超时恢复逻辑介入。</p>
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
     * 领取并投递一批 DataSync worker outbox 命令。
     *
     * <p>这是当前阶段的手动 dispatcher 入口，完整执行：
     * claim PENDING/到期 DEFERRED -> 调用 datasource-management 内部幂等入口 ->
     * 按下游结果回写 SUCCEEDED/DEFERRED/FAILED receipt。
     * 后续后台 scheduler 或 Kafka publisher 应复用服务层逻辑，而不是重新实现一套投递状态机。</p>
     *
     * @param request 批量投递请求。
     * @return 本轮投递的低敏统计和单条结果。
     */
    @PostMapping("/dispatch-batch")
    public ResponseEntity<ApiResponse<DataSyncWorkerOutboxDispatchBatchResult>> dispatchBatch(
            @RequestBody DataSyncWorkerOutboxDispatchBatchRequest request) {
        DataSyncWorkerOutboxDispatchBatchResult result = deliveryService.dispatchBatch(request);
        return ResponseEntity.ok(ApiResponse.success("DataSync worker outbox 批量投递完成", result));
    }

    /**
     * 查询 DataSync worker outbox 低敏诊断信息。
     *
     * <p>该路由无业务副作用，用于运维判断命令是否堆积、是否等待重试、是否已经收到下游 receipt。
     * 返回结果不会包含 payload_json、SQL、工具实参、连接串、凭据、样本数据、prompt、模型输出或错误正文。</p>
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
