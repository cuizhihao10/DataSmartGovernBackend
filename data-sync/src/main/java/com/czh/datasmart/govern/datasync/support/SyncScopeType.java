/**
 * @Author : Cui
 * @Date: 2026/07/05 23:10
 * @Description DataSmart Govern Backend - SyncScopeType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据同步范围类型。
 *
 * <p>同步模式 {@link SyncMode} 回答“怎么搬”，例如全量、增量、CDC、回放；
 * 同步范围回答“搬哪些对象”，例如单表、多表、整个 schema、整个库或自定义查询结果。
 * 把二者拆开是为了避免产品能力继续被单表复制模型锁死：</p>
 *
 * <p>1. 同一个 FULL 模式既可能是单表全量，也可能是多表全量或整库迁移；</p>
 * <p>2. 同一个源端/目标端组合下，不同范围需要完全不同的预检查、审批、并发和回滚策略；</p>
 * <p>3. worker 执行器可以先只支持 SINGLE_OBJECT，但控制面必须能明确表达更大的产品范围，
 * 并在执行层 fail-closed，而不是把未实现能力伪装成已支持。</p>
 */
public enum SyncScopeType {

    /**
     * 单个逻辑对象同步。
     *
     * <p>这是当前最小真实执行闭环已经支持的范围，通常对应一张表、一个视图、一个 topic、
     * 一个文件逻辑对象或一个 API 资源。该范围必须声明 sourceObjectName 与 targetObjectName。</p>
     */
    SINGLE_OBJECT,

    /**
     * 多对象选择同步。
     *
     * <p>用于用户在创建任务阶段勾选多张表或多个逻辑对象，并为每个对象设置目标对象名、字段映射、
     * 写入策略和过滤策略。该范围必须通过 objectMappingConfig 声明对象映射清单。</p>
     */
    OBJECT_LIST,

    /**
     * 整个 schema 同步或迁移。
     *
     * <p>适合 PostgreSQL schema、SQL Server schema、Hive database 等有明确命名空间的场景。
     * 该范围必须声明 sourceSchemaName 与 targetSchemaName，并建议在预检阶段由 datasource-management
     * 做元数据发现、对象枚举、DDL 兼容性和容量估算。</p>
     */
    SCHEMA_FULL,

    /**
     * 整个数据源或数据库级迁移。
     *
     * <p>这是影响面最大的范围，通常需要管理员审批、容量评估、目标端命名策略、对象排除规则、
     * 分批执行计划和回滚预案。当前控制面允许建模，但执行前必须完成显式迁移策略预检。</p>
     */
    DATABASE_FULL,

    /**
     * 自定义 SQL 查询结果同步。
     *
     * <p>自定义 SQL 不应混入普通 filterConfig，因为它比 where 条件风险更高：
     * SQL 可能跨表、包含聚合、暴露业务口径，甚至被误写成 DDL/DML。
     * 因此它必须独立成范围类型，并且只允许只读 SELECT/WITH 查询、强制审计、强制审批和低敏响应。</p>
     */
    CUSTOM_SQL_QUERY
}
