/**
 * @Author : Cui
 * @Date: 2026/05/07 21:33
 * @Description DataSmart Govern Backend - DataSyncTemplateController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步模板 API。
 *
 * <p>同步模板负责保存“可复用同步配置”，不直接代表一次运行。真实产品中，模板通常会经历：
 * 创建 -> 规划预览 -> 执行前预检查 -> 创建任务 -> 审批/调度 -> worker 执行。把这些入口拆开，
 * 能让用户、Agent 和运维人员清楚知道每一步的职责，而不是把“保存配置”和“开始搬数据”混在一起。</p>
 */
@RestController
@RequestMapping({"/sync-templates", "/api/sync-templates"})
@RequiredArgsConstructor
public class DataSyncTemplateController {

    private final DataSyncService dataSyncService;

    /**
     * 创建同步模板。
     *
     * <p>该接口只保存配置，不触发真实读写。请求体中的 datasourceId 是对 datasource-management 的引用，
     * 本服务不会保存连接串、账号、密码或密钥。</p>
     */
    @PostMapping
    public PlatformApiResponse<SyncTemplate> createTemplate(
            @Valid @RequestBody CreateSyncTemplateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTemplate template = dataSyncService.createTemplate(request, actorContext(tenantId, actorId, actorRole, traceId, headers));
        return PlatformApiResponse.success("同步模板创建成功", template, traceId);
    }

    /**
     * 分页查询同步模板。
     *
     * <p>列表接口用于控制台和 Agent 查询模板目录，只按低敏条件筛选，不返回任何连接凭据。</p>
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<SyncTemplate>> pageTemplates(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long sourceDatasourceId,
            @RequestParam(required = false) Long targetDatasourceId,
            @RequestParam(required = false) String syncMode,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTemplateQueryCriteria criteria = new SyncTemplateQueryCriteria(
                tenantId, projectId, workspaceId, sourceDatasourceId, targetDatasourceId, syncMode, enabled, current, size);
        return PlatformApiResponse.success(dataSyncService.pageTemplates(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询同步模板详情。
     *
     * <p>模板详情属于配置控制面数据，仍然要经过租户、项目和 SELF 数据范围校验。</p>
     */
    @GetMapping("/{id}")
    public PlatformApiResponse<SyncTemplate> getTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.getTemplate(
                id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 校验同步模板。
     *
     * <p>validate 是 fail-fast 接口：只要模板存在硬性配置错误，就直接抛业务异常。它适合按钮“校验模板”，
     * 不适合一次性展示所有改进建议；展示建议请使用 preview。</p>
     */
    @PostMapping("/{id}/validate")
    public PlatformApiResponse<SyncTaskOperationResult> validateTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步模板校验完成",
                dataSyncService.validateTemplate(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 生成同步模板规划预览。
     *
     * <p>preview 面向“配置是否清晰、是否建议进入任务草稿、是否需要补充策略”的低敏报告。
     * 它不会执行同步，不会读取源端样本，不会返回字段映射、过滤条件、分区窗口、自定义 SQL、checkpoint 原文或连接配置。</p>
     */
    @PostMapping("/{id}/preview")
    public PlatformApiResponse<SyncTemplatePlanningPreviewResponse> previewTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步模板规划预览生成成功",
                dataSyncService.previewTemplate(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 生成同步模板执行前预检查。
     *
     * <p>precheck 面向“能不能真实入队执行”的准入判断。它比 preview 更严格：如果当前最小 runner 不支持多对象、
     * 全库、自定义 SQL、checkpoint handoff 或字段转换，就会返回 NOT_SUPPORTED_BY_CURRENT_RUNNER 或 BLOCKED，
     * 并且 {@code runTask} 也会复用同一套预检查结果阻止入队。</p>
     */
    @PostMapping("/{id}/precheck")
    public PlatformApiResponse<SyncTemplateExecutionPrecheckResponse> precheckTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步模板执行前预检查完成",
                dataSyncService.precheckTemplate(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 生成 DataX 风格离线作业计划。
     *
     * <p>该接口服务“离线 runner 规划”，不是执行入口。它会根据模板推导 Reader/Writer 家族、模式族、分片策略、
     * 调度语义、statementRef 策略、checkpoint handoff、审批要求和 fail-closed 原因，但不会：</p>
     * <p>1. 创建同步任务；</p>
     * <p>2. 把任务推进 QUEUED；</p>
     * <p>3. 连接源端或目标端；</p>
     * <p>4. 执行 SQL 或读取样本；</p>
     * <p>5. 返回 SQL、字段映射、对象映射、过滤条件、分区配置、连接串或凭据正文。</p>
     *
     * <p>为什么使用 POST 而不是 GET：虽然该接口是只读规划，但它可能在未来接收更复杂的低敏规划上下文
     * 或 agent planning options；保持 POST 可避免把复杂参数和潜在敏感上下文放进 URL、代理日志和浏览器历史。</p>
     */
    @PostMapping("/{id}/offline-job-plan")
    public PlatformApiResponse<SyncOfflineJobPlanResponse> buildOfflineJobPlan(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步模板离线作业计划生成成功",
                dataSyncService.buildOfflineJobPlan(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
