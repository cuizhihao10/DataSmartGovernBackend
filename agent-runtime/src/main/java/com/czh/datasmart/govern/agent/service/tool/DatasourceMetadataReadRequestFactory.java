/**
 * @Author : Cui
 * @Date: 2026/05/24 21:07
 * @Description DataSmart Govern Backend - DatasourceMetadataReadRequestFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * `datasource.metadata.read` 工具请求构建器。
 *
 * <p>把这段逻辑从适配器中拆出来，是为了避免 `DatasourceMetadataReadToolAdapter`
 * 同时承担 HTTP 调用、参数治理、限额裁剪、响应摘要等过多职责。真实产品中的工具适配器会越来越多，
 * 如果每个适配器都把所有规则写在一个类里，很快会形成 500 行以上的大文件，也不利于测试。</p>
 *
 * <p>本类负责的不是“业务库元数据怎么读取”，而是“Agent 计划参数如何被清洗成安全的下游请求”：</p>
 * <p>1. 从审计记录的 planArguments 读取 Python AgentPlan 规划出的参数；</p>
 * <p>2. 对 maxTables、maxColumnsPerTable 等数值做默认值和最大值裁剪；</p>
 * <p>3. 对 includeSampleRows 这类高风险开关做保守降级；</p>
 * <p>4. 保留 catalog/schema/tableNamePattern，方便 Agent 针对用户意图缩小扫描范围。</p>
 */
@Component
public class DatasourceMetadataReadRequestFactory {

    /**
     * Agent 侧默认最多读取 50 张表。
     * 这个值低于 datasource-management 的绝对上限，目的是让 Agent 工具调用天然更保守。
     */
    private static final int DEFAULT_MAX_TABLES = 50;

    /**
     * Agent 侧允许的最大表数量。
     * 如果 Python AgentPlan 误把 maxTables 设得很大，Java 控制面会先裁剪，避免把压力直接传给下游。
     */
    private static final int AGENT_MAX_TABLES = 100;

    /**
     * Agent 侧默认每表最多读取 200 个字段。
     * 这个默认值足以覆盖大多数治理配置页，同时不会轻易撑爆响应体和模型上下文。
     */
    private static final int DEFAULT_MAX_COLUMNS_PER_TABLE = 200;

    /**
     * Agent 侧允许的最大每表字段数。
     * 下游目前绝对上限更高，但 Agent 上下文通常比前端页面更怕大响应，因此这里设置更谨慎。
     */
    private static final int AGENT_MAX_COLUMNS_PER_TABLE = 300;

    /**
     * 当前阶段样本行默认完全关闭。
     * 后续如果接入字段脱敏、审批单、敏感级别识别和样本访问审计，可以把这里升级为可配置策略。
     */
    private static final int DISABLED_SAMPLE_ROW_LIMIT = 0;

    /**
     * 根据当前工具执行上下文构建 datasource-management 的元数据发现请求。
     *
     * @param context 当前工具执行上下文，包含会话、Run、审计快照和运行变量
     * @return 已经完成默认值填充、上限裁剪和敏感开关降级的请求对象
     */
    public DatasourceMetadataReadRequest build(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        return new DatasourceMetadataReadRequest(
                parseActorId(context.session().getActorId()),
                "AGENT_RUNTIME",
                context.session().getTenantId(),
                stringValue(arguments.get("catalog")),
                stringValue(arguments.get("schemaPattern")),
                stringValue(arguments.get("tableNamePattern")),
                boundedInteger(arguments.get("maxTables"), DEFAULT_MAX_TABLES, AGENT_MAX_TABLES),
                boundedInteger(arguments.get("maxColumnsPerTable"),
                        DEFAULT_MAX_COLUMNS_PER_TABLE,
                        AGENT_MAX_COLUMNS_PER_TABLE),
                booleanValue(arguments.get("includeColumns"), true),
                booleanValue(arguments.get("includeViews"), true),
                booleanValue(arguments.get("includePrimaryKeys"), true),
                booleanValue(arguments.get("includeIndexes"), false),
                false,
                DISABLED_SAMPLE_ROW_LIMIT
        );
    }

    /**
     * 把会话 actorId 转换为下游 DTO 要求的 Long。
     *
     * <p>当前 Agent 会话中的 actorId 可能来自前端用户、服务账号、企业 IM 或未来多渠道入口，
     * 不一定天然是纯数字。为了不让工具调用因为展示型 ID 格式失败，这里做保守转换：
     * 能提取数字则提取，不能提取则使用 0L 表示系统代理。</p>
     */
    private Long parseActorId(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return 0L;
        }
        try {
            String digits = actorId.replaceAll("[^0-9]", "");
            return digits.isBlank() ? 0L : Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 读取字符串参数。
     * 空字符串会被转成 null，让下游按“不限制该维度”处理，而不是传入一个无意义的空过滤条件。
     */
    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    /**
     * 读取并裁剪整数参数。
     * 这里同时兼容 Number 和字符串，方便 Python Runtime 或未来 MCP 工具调用层用 JSON 数字/文本传参。
     */
    private Integer boundedInteger(Object value, int defaultValue, int maxValue) {
        int resolved = defaultValue;
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                resolved = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                resolved = defaultValue;
            }
        }
        if (resolved < 1) {
            resolved = defaultValue;
        }
        return Math.min(resolved, maxValue);
    }

    /**
     * 读取布尔参数。
     * JSON boolean、字符串 true/false 都可识别；缺失时使用工具级安全默认值。
     */
    private Boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return defaultValue;
    }
}
