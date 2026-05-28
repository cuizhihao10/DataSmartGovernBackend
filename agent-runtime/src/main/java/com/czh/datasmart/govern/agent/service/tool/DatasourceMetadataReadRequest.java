/**
 * @Author : Cui
 * @Date: 2026/05/24 21:06
 * @Description DataSmart Govern Backend - DatasourceMetadataReadRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

/**
 * Agent 调用 datasource-management 元数据发现接口时使用的本地请求契约。
 *
 * <p>这个类刻意放在 agent-runtime 模块内，而不是直接依赖 datasource-management 的
 * `MetadataDiscoveryRequest`，原因是微服务之间应通过 HTTP JSON 契约协作，而不是在 Java
 * 代码层互相引用对方的 Controller DTO。这样未来 datasource-management 可以独立发布，
 * agent-runtime 也可以把同一份工具契约迁移到 gateway、OpenFeign、Kafka 命令或 MCP-style
 * 工具适配层。</p>
 *
 * <p>字段设计原则：</p>
 * <p>1. actorId / actorRole / actorTenantId 表达“谁代表 Agent 发起探查”，用于下游审计与角色兜底；</p>
 * <p>2. catalog / schemaPattern / tableNamePattern 用于缩小元数据扫描范围，避免大库全量展开；</p>
 * <p>3. maxTables / maxColumnsPerTable / sampleRowLimit 是 Agent 侧第一道限额，下游仍会二次限额；</p>
 * <p>4. include* 开关用于控制响应信息量和风险等级，例如样本数据默认关闭，索引默认关闭。</p>
 */
public record DatasourceMetadataReadRequest(
        /**
         * Agent Runtime 代表的操作者 ID。
         * 当前会话 actorId 可能是 `u-001` 这类业务字符串，下游 DTO 需要 Long，
         * 因此构建请求时会做一次保守转换；转换失败时使用 0L 表示系统服务账号代理。
         */
        Long actorId,

        /**
         * 下游用于本地角色判断的角色名。
         * 这里固定为 AGENT_RUNTIME，表示该调用不是普通用户直接发起，而是受 Java 控制面治理后的工具执行。
         */
        String actorRole,

        /**
         * 当前租户 ID。
         * 即使 gateway 已经通过 Header 传递租户，body 中仍保留一份审计上下文，方便下游业务日志独立分析。
         */
        Long actorTenantId,

        /**
         * catalog 过滤条件。
         * MySQL、SQL Server 等连接器可能使用 catalog；部分数据库可能忽略该字段。
         */
        String catalog,

        /**
         * schema 过滤条件。
         * PostgreSQL、Oracle、SQL Server 等连接器常用 schema 组织对象。
         */
        String schemaPattern,

        /**
         * 表名过滤条件。
         * 真实商业场景中非常重要：大库全量扫描可能慢且噪声大，Agent 应优先根据用户意图缩小范围。
         */
        String tableNamePattern,

        /**
         * 本次最多返回多少张表。
         * Agent 侧会先裁剪到安全上限，下游 datasource-management 仍会依据自身配置再次裁剪。
         */
        Integer maxTables,

        /**
         * 每张表最多返回多少个字段。
         * 宽表可能包含上千字段，限制字段数可以保护响应大小、前端渲染和 Agent 上下文窗口。
         */
        Integer maxColumnsPerTable,

        /**
         * 是否返回字段明细。
         * 如果只需要表清单，可关闭该开关以降低下游探查成本。
         */
        Boolean includeColumns,

        /**
         * 是否包含视图。
         * 视图在治理场景中也有价值，但可能隐藏复杂 SQL，默认跟随计划参数显式控制。
         */
        Boolean includeViews,

        /**
         * 是否返回主键信息。
         * 主键是同步、去重、增量策略、质量唯一性规则生成的重要依据，因此默认开启。
         */
        Boolean includePrimaryKeys,

        /**
         * 是否返回索引信息。
         * 索引能帮助 Agent 判断扫描性能和增量字段候选，但比基础结构更重，所以默认关闭。
         */
        Boolean includeIndexes,

        /**
         * 是否返回样本行。
         * 样本行最容易涉及敏感数据，当前 agent-runtime 默认强制关闭，只有后续接入更严格审批/脱敏后才应开放。
         */
        Boolean includeSampleRows,

        /**
         * 每张表最多返回多少行样本。
         * 当 includeSampleRows 为 false 时该字段仍可传 0，表示本次不会请求样本数据。
         */
        Integer sampleRowLimit) {
}
