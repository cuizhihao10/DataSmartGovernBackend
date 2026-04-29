/**
 * @Author : Cui
 * @Date: 2026/04/28 19:34
 * @Description DataSmart Govern Backend - QualityTaskPayloadParser.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DATA_QUALITY_SCAN 任务载荷解析与校验器。
 *
 * <p>它的职责不是执行业务扫描，而是守住“任务合同”这道边界：
 * 1. data-quality 提交任务前，用它校验自己生成的 payload 是否完整；
 * 2. 未来质量执行器认领任务后，用它判断任务是否真的是可消费的质量扫描任务；
 * 3. 当 payload schema 升级时，用它集中维护兼容策略，而不是让每个执行器到处写 if/else。
 *
 * <p>这个类刻意放在 integration.task 包中，因为 payload 是 data-quality 与 task-management/执行器之间的集成合同，
 * 不是质量规则本身的领域实体，也不是前端请求 DTO。
 */
@Component
@RequiredArgsConstructor
public class QualityTaskPayloadParser {

    /**
     * 当前支持的质量扫描任务 payload 版本。
     */
    public static final String SUPPORTED_SCHEMA_VERSION = "QUALITY_SCAN_TASK_V1";

    /**
     * 载荷来源模块固定值。
     */
    public static final String SOURCE_MODULE = "data-quality";

    /**
     * 载荷业务类型固定值。
     */
    public static final String TASK_KIND = "QUALITY_SCAN";

    private final ObjectMapper objectMapper;

    /**
     * 解析并校验 JSON 字符串。
     *
     * <p>执行器从 task-management 拿到的 params 是字符串，因此需要先反序列化。
     * 反序列化成功不代表业务可执行，还要继续走 validate，防止缺字段、版本不支持或扫描计划不可调度。
     */
    public QualityTaskPayload parseAndValidate(String params) {
        if (params == null || params.isBlank()) {
            throw new IllegalArgumentException("质量任务 payload 不能为空");
        }
        try {
            QualityTaskPayload payload = objectMapper.readValue(params, QualityTaskPayload.class);
            validate(payload);
            return payload;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("质量任务 payload 不是合法 JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    /**
     * 校验结构化 payload。
     *
     * <p>这里返回 void 并在失败时抛异常，是为了让调用方明确区分“可执行”和“不可执行”。
     * 如果后续需要在前端展示所有校验错误，可以把 errors 列表作为 DTO 返回。
     */
    public void validate(QualityTaskPayload payload) {
        List<String> errors = new ArrayList<>();
        if (payload == null) {
            errors.add("payload 不能为空");
        } else {
            validateHeader(payload, errors);
            validateRuleSnapshot(payload, errors);
            validateScanPlan(payload, errors);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("质量任务 payload 校验失败: " + String.join("; ", errors));
        }
    }

    /**
     * 校验 payload 头部合同。
     *
     * <p>schemaVersion、sourceModule、taskKind 是执行器判断“能不能消费该任务”的第一层防线。
     */
    private void validateHeader(QualityTaskPayload payload, List<String> errors) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(payload.getSchemaVersion())) {
            errors.add("不支持的 schemaVersion=" + payload.getSchemaVersion());
        }
        if (!SOURCE_MODULE.equals(payload.getSourceModule())) {
            errors.add("sourceModule 必须为 " + SOURCE_MODULE);
        }
        if (!TASK_KIND.equals(payload.getTaskKind())) {
            errors.add("taskKind 必须为 " + TASK_KIND);
        }
        if (payload.getTenantId() != null && payload.getTenantId() < 0) {
            errors.add("tenantId 不能小于 0");
        }
    }

    /**
     * 校验规则快照。
     *
     * <p>这些字段既用于执行器回调，也用于任务排障和审计解释。
     */
    private void validateRuleSnapshot(QualityTaskPayload payload, List<String> errors) {
        if (payload.getRuleId() == null) {
            errors.add("ruleId 不能为空");
        }
        if (payload.getRuleName() == null || payload.getRuleName().isBlank()) {
            errors.add("ruleName 不能为空");
        }
        if (payload.getRuleVersion() == null) {
            errors.add("ruleVersion 不能为空");
        }
        if (payload.getComparisonOperator() == null || payload.getComparisonOperator().isBlank()) {
            errors.add("comparisonOperator 不能为空");
        }
        if (payload.getExpectedValue() == null) {
            errors.add("expectedValue 不能为空");
        }
    }

    /**
     * 校验扫描计划。
     *
     * <p>执行器只应该消费已经被 data-quality 判定为可调度的计划。
     * 如果计划不可调度还进入任务队列，说明提交入口或历史数据存在问题，应尽早失败而不是让执行器盲扫源系统。
     */
    private void validateScanPlan(QualityTaskPayload payload, List<String> errors) {
        if (payload.getScanPlan() == null) {
            errors.add("scanPlan 不能为空");
            return;
        }
        if (payload.getScanPlan().getRuleId() == null) {
            errors.add("scanPlan.ruleId 不能为空");
        }
        if (payload.getRuleId() != null && payload.getScanPlan().getRuleId() != null
                && !payload.getRuleId().equals(payload.getScanPlan().getRuleId())) {
            errors.add("payload.ruleId 与 scanPlan.ruleId 不一致");
        }
        if (payload.getScanPlan().getTargetType() == null || payload.getScanPlan().getTargetType().isBlank()) {
            errors.add("scanPlan.targetType 不能为空");
        }
        if (payload.getScanPlan().getExecutionMode() == null || payload.getScanPlan().getExecutionMode().isBlank()) {
            errors.add("scanPlan.executionMode 不能为空");
        }
        if (!Boolean.TRUE.equals(payload.getScanPlan().getSchedulable())) {
            errors.add("scanPlan 必须是可调度计划");
        }
    }
}
