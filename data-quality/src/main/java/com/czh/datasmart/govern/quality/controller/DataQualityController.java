package com.czh.datasmart.govern.quality.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.quality.common.ApiResponse;
import com.czh.datasmart.govern.quality.controller.dto.CreateQualityRuleRequest;
import com.czh.datasmart.govern.quality.controller.dto.RunQualityCheckRequest;
import com.czh.datasmart.govern.quality.controller.dto.UpdateQualityRuleRequest;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 数据质量控制器。
 * <p>
 * 当前对外暴露两组能力：
 * 1. 质量规则管理。
 * 2. 质量检测执行与报告查询。
 * <p>
 * 这两组能力结合起来，构成了一个完整的最小业务闭环：
 * 先定义“什么叫合格”，再判断“这次是否合格”，最后保存“为什么合格或不合格”。
 */
@RestController
@RequestMapping("/quality-rules")
@RequiredArgsConstructor
public class DataQualityController {

    private final DataQualityService dataQualityService;

    @PostMapping
    public ResponseEntity<ApiResponse<QualityRule>> createRule(@Valid @RequestBody CreateQualityRuleRequest request) {
        QualityRule rule = dataQualityService.createRule(
                request.getName(),
                request.getRuleType(),
                request.getTargetObject(),
                request.getComparisonOperator(),
                request.getExpectedValue(),
                request.getSeverity(),
                request.getDescription()
        );
        return ResponseEntity.ok(ApiResponse.success("quality rule created", rule));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<IPage<QualityRule>>> listRules(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (ruleType != null) {
            wrapper.eq(QualityRule::getRuleType, ruleType.toUpperCase());
        }
        if (status != null) {
            wrapper.eq(QualityRule::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(QualityRule::getCreateTime);
        return ResponseEntity.ok(ApiResponse.success(
                dataQualityService.page(new Page<>(current, size), wrapper)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> getRule(@PathVariable Long id) {
        QualityRule rule = dataQualityService.getById(id);
        if (rule == null || QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new NoSuchElementException("Quality rule not found: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateQualityRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("quality rule updated",
                dataQualityService.updateRule(
                        id,
                        request.getName(),
                        request.getTargetObject(),
                        request.getComparisonOperator(),
                        request.getExpectedValue(),
                        request.getSeverity(),
                        request.getDescription()
                )));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<QualityRule>> enableRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("quality rule enabled", dataQualityService.enableRule(id)));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<QualityRule>> disableRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("quality rule disabled", dataQualityService.disableRule(id)));
    }

    /**
     * 执行一次规则检测。
     * <p>
     * 未来真实执行器可以把计算出的 measuredValue 回填到这里，
     * 当前则先由接口接收观测值，用于完成规则引擎的第一版闭环。
     */
    @PostMapping("/{id}/run-check")
    public ResponseEntity<ApiResponse<QualityCheckReport>> runCheck(
            @PathVariable Long id,
            @Valid @RequestBody RunQualityCheckRequest request) {
        return ResponseEntity.ok(ApiResponse.success("quality check completed",
                dataQualityService.runQualityCheck(
                        id,
                        request.getMeasuredValue(),
                        request.getSampleSize(),
                        request.getExceptionCount(),
                        request.getNotes()
                )));
    }

    @GetMapping("/{id}/reports")
    public ResponseEntity<ApiResponse<List<QualityCheckReport>>> listReports(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listReportsByRuleId(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> deleteRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("quality rule deleted", dataQualityService.deleteRule(id)));
    }
}
