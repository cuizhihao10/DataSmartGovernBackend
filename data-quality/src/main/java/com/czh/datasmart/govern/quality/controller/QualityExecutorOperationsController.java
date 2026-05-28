/**
 * @Author : Cui
 * @Date: 2026/05/07 21:20
 * @Description DataSmart Govern Backend - QualityExecutorOperationsController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionFailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionStartRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionSuccessRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.executor.QualityTaskExecutorCoordinator;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质量执行器运维与回调控制器。
 *
 * <p>这里的接口不是普通用户的规则管理入口，而是面向 data-quality 执行器、调度器、本地联调和运维排障。
 * 它负责把 task-management 认领到的 `DATA_QUALITY_SCAN` 任务转换为 data-quality 内部 execution，
 * 并在扫描成功或失败后完成 data-quality 自己的执行记录和报告闭环。</p>
 *
 * <p>把执行器协议从 DataQualityController 拆出后，后续可以继续补充服务账号签名、执行器白名单、
 * 批量消费限流、租户级并发配额、执行器版本上报、灰度 worker、失败熔断和调度指标，而不会影响规则 CRUD。</p>
 */
@RestController
@RequestMapping("/quality-rules/executor")
@RequiredArgsConstructor
public class QualityExecutorOperationsController {

    private final DataQualityService dataQualityService;
    private final QualityTaskExecutorCoordinator qualityTaskExecutorCoordinator;

    /**
     * 质量执行器开始执行回调。
     *
     * <p>推荐调用顺序是：先从 task-management 认领任务，再解析 payload，然后调用本接口创建 RUNNING execution，
     * 最后执行真实扫描并通过 succeed 或 fail 回调收口。这样两个微服务只通过 API 合同同步状态。</p>
     */
    @PostMapping("/executions/start")
    public ResponseEntity<ApiResponse<QualityCheckExecution>> startTaskExecution(
            @Valid @RequestBody QualityExecutionStartRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务执行记录已开始",
                dataQualityService.startTaskExecution(request)));
    }

    /**
     * 质量执行器成功完成回调。
     *
     * <p>该接口会把 RUNNING execution 收口为 SUCCESS，并生成质量报告和异常明细。
     * 这里的 SUCCESS 表示扫描动作成功完成，质量结果仍由 report.checkStatus 表示。</p>
     */
    @PostMapping("/executions/{executionId}/succeed")
    public ResponseEntity<ApiResponse<QualityCheckReport>> completeTaskExecution(
            @PathVariable Long executionId,
            @Valid @RequestBody QualityExecutionSuccessRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务执行成功并生成报告",
                dataQualityService.completeTaskExecution(executionId, request)));
    }

    /**
     * 质量执行器失败回调。
     *
     * <p>该接口记录扫描动作没有成功完成的技术失败，例如数据源连接失败、执行超时、
     * SQL 被安全策略拒绝、执行器进程异常或任务被取消。技术失败不会生成质量报告，
     * 避免把平台故障误判为业务数据质量差。</p>
     */
    @PostMapping("/executions/{executionId}/fail")
    public ResponseEntity<ApiResponse<QualityCheckExecution>> failTaskExecution(
            @PathVariable Long executionId,
            @Valid @RequestBody QualityExecutionFailRequest request) {
        return ResponseEntity.ok(ApiResponse.success("质量检测任务执行失败已记录",
                dataQualityService.failTaskExecution(executionId, request)));
    }

    /**
     * 手动触发一次质量执行器 coordinator。
     *
     * <p>这是受控联调入口，每次最多认领一条质量任务，适合验证认领、payload 校验、
     * SQL 扫描、报告生成和 task-management 回调链路是否闭环。</p>
     */
    @PostMapping("/coordinator/run-once")
    public ResponseEntity<ApiResponse<QualityExecutorRunResult>> runExecutorOnce() {
        return ResponseEntity.ok(ApiResponse.success("质量执行器 coordinator 单次运行完成",
                qualityTaskExecutorCoordinator.runOnce()));
    }

    /**
     * 手动触发一小批质量执行器 coordinator。
     *
     * <p>该接口用于模拟后台 scheduler 的单轮行为。maxRuns 会在 coordinator 内部压到安全范围，
     * 避免一次手动调用认领过多任务造成源库压力或队列状态剧烈波动。</p>
     */
    @PostMapping("/coordinator/run-batch")
    public ResponseEntity<ApiResponse<List<QualityExecutorRunResult>>> runExecutorBatch(
            @RequestParam(defaultValue = "1") Integer maxRuns) {
        return ResponseEntity.ok(ApiResponse.success("质量执行器 coordinator 批量运行完成",
                qualityTaskExecutorCoordinator.runBatch(maxRuns)));
    }
}
