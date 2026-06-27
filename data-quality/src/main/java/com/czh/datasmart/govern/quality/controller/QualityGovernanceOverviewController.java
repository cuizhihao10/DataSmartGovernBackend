/**
 * @Author : Cui
 * @Date: 2026/06/27 20:58
 * @Description DataSmart Govern Backend - QualityGovernanceOverviewController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.QualityGovernanceOverviewResponse;
import com.czh.datasmart.govern.quality.service.QualityGovernanceOverviewService;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据质量治理总览控制器。
 *
 * <p>该控制器承载的是质量模块的“管理大盘入口”，和已有控制器的边界如下：</p>
 *
 * <p>1. {@link DataQualityController}：管理规则定义、生命周期、扫描计划和单条规则检测；</p>
 * <p>2. {@link QualityReportController}：查询报告、异常明细和异常聚合；</p>
 * <p>3. {@link QualityExecutorOperationsController}：面向 worker/coordinator 的执行器运维与回调协议；</p>
 * <p>4. 本控制器：面向项目负责人、运营人员、审计人员和 Agent 复盘流程，聚合低敏治理态势。</p>
 *
 * <p>单独拆出该 Controller 的原因是避免把“规则 CRUD”“报告详情”“执行器协议”“运营总览”继续
 * 堆进同一个文件。这样每条产品能力线都能独立扩展，也符合用户要求的低耦合与单文件行数控制。</p>
 */
@RestController
@RequestMapping("/quality-rules/governance")
@RequiredArgsConstructor
public class QualityGovernanceOverviewController {

    private final QualityGovernanceOverviewService overviewService;
    private final QualityProjectScopeSupport projectScopeSupport;

    /**
     * 查询数据质量治理总览。
     *
     * <p>路由语义：{@code GET /quality-rules/governance/overview} 表示读取质量规则体系之上的治理态势。
     * 该接口不会创建规则、不会执行扫描、不会生成报告，只做低敏聚合统计和治理建议生成。</p>
     *
     * <p>权限语义：Controller 只解析 gateway/permission-admin 透传的 PROJECT 数据范围 Header，并把解析后的
     * {@link QualityProjectVisibility} 传给服务层。真正的 SQL 过滤在服务层统一追加，避免每个查询条件
     * 都在 Controller 中手写，降低漏加权限条件的风险。</p>
     *
     * @param tenantId 可选租户过滤条件
     * @param projectId 可选项目过滤条件，会与授权项目集合共同生效
     * @param workspaceId 可选工作空间过滤条件
     * @param windowDays 近期报告、执行和异常统计窗口天数，服务层会裁剪到安全范围
     * @param topLimit TOP 异常字段和类型返回上限，服务层会裁剪到安全范围
     * @param dataScopeLevel gateway/permission-admin 透传的数据范围级别
     * @param authorizedProjectIds gateway/permission-admin 透传的授权项目集合
     * @return 数据质量治理总览低敏响应
     */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<QualityGovernanceOverviewResponse>> overview(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(required = false) Integer topLimit,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        QualityProjectVisibility visibility = projectScopeSupport.resolveVisibility(
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据质量治理总览生成完成",
                overviewService.overview(tenantId, windowDays, topLimit, visibility)));
    }
}
