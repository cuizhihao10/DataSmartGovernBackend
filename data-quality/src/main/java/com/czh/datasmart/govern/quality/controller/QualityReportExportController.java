/**
 * @Author : Cui
 * @Date: 2026/07/01 11:10
 * @Description DataSmartGovernBackend - QualityReportExportController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.controller.dto.QualityReportLowSensitiveExportContent;
import com.czh.datasmart.govern.quality.service.QualityReportLowSensitiveExportService;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质量报告导出控制器。
 *
 * <p>它和 QualityReportController 分开，是为了避免“查询报告”和“导出文件”继续耦合。
 * 商业产品里，导出通常会继续演进出异步任务、审批、脱敏策略、文件保留期、下载授权和审计日志。
 * 单独拆出 Controller 后，后续可以只增强导出域，不影响普通报告列表/详情查询。</p>
 */
@RestController
@RequestMapping("/quality-rules/reports")
@RequiredArgsConstructor
public class QualityReportExportController {

    private final QualityReportLowSensitiveExportService exportService;
    private final QualityProjectScopeSupport qualityProjectScopeSupport;

    /**
     * 导出低敏质量报告 CSV。
     *
     * <p>路由语义：
     * {@code GET /quality-rules/reports/{reportId}/exports/low-sensitive-csv}
     * 表示导出一份“可运营/可审计查看的低敏报告文件”。它不是高敏样本导出接口。</p>
     *
     * <p>安全边界：
     * - 不导出 recordIdentifier、observedValue、expectedValue、samplePayload、recommendation、notes；
     * - PROJECT 数据范围仍然生效，未授权项目不能通过猜 reportId 下载；
     * - maxAnomalyRows 有服务层硬上限，避免同步接口被误用成大批量导出任务；
     * - 后续如需导出样本原文，必须接入 permission-admin 高风险权限、人审、脱敏和审计留痕。</p>
     *
     * @param reportId 质量报告 ID。
     * @param maxAnomalyRows 低敏异常行最大导出数量。
     * @param dataScopeLevel gateway/permission-admin 透传的数据范围级别。
     * @param authorizedProjectIds gateway/permission-admin 透传的授权项目集合。
     * @return CSV 文件下载响应。
     */
    @GetMapping("/{reportId}/exports/low-sensitive-csv")
    public ResponseEntity<String> exportLowSensitiveCsv(
            @PathVariable Long reportId,
            @RequestParam(required = false) Integer maxAnomalyRows,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = qualityProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        QualityReportLowSensitiveExportContent content = exportService.exportReport(
                reportId, visibility, maxAnomalyRows);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName())
                        .build()
                        .toString())
                .header("X-DataSmart-Export-Sensitivity", "LOW_SENSITIVE_ONLY")
                .header("X-DataSmart-Export-Anomaly-Rows", String.valueOf(content.anomalyRows()))
                .header("X-DataSmart-Export-Truncated", String.valueOf(content.truncated()))
                .body(content.body());
    }
}
