/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - QualityRuleTargetValidationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量规则目标校验结果。
 *
 * <p>这个对象用于把“校验结论”和“为什么这样判断”一起返回给前端或调用方。
 * 它不是简单的 true/false，因为真实产品里目标不可用的原因很多：
 * 1. 必填字段没填，例如字段级规则缺 fieldName；
 * 2. 目标类型当前平台还没有扫描策略；
 * 3. 数据源、表、字段存在性未来需要通过 datasource-management 远程确认；
 * 4. 规则虽然能保存，但暂时不适合启用进入生产检测。
 */
@Data
public class QualityRuleTargetValidationResult {

    /**
     * 被校验的规则 ID。
     *
     * <p>新建规则前的结构校验可以为空；对已保存规则校验时会返回具体 ID。
     */
    private Long ruleId;

    /**
     * 是否可以被当前平台识别为可扫描目标。
     */
    private Boolean valid;

    /**
     * 目标类型，例如 RELATIONAL_TABLE、RELATIONAL_FIELD、KAFKA_TOPIC。
     */
    private String targetType;

    /**
     * 命中的扫描策略编码。
     *
     * <p>它不是具体执行器实例，而是“未来应该交给哪类扫描器处理”的路由标识。
     */
    private String scanStrategy;

    /**
     * 校验状态：UNVALIDATED、VALIDATED、INVALID、UNSUPPORTED。
     */
    private String validationStatus;

    /**
     * 给人阅读的结论说明。
     */
    private String message;

    /**
     * 改进建议。
     *
     * <p>当校验失败或只是结构性通过时，建议可以告诉用户下一步需要补哪些字段、
     * 是否要接入数据源元数据、是否要做脱敏或采样策略。
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 校验时间。
     */
    private LocalDateTime validatedTime;
}
