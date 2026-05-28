/**
 * @Author : Cui
 * @Date: 2026/05/05 23:05
 * @Description DataSmart Govern Backend - QualityRuleLifecycleSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.scan.QualityScanStrategyRegistry;
import com.czh.datasmart.govern.quality.support.QualityComparisonOperator;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import com.czh.datasmart.govern.quality.support.QualityRuleType;
import com.czh.datasmart.govern.quality.support.QualitySeverity;
import com.czh.datasmart.govern.quality.support.QualityTargetValidationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * 质量规则生命周期支撑组件。
 *
 * <p>该组件专门承载“质量规则从创建、修改、校验、启用、停用、归档、恢复到逻辑删除”的生命周期逻辑。
 * 它从 `DataQualityServiceImpl` 拆出后，主服务不再需要直接关心大量状态变更细节，可以把注意力放在
 * 跨模块编排、执行回调和对外服务契约上。
 *
 * <p>商业化产品里，规则生命周期并不是普通 CRUD：
 * 1. DRAFT 表示规则仍在配置或评审中，不应该直接进入生产扫描；
 * 2. ACTIVE 表示规则可被调度器、执行器或人工检测消费；
 * 3. INACTIVE 表示规则保留但暂停执行，适合临时下线；
 * 4. ARCHIVED 表示规则退出当前治理流程，但历史报告仍需保留；
 * 5. DELETED 表示逻辑删除，避免物理删除破坏审计链路和历史报告解释。
 *
 * <p>后续如果补充规则审批、版本发布、灰度启用、批量变更、规则模板、租户级规则目录，
 * 都应优先扩展该组件，而不是继续把 `DataQualityServiceImpl` 变成胖服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityRuleLifecycleSupport {

    /**
     * 质量规则 Mapper。
     * 这里直接使用 Mapper，是为了让生命周期组件自己拥有规则持久化能力，而不是依赖主服务转发 `save/updateById`。
     */
    private final QualityRuleMapper qualityRuleMapper;

    /**
     * 质量扫描策略注册表。
     * 生命周期组件在创建、更新、校验和启用规则时，都需要判断当前目标是否具备可扫描能力。
     */
    private final QualityScanStrategyRegistry qualityScanStrategyRegistry;

    public QualityRule createRule(Long tenantId, Long projectId, Long workspaceId,
                                  String name, String ruleType, String targetObject, String targetType,
                                  Long dataSourceId, String databaseName, String schemaName, String tableName,
                                  String fieldName, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        ensureRuleNameNotDuplicated(tenantId, projectId, name, null);
        QualityRule rule = new QualityRule();
        rule.setTenantId(tenantId);
        rule.setProjectId(projectId);
        rule.setWorkspaceId(workspaceId);
        rule.setName(name);
        rule.setRuleType(QualityRuleType.fromValue(ruleType).name());
        rule.setTargetObject(targetObject);
        applyTargetFields(rule, targetType, dataSourceId, databaseName, schemaName, tableName, fieldName);
        rule.setComparisonOperator(QualityComparisonOperator.fromValue(comparisonOperator).name());
        rule.setExpectedValue(expectedValue);
        rule.setSeverity(QualitySeverity.normalize(severity));
        rule.setDescription(description);
        rule.setStatus(QualityRuleStatus.DRAFT);
        rule.setRuleVersion(1);
        rule.setTargetValidationStatus(QualityTargetValidationStatus.UNVALIDATED);
        rule.setCreateTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.insert(rule);
        applyValidationResult(rule, qualityScanStrategyRegistry.validate(rule));
        log.info("创建质量规则成功，ruleId={}", rule.getId());
        return rule;
    }

    public QualityRule updateRule(Long id, String name, String targetObject, String targetType,
                                  Long dataSourceId, String databaseName, String schemaName, String tableName,
                                  String fieldName, String comparisonOperator,
                                  BigDecimal expectedValue, String severity, String description) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        ensureRuleNameNotDuplicated(rule.getTenantId(), rule.getProjectId(), name, id);
        rule.setName(name);
        rule.setTargetObject(targetObject);
        applyTargetFields(rule, targetType, dataSourceId, databaseName, schemaName, tableName, fieldName);
        rule.setComparisonOperator(QualityComparisonOperator.fromValue(comparisonOperator).name());
        rule.setExpectedValue(expectedValue);
        rule.setSeverity(QualitySeverity.normalize(severity));
        rule.setDescription(description);
        rule.setRuleVersion(rule.getRuleVersion() == null ? 1 : rule.getRuleVersion() + 1);
        rule.setTargetValidationStatus(QualityTargetValidationStatus.UNVALIDATED);
        rule.setTargetValidationMessage("规则目标已更新，等待重新校验");
        rule.setTargetValidatedTime(null);
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
        applyValidationResult(rule, qualityScanStrategyRegistry.validate(rule));
        log.info("更新质量规则成功，ruleId={}", id);
        return rule;
    }

    public QualityRule enableRule(Long id, String reason) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        QualityRuleTargetValidationResult validationResult = qualityScanStrategyRegistry.validate(rule);
        applyValidationResult(rule, validationResult);
        ensureTargetCanBeActivated(validationResult);
        rule.setStatus(QualityRuleStatus.ACTIVE);
        rule.setArchivedTime(null);
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
        log.info("启用质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
    }

    public QualityRule disableRule(Long id, String reason) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        ensureNotArchived(rule);
        rule.setStatus(QualityRuleStatus.INACTIVE);
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
        log.info("停用质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
    }

    public QualityRule archiveRule(Long id, String reason) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        if (QualityRuleStatus.ARCHIVED.equals(rule.getStatus())) {
            return rule;
        }
        rule.setStatus(QualityRuleStatus.ARCHIVED);
        rule.setArchivedTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
        log.info("归档质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
    }

    public QualityRule restoreRule(Long id, String reason) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        if (!QualityRuleStatus.ARCHIVED.equals(rule.getStatus())) {
            throw new IllegalStateException("只有已归档规则才能恢复");
        }
        rule.setStatus(QualityRuleStatus.INACTIVE);
        rule.setArchivedTime(null);
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
        log.info("恢复归档质量规则成功，ruleId={}, reason={}", id, reason);
        return rule;
    }

    public QualityRule deleteRule(Long id) {
        QualityRule rule = getRequiredRule(id);
        rule.setStatus(QualityRuleStatus.DELETED);
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
        log.info("逻辑删除质量规则成功，ruleId={}", id);
        return rule;
    }

    public QualityRuleTargetValidationResult validateRuleTarget(Long id) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        QualityRuleTargetValidationResult result = qualityScanStrategyRegistry.validate(rule);
        applyValidationResult(rule, result);
        log.info("校验质量规则目标完成，ruleId={}, status={}, strategy={}",
                id, result.getValidationStatus(), result.getScanStrategy());
        return result;
    }

    public QualityScanPlan buildScanPlan(Long id, QualityScanPlanRequest request) {
        QualityRule rule = getRequiredRule(id);
        ensureNotDeleted(rule);
        if (!QualityTargetValidationStatus.VALIDATED.equals(rule.getTargetValidationStatus())) {
            throw new IllegalStateException("质量规则目标尚未通过校验，不能生成扫描计划: " + rule.getTargetValidationMessage());
        }
        QualityScanPlan plan = qualityScanStrategyRegistry.buildScanPlan(rule, request);
        log.info("生成质量扫描计划完成，ruleId={}, strategy={}, mode={}, schedulable={}",
                id, plan.getScanStrategy(), plan.getExecutionMode(), plan.getSchedulable());
        return plan;
    }

    public QualityRule getRequiredRule(Long id) {
        QualityRule rule = qualityRuleMapper.selectById(id);
        if (rule == null) {
            throw new NoSuchElementException("质量规则不存在: " + id);
        }
        return rule;
    }

    public void ensureNotDeleted(QualityRule rule) {
        if (QualityRuleStatus.DELETED.equals(rule.getStatus())) {
            throw new IllegalStateException("质量规则已删除: " + rule.getId());
        }
    }

    private void applyTargetFields(QualityRule rule, String targetType, Long dataSourceId,
                                   String databaseName, String schemaName, String tableName, String fieldName) {
        QualityRuleTargetType normalizedTargetType = QualityRuleTargetType.fromValue(targetType);
        rule.setTargetType(normalizedTargetType.name());
        rule.setDataSourceId(dataSourceId);
        rule.setDatabaseName(trimToNull(databaseName));
        rule.setSchemaName(trimToNull(schemaName));
        rule.setTableName(trimToNull(tableName));
        rule.setFieldName(trimToNull(fieldName));
    }

    private void applyValidationResult(QualityRule rule, QualityRuleTargetValidationResult result) {
        rule.setScanStrategy(result.getScanStrategy());
        rule.setTargetValidationStatus(result.getValidationStatus());
        rule.setTargetValidationMessage(result.getMessage());
        rule.setTargetValidatedTime(result.getValidatedTime());
        rule.setUpdateTime(LocalDateTime.now());
        qualityRuleMapper.updateById(rule);
    }

    private void ensureTargetCanBeActivated(QualityRuleTargetValidationResult result) {
        if (!Boolean.TRUE.equals(result.getValid())) {
            throw new IllegalStateException("质量规则目标校验未通过，不能启用: " + result.getMessage());
        }
    }

    private void ensureNotArchived(QualityRule rule) {
        if (QualityRuleStatus.ARCHIVED.equals(rule.getStatus())) {
            throw new IllegalStateException("质量规则已归档，请先恢复后再操作: " + rule.getId());
        }
    }

    /**
     * 校验同一租户、同一项目下的规则名称唯一。
     *
     * <p>旧实现只按 name 去重，会导致两个项目不能创建同名质量规则，产品体验不合理；
     * 如果完全不去重，又会让同一项目内出现多个同名规则，运营后台难以识别。
     * 因此这里选择 `tenantId + projectId + name` 作为商业化更合理的唯一边界。</p>
     */
    private void ensureRuleNameNotDuplicated(Long tenantId, Long projectId, String name, Long currentId) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityRule::getTenantId, tenantId)
                .eq(QualityRule::getProjectId, projectId)
                .eq(QualityRule::getName, name)
                .ne(currentId != null, QualityRule::getId, currentId)
                .ne(QualityRule::getStatus, QualityRuleStatus.DELETED);
        if (qualityRuleMapper.selectCount(wrapper) > 0) {
            throw new IllegalArgumentException("质量规则名称已存在: " + name);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
