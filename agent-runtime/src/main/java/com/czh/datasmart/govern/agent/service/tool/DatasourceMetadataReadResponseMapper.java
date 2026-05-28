/**
 * @Author : Cui
 * @Date: 2026/05/24 21:08
 * @Description DataSmart Govern Backend - DatasourceMetadataReadResponseMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * datasource-management 元数据发现响应解包器。
 *
 * <p>agent-runtime 调用下游业务服务时，不能简单把远端响应原样塞进工具输出。
 * 原因有三个：</p>
 * <p>1. Agent 审计需要稳定的失败分类，例如空响应、非 0 业务码、缺失 data；</p>
 * <p>2. 前端和后续编排器更需要“摘要”，而不是每次都解析完整元数据明细；</p>
 * <p>3. 工具输出未来可能进入模型上下文、事件流或对象存储，必须提前区分 summary 和 raw metadata。</p>
 */
@Component
public class DatasourceMetadataReadResponseMapper {

    /**
     * 把 datasource-management 的统一响应转换为 Agent 工具执行结果。
     *
     * @param datasourceId 当前工具读取的数据源 ID，用于输出摘要和审计排障
     * @param response 下游返回的统一响应 Map，形如 `{code,message,data}`
     * @return Agent 工具执行结果；成功时包含 summary 与 metadata，失败时包含稳定 errorCode
     */
    public AgentToolExecutionOutcome toOutcome(Long datasourceId, Map<String, Object> response) {
        if (response == null) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_EMPTY_RESPONSE",
                    "datasource-management 返回空响应，无法确认元数据发现是否成功");
        }
        Integer code = integerValue(response.get("code"), 0);
        if (code != 0) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_METADATA_FAILED",
                    "datasource-management 元数据发现失败: " + safeMessage(response.get("message")));
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> rawData)) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_METADATA_MISSING_DATA",
                    "datasource-management 响应缺少 data，工具无法生成元数据摘要");
        }

        Map<String, Object> metadata = copyStringKeyMap(rawData);
        Map<String, Object> summary = buildSummary(metadata);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasourceId", datasourceId);
        output.put("remoteMessage", response.get("message"));
        output.put("summary", summary);
        output.put("metadata", metadata);
        return AgentToolExecutionOutcome.succeeded("数据源元数据读取成功，已生成受控发现摘要。", output);
    }

    /**
     * 从完整元数据结果中生成更适合 Agent 审计、前端卡片和后续编排判断的摘要。
     *
     * <p>这里不假设下游一定返回强类型对象，而是按 JSON Map 提取关键字段。
     * 这样即使 datasource-management 后续独立演进字段，只要保留基础字段，本工具仍能兼容。</p>
     */
    private Map<String, Object> buildSummary(Map<String, Object> metadata) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("datasourceName", metadata.get("datasourceName"));
        summary.put("datasourceType", metadata.get("datasourceType"));
        summary.put("productName", metadata.get("productName"));
        summary.put("tableCount", integerValue(metadata.get("tableCount"), 0));
        summary.put("columnCount", countReturnedColumns(metadata.get("tables")));
        summary.put("truncated", isTruncated(metadata));
        summary.put("cacheHit", booleanValue(metadata.get("cacheHit"), false));
        summary.put("discoveryDurationMs", metadata.get("discoveryDurationMs"));
        summary.put("warnings", metadata.get("warnings"));
        return summary;
    }

    /**
     * 统计当前响应中实际返回的字段数量。
     * 注意它不是数据库真实字段总数，而是本次响应体中展开给 Agent 的字段数量。
     */
    private int countReturnedColumns(Object tablesValue) {
        if (!(tablesValue instanceof List<?> tables)) {
            return 0;
        }
        int count = 0;
        for (Object table : tables) {
            if (table instanceof Map<?, ?> tableMap) {
                Object columnCount = tableMap.get("columnCount");
                count += integerValue(columnCount, 0);
            }
        }
        return count;
    }

    /**
     * 判断结果是否被截断。
     * 当前依据包括：任意表字段被截断，或者返回表数达到本次应用的 maxTables。
     */
    private boolean isTruncated(Map<String, Object> metadata) {
        Object tablesValue = metadata.get("tables");
        if (tablesValue instanceof List<?> tables) {
            for (Object table : tables) {
                if (table instanceof Map<?, ?> tableMap && booleanValue(tableMap.get("columnsTruncated"), false)) {
                    return true;
                }
            }
        }
        int tableCount = integerValue(metadata.get("tableCount"), 0);
        int appliedMaxTables = integerValue(metadata.get("appliedMaxTables"), Integer.MAX_VALUE);
        return tableCount > 0 && tableCount >= appliedMaxTables;
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private Integer integerValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return defaultValue;
    }

    private String safeMessage(Object value) {
        if (value == null) {
            return "下游未返回错误说明";
        }
        String message = String.valueOf(value);
        return message.isBlank() ? "下游未返回错误说明" : message;
    }
}
