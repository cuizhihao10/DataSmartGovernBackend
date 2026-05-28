/**
 * @Author : Cui
 * @Date: 2026/05/25 00:24
 * @Description DataSmart Govern Backend - TaskDraftParamValidationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任务草稿参数 schema 校验支持组件。
 *
 * <p>任务草稿的 `params` 字段当前是 JSON 字符串，这是为了兼容不同任务类型：
 * 质量扫描、数据同步、人工复核、资产盘点、合规脱敏等任务的参数结构天然不同。
 * 但“字符串能解析成 JSON”并不等于“任务能执行”。如果完全不校验，
 * Agent 或前端可能保存一个结构合法但业务不可执行的草稿，直到转换真实任务后才在执行器里失败。</p>
 *
 * <p>该组件的职责是提供第一版轻量 schema 校验：</p>
 * <p>1. 保存或更新草稿时，尽早发现明显缺失的关键参数；</p>
 * <p>2. 转换真实任务前，再做一次最终校验，避免旧草稿或人工改库绕过校验；</p>
 * <p>3. 不把校验逻辑写死在 Controller 或 ServiceImpl 中，避免任务类型越来越多时主流程膨胀。</p>
 *
 * <p>当前校验是“宽入口 + 关键字段下限”，不是最终强 JSON Schema。
 * 这样可以兼容 Agent 早期输出、人工草稿、模板草稿三种来源，同时为后续升级到标准 JSON Schema 或模板化校验保留接口。</p>
 */
@Component
public class TaskDraftParamValidationSupport {

    private static final String DATA_QUALITY_SCAN = "DATA_QUALITY_SCAN";
    private static final String DATA_SYNC = "DATA_SYNC";
    private static final String MANUAL_REVIEW = "MANUAL_REVIEW";

    private final ObjectMapper objectMapper;

    public TaskDraftParamValidationSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验任务草稿参数。
     *
     * @param taskType 草稿目标任务类型，例如 DATA_QUALITY_SCAN、DATA_SYNC、MANUAL_REVIEW。
     * @param paramsJson 草稿参数 JSON 字符串；为空时会被视为 `{}`。
     * @param actionName 当前业务动作名称，用于生成更可读的错误提示。
     */
    public void validate(String taskType, String paramsJson, String actionName) {
        String normalizedType = normalizeTaskType(taskType);
        Map<String, Object> params = parseParams(paramsJson, actionName);
        switch (normalizedType) {
            case DATA_QUALITY_SCAN -> validateDataQualityScan(params, actionName);
            case DATA_SYNC -> validateDataSync(params, actionName);
            case MANUAL_REVIEW -> validateManualReview(params, actionName);
            default -> validateUnknownTaskType(params, normalizedType, actionName);
        }
    }

    /**
     * 校验数据质量扫描任务参数。
     *
     * <p>质量扫描任务至少要知道“扫描什么规则或扫描计划”。
     * 当前兼容三类来源：</p>
     * <p>1. `ruleIds`：人工或规则管理页面选择的已保存规则 ID；</p>
     * <p>2. `qualityRuleSuggestions`：Agent 根据元数据生成的规则草案，后续可转成真实规则；</p>
     * <p>3. `scanPlan`：更结构化的扫描计划，未来可以包含表、字段、抽样、阈值、执行窗口等。</p>
     */
    private void validateDataQualityScan(Map<String, Object> params, String actionName) {
        if (nonEmptyList(params.get("ruleIds"))
                || nonEmptyList(params.get("qualityRuleSuggestions"))
                || objectMap(params.get("scanPlan")) != null) {
            return;
        }
        throw new IllegalArgumentException(actionName
                + " DATA_QUALITY_SCAN 草稿参数不完整：必须提供 ruleIds、qualityRuleSuggestions 或 scanPlan 之一");
    }

    /**
     * 校验数据同步任务参数。
     *
     * <p>数据同步任务有两种常见创建方式：</p>
     * <p>1. 基于模板：提供 `syncTemplateId`，后续由 data-sync 读取模板里的源、目标、字段映射和同步模式；</p>
     * <p>2. 直接配置：提供 sourceDatasourceId、targetDatasourceId 和 syncMode。</p>
     *
     * <p>第一版先要求最小字段，后续再扩展字段映射、增量字段、分片策略、冲突策略、CDC offset 等更细 schema。</p>
     */
    private void validateDataSync(Map<String, Object> params, String actionName) {
        if (hasValue(params.get("syncTemplateId"))) {
            return;
        }
        boolean hasSource = hasValue(firstNonNull(params.get("sourceDatasourceId"), params.get("sourceId")));
        boolean hasTarget = hasValue(firstNonNull(params.get("targetDatasourceId"), params.get("targetId")));
        String syncMode = text(params.get("syncMode"));
        if (hasSource && hasTarget && supportedSyncMode(syncMode)) {
            return;
        }
        throw new IllegalArgumentException(actionName
                + " DATA_SYNC 草稿参数不完整：必须提供 syncTemplateId，或同时提供 sourceDatasourceId、targetDatasourceId、syncMode");
    }

    /**
     * 校验人工复核任务参数。
     *
     * <p>人工复核虽然不需要执行器自动扫描数据，但也不能是空任务。
     * 至少需要 objective/reviewTarget/checklist 之一，让审批人和负责人知道要复核什么。</p>
     */
    private void validateManualReview(Map<String, Object> params, String actionName) {
        if (hasValue(params.get("objective"))
                || hasValue(params.get("reviewTarget"))
                || nonEmptyList(params.get("checklist"))) {
            return;
        }
        throw new IllegalArgumentException(actionName
                + " MANUAL_REVIEW 草稿参数不完整：必须提供 objective、reviewTarget 或 checklist 之一");
    }

    /**
     * 未知任务类型的兜底校验。
     *
     * <p>平台未来会继续增加资产盘点、合规脱敏、运维巡检等任务类型。
     * 对未知类型当前不做强约束，但至少要求 params 是 JSON 对象。
     * 这样既不阻塞新类型灰度，也避免数组、字符串这类不利于扩展的参数结构进入任务中心。</p>
     */
    private void validateUnknownTaskType(Map<String, Object> params, String taskType, String actionName) {
        if (params == null) {
            throw new IllegalArgumentException(actionName + " " + taskType + " 草稿参数必须是 JSON 对象");
        }
    }

    private Map<String, Object> parseParams(String paramsJson, String actionName) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(paramsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(actionName + " 草稿参数不是合法 JSON 对象: " + ex.getMessage(), ex);
        }
    }

    private boolean supportedSyncMode(String syncMode) {
        if (syncMode == null) {
            return false;
        }
        return List.of("FULL", "INCREMENTAL", "CDC", "SCHEDULED_BATCH", "BACKFILL", "REPLAY", "OFFLINE_IMPORT", "OFFLINE_EXPORT")
                .contains(syncMode.trim().toUpperCase(Locale.ROOT));
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private Map<?, ?> objectMap(Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty() ? map : null;
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        return true;
    }

    private String normalizeTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("任务草稿类型不能为空，无法执行参数 schema 校验");
        }
        return taskType.trim().toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }
}
