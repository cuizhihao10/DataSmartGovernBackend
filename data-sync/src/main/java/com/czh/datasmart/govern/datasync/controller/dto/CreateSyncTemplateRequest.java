/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - CreateSyncTemplateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建同步模板请求。
 *
 * <p>该请求只保存同步配置，不直接触发执行。
 * 后续真实产品中，模板创建后通常还会经历校验、预览、审批、生成任务、调度等环节。
 */
@Data
public class CreateSyncTemplateRequest {

    /**
     * 租户 ID。普通用户场景下服务端会优先使用 gateway 透传租户，平台管理员或服务账号才允许显式指定。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>用于租户内部二级隔离。项目负责人、项目级同步看板、项目级配额和成本统计都会依赖该字段。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>用于多团队协作空间和空间级筛选。当前可为空，后续如果前端已经有 workspace 上下文，应优先传入。
     */
    private Long workspaceId;

    @NotBlank(message = "同步模板名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "源数据源 ID 不能为空")
    private Long sourceDatasourceId;

    @NotNull(message = "目标数据源 ID 不能为空")
    private Long targetDatasourceId;

    /**
     * 源端 schema 名称。
     *
     * <p>该字段用于执行器定位源端对象所在命名空间，例如 MySQL 的库名、PostgreSQL 的 schema 名称。
     * 它不包含连接地址、账号、密钥或 SQL 条件；如果某类连接器没有 schema 概念，可以为空。</p>
     */
    private String sourceSchemaName;

    /**
     * 源端对象名称。
     *
     * <p>该字段用于表达“从哪个逻辑对象读取”，例如表名、视图名、topic 逻辑名或 API 资源名。它是进入真实执行闭环的必要配置，
     * 但仍然不是 SQL，也不包含 where 条件、样本数据或完整文件路径。服务端会按安全标识符规则做校验，避免后续 runner
     * 被迫处理拼接 SQL 风险。</p>
     */
    private String sourceObjectName;

    /**
     * 目标端 schema 名称。
     *
     * <p>含义与 sourceSchemaName 对称，用于表达写入目标的命名空间。生产环境中它还可以参与权限、配额和审计分组。</p>
     */
    private String targetSchemaName;

    /**
     * 目标端对象名称。
     *
     * <p>该字段用于表达“写入到哪个逻辑对象”。如果缺少该字段，worker 即使拿到 datasourceId 和 syncMode，
     * 也无法形成可执行的写入计划。</p>
     */
    private String targetObjectName;

    /**
     * 源端连接器类型，例如 MYSQL、POSTGRESQL、KAFKA、OBJECT_STORAGE。
     *
     * <p>这个字段是低敏控制面信息，只表达“源端属于哪类系统”，不表达真实 host、port、database、topic、
     * bucket、文件路径、账号或密钥。当前它是可选字段：调用方只传 datasourceId 时，服务端会优先调用
     * datasource-management 的低敏能力快照自动补全 connector type；如果快照补全在本地环境被关闭，
     * 两端都缺省时仍按历史兼容逻辑只做基础校验。</p>
     */
    private String sourceConnectorType;

    /**
     * 目标端连接器类型。
     *
     * <p>目标端字段与源端字段遵循同一低敏原则。推荐调用方只传 datasourceId，让服务端按可信快照补全；
     * 如果调用方显式传入 connector type，则服务端会先归一化，再结合能力矩阵判断源端、目标端和 syncMode
     * 是否匹配。最终进入模板校验时如果只剩一端 connector type，仍会 fail-closed 拒绝，避免把“半个能力事实”
     * 误当成可执行配置。</p>
     */
    private String targetConnectorType;

    /**
     * 同步模式编码，例如 FULL、INCREMENTAL_TIME、CDC_STREAMING。
     */
    @NotBlank(message = "同步模式不能为空")
    private String syncMode;

    /**
     * 同步范围类型。
     *
     * <p>为空时服务端按 SINGLE_OBJECT 兼容历史调用方。
     * 当用户选择“多表同步”“全库/全 schema 迁移”或“自定义 SQL 查询传输”时，前端或 Agent
     * 应显式传入 OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL 或 CUSTOM_SQL_QUERY。
     * 该字段不替代 syncMode：syncMode 仍然表示全量/增量/定时/CDC 等执行方式，syncScopeType 表示对象范围。</p>
     */
    private String syncScopeType;

    /**
     * 目标端写入策略。
     *
     * <p>可选值包括 APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE。为空时服务端会按历史兼容回落到 APPEND，
     * 但预览和 workerPlan 会提示默认追加写入的重试/回放重复风险。真实生产中，关键主数据通常应显式选择 UPSERT
     * 并声明 primaryKeyField。</p>
     */
    private String writeStrategy;

    /**
     * 主键或冲突判断字段。
     *
     * <p>当 writeStrategy 为 UPSERT、INSERT_IGNORE、REPLACE 时必须声明。这里保存字段名，不保存任何字段值；
     * 字段值只应存在于受控 worker 的读写上下文和目标端数据库中。</p>
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     *
     * <p>INCREMENTAL_TIME 与 INCREMENTAL_ID 模式必须声明该字段，用于 checkpoint 推进。它只保存字段名，
     * 不保存 checkpoint 原始值、窗口边界或业务过滤条件。</p>
     */
    private String incrementalField;

    private String fieldMappingConfig;

    /**
     * 多对象/全库范围映射配置。
     *
     * <p>用于表达“选择哪些表”“源表到目标表如何映射”“是否按通配符包含/排除对象”“目标命名策略是什么”。
     * 该字段必须是 JSON 文本，普通预览和审计不会回显原文。
     * 单表同步时可以为空；OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL 则应至少声明映射清单或发现策略。</p>
     */
    private String objectMappingConfig;

    private String filterConfig;

    /**
     * 自定义 SQL 查询配置。
     *
     * <p>只适用于 CUSTOM_SQL_QUERY 范围/模式。
     * 当前允许以 JSON 形式传入只读 SQL 或 statementRef，例如：
     * {"sql":"select id,name from customer where status = :status","parameters":[{"name":"status","type":"STRING"}]}。
     * 服务端会做只读 SQL 安全校验，但不会在响应、审计、worker plan 中回显 SQL 正文。</p>
     */
    private String customSqlConfig;

    private String partitionConfig;
    private String retryPolicy;
    private String timeoutPolicy;
}
