/**
 * @Author : Cui
 * @Date: 2026/4/18 21:40
 * @Description DataSmart Govern Backend - DataQualityController.java
 * @Version:1.0.0
 */
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 数据质量控制器。
 * 当前模块对外暴露两组紧密关联的能力：
 * 1. 质量规则管理。
 * 2. 规则执行与检测报告查询。
 *
 * 这两组能力合在一起，形成了最小但完整的治理闭环：
 * 先定义什么叫合格，再执行判断，最后把判断过程沉淀成报告。
 */
@RestController
@RequestMapping("/quality-rules")
@RequiredArgsConstructor
public class DataQualityController {

    /**
     * 控制器只依赖服务接口，保持接口层与业务层边界清晰。
     */
    private final DataQualityService dataQualityService;

    /**
     * 创建质量规则。
     */
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
        return ResponseEntity.ok(ApiResponse.success("质量规则创建成功", rule));
    }

    /**
     * 分页查询质量规则。
     * 当前支持按规则类型和状态做过滤，并默认排除已逻辑删除规则。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<QualityRule>>> listRules(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (ruleType != null && !ruleType.isBlank()) {
            wrapper.eq(QualityRule::getRuleType, ruleType.toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(QualityRule::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(QualityRule::getCreateTime);
        return ResponseEntity.ok(ApiResponse.success(
                dataQualityService.page(new Page<>(current, size), wrapper)));
    }

    /**
     * 查询规则详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> getRule(@PathVariable Long id) {
        QualityRule rule = dataQualityService.getById(id);
        if (rule == null || QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    /**
     * 更新质量规则。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateQualityRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "质量规则更新成功",
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

    /**
     * 启用规则。
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<QualityRule>> enableRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已启用", dataQualityService.enableRule(id)));
    }

    /**
     * 停用规则。
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<QualityRule>> disableRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已停用", dataQualityService.disableRule(id)));
    }

    /**
     * 逻辑删除规则。
     * 当前删除并不物理移除数据，而是把状态改为 DELETED，便于后续保留审计痕迹。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<QualityRule>> deleteRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("质量规则已删除", dataQualityService.deleteRule(id)));
    }

    /**
     * 执行一次质量检测。
     * 该接口会依据规则定义和外部传入观测值生成一次报告。
     */
    @PostMapping("/{id}/run-check")
    public ResponseEntity<ApiResponse<QualityCheckReport>> runQualityCheck(
            @PathVariable Long id,
            @Valid @RequestBody RunQualityCheckRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "质量检测执行完成",
                dataQualityService.runQualityCheck(
                        id,
                        request.getMeasuredValue(),
                        request.getSampleSize(),
                        request.getExceptionCount(),
                        request.getNotes()
                )));
    }

    /**
     * 查询某条规则下的历史报告。
     * 详情接口看规则定义，报告接口看历史执行结果，二者结合才能完整理解一条规则。
     */
    @GetMapping("/{id}/reports")
    public ResponseEntity<ApiResponse<List<QualityCheckReport>>> listReports(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(dataQualityService.listReportsByRuleId(id)));
    }
}
