/**
 * @Author : Cui
 * @Date: 2026/06/22 10:39
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerExecutionReceiptQueryResult;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerExecutionReceiptRecordRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerExecutionReceiptRecordResult;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerExecutionReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DataSync worker 执行回执内部控制器。
 *
 * <p>本控制器用于 datasource-management Runner 把真实执行阶段的低敏回执写回 task-management。
 * 它和 {@link DataSyncWorkerCommandOutboxController} 的职责不同：</p>
 * <p>1. outbox controller 管理“命令投递”；</p>
 * <p>2. execution receipt controller 管理“下游执行事实”；</p>
 * <p>3. 两者分离后，任务中心可以同时回答“命令有没有投递成功”和“投递后下游有没有真正执行”。</p>
 *
 * <p>安全说明：</p>
 * <p>这些路由属于内部控制面，不应直接暴露给公网或普通用户。生产环境应继续通过 gateway、服务网格、ACL、
 * mTLS 或服务账号签名限制调用方。请求和响应都必须保持低敏，不承载 SQL、连接串、工具参数、样本数据、
 * checkpoint 原始值、prompt、模型输出、凭据或内部 endpoint。</p>
 */
@RestController
@RequestMapping("/internal/data-sync-worker-execution-receipts")
@RequiredArgsConstructor
public class DataSyncWorkerExecutionReceiptController {

    /**
     * 执行回执服务，负责校验、关联 outbox、幂等写入、脱敏和低敏视图组装。
     */
    private final DataSyncWorkerExecutionReceiptService receiptService;

    /**
     * 记录 datasource-management Runner 的低敏执行回执。
     *
     * <p>该接口有数据库写副作用。下游应为同一个执行事件生成稳定 receiptId，
     * 这样网络重试或 Kafka 重放时可以被 task-management 幂等识别。</p>
     *
     * @param request 低敏执行回执请求。
     * @return 低敏写入结果。
     */
    @PostMapping("/record")
    public ResponseEntity<ApiResponse<DataSyncWorkerExecutionReceiptRecordResult>> record(
            @RequestBody DataSyncWorkerExecutionReceiptRecordRequest request) {
        DataSyncWorkerExecutionReceiptRecordResult result = receiptService.recordReceipt(request);
        return ResponseEntity.ok(ApiResponse.success("DataSync worker execution receipt 已记录", result));
    }

    /**
     * 查询 DataSync worker 执行回执历史。
     *
     * <p>该接口无业务副作用，用于内部诊断、管理台执行历史和后续 Agent timeline 聚合。
     * 返回结果不会包含错误正文、warning 正文、checkpoint 原始值或同步数据明细。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<DataSyncWorkerExecutionReceiptQueryResult>> query(
            @RequestParam(value = "commandId", required = false) String commandId,
            @RequestParam(value = "syncTaskId", required = false) Long syncTaskId,
            @RequestParam(value = "syncExecutionId", required = false) Long syncExecutionId,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "limit", required = false) Integer limit) {
        DataSyncWorkerExecutionReceiptQueryResult result = receiptService.queryReceipts(
                commandId,
                syncTaskId,
                syncExecutionId,
                taskId,
                tenantId,
                projectId,
                eventType,
                limit
        );
        return ResponseEntity.ok(ApiResponse.success("DataSync worker execution receipt 查询完成", result));
    }
}
