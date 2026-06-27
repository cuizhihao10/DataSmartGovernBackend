/**
 * @Author : Cui
 * @Date: 2026/06/28 15:12
 * @Description DataSmart Govern Backend - QualityRemediationTaskController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityRemediationTaskRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRemediationTaskResponse;
import com.czh.datasmart.govern.quality.service.QualityRemediationTaskService;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质量异常治理任务控制器。
 *
 * <p>路由：POST /quality-rules/remediation-tasks。</p>
 *
 * <p>该路由补齐 data-quality 的关键闭环：质量规则和执行器发现异常后，用户或 Agent 可以把异常聚合转成
 * task-management 中的治理/复核任务。它不是清洗执行路由，不直接修改源数据，也不生成 SQL 清洗脚本。
 * 这样设计可以在商业化产品里先建立安全、可审计、可分派的治理流程，再逐步接入自动清洗执行器。</p>
 *
 * <p>权限语义：gateway 应将该路由映射为 QUALITY_ANOMALY + CREATE_REMEDIATION_TASK。
 * 原因是它面向异常工作台的“处置动作”，既不属于普通报告查看，也不属于质量规则 CRUD。</p>
 */
@RestController
@RequestMapping("/quality-rules/remediation-tasks")
@RequiredArgsConstructor
public class QualityRemediationTaskController {

    private final QualityRemediationTaskService remediationTaskService;
    private final QualityProjectScopeSupport qualityProjectScopeSupport;

    /**
     * 创建质量异常治理任务。
     *
     * <p>Header 中的数据范围由 gateway/permission-admin 生成：
     * `X-DataSmart-Data-Scope-Level=PROJECT` 时，`X-DataSmart-Authorized-Project-Ids`
     * 会被解析为项目白名单，service 会据此短路空授权、校验 reportId 归属并收口异常查询。</p>
     *
     * <p>返回中可能包含 payloadPreview，但 preview 只包含低敏聚合摘要。
     * 如果调用方需要查看具体样本，应继续走异常工作台的受控查询接口，而不是从任务创建接口获取。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<QualityRemediationTaskResponse>> createRemediationTask(
            @RequestBody(required = false) QualityRemediationTaskRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityRemediationTaskRequest safeRequest = request == null ? new QualityRemediationTaskRequest() : request;
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                safeRequest.getProjectId(),
                safeRequest.getWorkspaceId(),
                dataScopeLevel,
                authorizedProjectIds
        );
        return ResponseEntity.ok(ApiResponse.success(remediationTaskService.createRemediationTask(
                safeRequest,
                visibility,
                tenantId,
                actorId
        )));
    }
}
