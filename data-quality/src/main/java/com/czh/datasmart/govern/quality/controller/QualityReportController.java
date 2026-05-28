/**
 * @Author : Cui
 * @Date: 2026/05/07 21:18
 * @Description DataSmart Govern Backend - QualityReportController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量报告与异常样本查询控制器。
 *
 * <p>该控制器从 DataQualityController 中拆出，专门承载“质量检测之后怎么看结果”的查询面。
 * 规则管理回答“定义了什么质量要求”，执行器入口回答“怎么执行检测”，而报告与异常查询回答：
 * 哪些规则失败了、失败样本在哪里、问题集中在哪个字段或异常类型、是否需要人工复核或清洗。</p>
 *
 * <p>商业化产品中，报告和异常查询通常会继续演进为质量大盘、异常工作台、清洗任务入口、
 * 审计导出、AI 根因分析上下文和告警回溯。因此把它独立成 Controller，可以避免规则 CRUD
 * 和运营分析接口继续耦合在同一个文件中。</p>
 */
@RestController
@RequestMapping("/quality-rules")
@RequiredArgsConstructor
public class QualityReportController {

    private final DataQualityService dataQualityService;
    private final QualityProjectScopeSupport qualityProjectScopeSupport;

    /**
     * 分页查询质量检测报告。
     *
     * <p>这是面向质量运营后台的横向检索入口，不要求用户先进入某条规则详情。
     * 典型问题包括：最近有哪些失败报告、哪些 CRITICAL 规则频繁失败、某个目标对象近期质量趋势如何、
     * 人工触发和任务触发的质量结果是否存在差异。</p>
     */
    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<IPage<QualityCheckReport>>> pageReports(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String checkStatus,
            @RequestParam(required = false) String targetObject,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Boolean failedOnly,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.pageReports(
                current,
                size,
                ruleId,
                ruleType,
                severity,
                checkStatus,
                targetObject,
                triggerType,
                startTime,
                endTime,
                failedOnly,
                visibility
        )));
    }

    /**
     * 查询某份质量报告下的异常明细。
     *
     * <p>报告摘要告诉我们“失败了多少”，异常明细告诉我们“哪些样本失败、为什么失败、建议如何处理”。
     * 这个接口可以服务前端报告详情页、清洗任务创建页、人工复核页和 AI 复盘上下文。</p>
     */
    @GetMapping("/reports/{reportId}/anomalies")
    public ResponseEntity<ApiResponse<List<QualityAnomalyDetail>>> listAnomalies(
            @PathVariable Long reportId,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listAnomaliesByReportId(reportId, visibility)));
    }

    /**
     * 分页查询质量异常明细。
     *
     * <p>该接口不局限于单份报告，可以按规则、报告、异常类型、字段、严重级别、目标对象和时间范围
     * 定位异常样本，适合质量运营台、问题排查页、人工复核页和清洗任务创建页。</p>
     */
    @GetMapping("/anomalies")
    public ResponseEntity<ApiResponse<IPage<QualityAnomalyDetail>>> pageAnomalies(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String fieldName,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String targetObject,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.pageAnomalies(
                current,
                size,
                reportId,
                ruleId,
                anomalyType,
                fieldName,
                severity,
                targetObject,
                startTime,
                endTime,
                visibility
        )));
    }

    /**
     * 聚合统计质量异常。
     *
     * <p>分页明细解决“看具体样本”，聚合接口解决“先看主要问题在哪里”。
     * groupBy 支持 FIELD、TYPE、SEVERITY、TARGET_OBJECT，默认 FIELD。
     * limit 默认 10、最大 100，避免聚合接口被误用成全量分析任务。</p>
     */
    @GetMapping("/anomalies/aggregation")
    public ResponseEntity<ApiResponse<List<QualityAnomalyAggregationItem>>> aggregateAnomalies(
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String fieldName,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String targetObject,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.aggregateAnomalies(
                reportId,
                ruleId,
                anomalyType,
                fieldName,
                severity,
                targetObject,
                startTime,
                endTime,
                groupBy,
                limit,
                visibility
        )));
    }
}
