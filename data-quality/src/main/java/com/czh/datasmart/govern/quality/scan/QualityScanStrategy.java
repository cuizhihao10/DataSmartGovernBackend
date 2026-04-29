/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - QualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;

import java.time.LocalDateTime;

/**
 * 质量扫描策略接口。
 *
 * <p>这是数据质量模块走向真实产品能力的一个关键抽象。
 * 不同数据目标的扫描方式差异很大：
 * 1. 关系型数据库需要连接数据源、读取元数据、构造 SQL；
 * 2. Kafka 需要消费 topic 样本、处理 offset、控制消费延迟；
 * 3. 文件需要识别格式、解析 schema、控制文件大小和采样行数；
 * 4. API 需要发起请求、校验状态码、解析响应结构。
 *
 * <p>如果把这些逻辑都写进 DataQualityServiceImpl，质量服务会快速变成一个难以维护的“上帝类”。
 * 因此这里先定义策略接口，让规则服务只关心“目标能否被当前策略识别”，
 * 具体扫描实现后续可以逐步扩展，不需要推翻规则生命周期和报告模型。
 */
public interface QualityScanStrategy {

    /**
     * 策略编码。
     *
     * <p>编码会写入 QualityRule.scanStrategy，便于后续调度器或扫描执行器根据规则路由到具体实现。
     */
    String strategyCode();

    /**
     * 判断当前策略是否支持某种目标类型。
     */
    boolean supports(QualityRuleTargetType targetType);

    /**
     * 校验规则目标是否满足当前策略的最低执行前提。
     *
     * <p>当前阶段主要做结构性校验，例如字段是否完整。
     * 后续接入 datasource-management 后，这里可以继续扩展为真实元数据校验：
     * 数据源是否存在、表是否存在、字段是否存在、账号是否有采样权限等。
     */
    QualityRuleTargetValidationResult validate(QualityRule rule);

    /**
     * 根据规则生成扫描计划。
     *
     * <p>默认实现返回“暂不支持调度”的计划，这样新增策略时不会因为还没实现计划生成而破坏编译。
     * 具体策略可以覆盖该方法，例如关系型策略会生成表/字段扫描计划。
     */
    default QualityScanPlan buildScanPlan(QualityRule rule, QualityScanPlanRequest request) {
        QualityScanPlan plan = new QualityScanPlan();
        plan.setRuleId(rule.getId());
        plan.setRuleName(rule.getName());
        plan.setRuleVersion(rule.getRuleVersion());
        plan.setTargetType(rule.getTargetType());
        plan.setScanStrategy(strategyCode());
        plan.setSchedulable(false);
        plan.setRiskLevel("MEDIUM");
        plan.setMessage("当前扫描策略暂未实现可调度扫描计划");
        plan.getSuggestions().add("可以先完成目标校验和规则配置，后续为该目标类型补充专用扫描计划生成逻辑。");
        plan.setPlannedTime(LocalDateTime.now());
        return plan;
    }
}
