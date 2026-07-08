/**
 * @Author : Cui
 * @Date: 2026/07/08 16:40
 * @Description DataSmart Govern Backend - SyncTaskCustomSqlCheckRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * SQL 语句模式创建向导的 SQL 检查请求。
 *
 * <p>这个 DTO 专门服务新建同步任务中的 {@code CUSTOM_SQL_QUERY / SQL语句} 模式。
 * 用户在页面上填写 SQL 后，前端调用 {@code POST /sync-tasks/create-wizard/sql/check}，
 * 后端会先做本地静态安全检查，再按需调用 datasource-management 的受控只读 SQL 探测能力。
 * 它不是任务执行入口，不会创建模板、不会创建任务、不会写目标端，也不会把查询样本数据返回给前端。</p>
 *
 * <p>字段设计原则：</p>
 * <p>1. 源端数据源是必需的，因为 SQL 语法、表是否存在、列别名推导都必须在源端连接器上验证；</p>
 * <p>2. 目标端数据源和目标对象不是 SQL 探测的必要条件，但保留字段是为了前端在同一个请求里携带上下文，
 * 后续可以基于目标表字段生成更强的字段映射建议；</p>
 * <p>3. {@code skipRemoteProbe} 用于前端输入过程中做轻量静态检查，例如用户每输入几秒触发一次检查时，
 * 可以先不打真实数据库，避免把频繁编辑变成频繁 SQL 执行。</p>
 */
@Data
public class SyncTaskCustomSqlCheckRequest {

    /**
     * 同步模式。
     *
     * <p>新建任务页面只应该在 {@code CUSTOM_SQL_QUERY} 模式下调用该接口。
     * 该字段允许为空是为了兼容前端直接在 SQL 输入框组件里调用检查；如果传入了其它模式，后端会返回阻断项，
     * 防止普通全量/定期任务误把过滤条件或表名当成 SQL 执行。</p>
     */
    @Size(max = 64, message = "同步模式不能超过 64 个字符")
    private String syncMode;

    /**
     * 源端数据源 ID。
     *
     * <p>data-sync 只把 ID 传给 datasource-management，不读取连接串和密码。
     * datasource-management 会根据该 ID 找到受控数据源配置，并在自己的权限、审计和超时边界内做只读探测。</p>
     */
    private Long sourceDatasourceId;

    /**
     * 目标端数据源 ID。
     *
     * <p>当前 SQL 检查不会直接访问目标端；它主要用于响应提示和后续字段映射增强。
     * SQL 语句模式真正写入目标端前，仍需要执行模板预检查来判断目标表是否存在、是否有主键/唯一约束、
     * 字段数量和类型是否能匹配。</p>
     */
    private Long targetDatasourceId;

    /**
     * 目标 schema 名。
     *
     * <p>PostgreSQL、Oracle、SQL Server 等连接器可能需要 schema；MySQL 通常以 database/catalog 表达类似概念。
     * 本接口不会强制解释该字段，只在响应建议中提示前端继续做目标表字段映射。</p>
     */
    @Size(max = 128, message = "目标 schema 名不能超过 128 个字符")
    private String targetSchemaName;

    /**
     * 目标表或目标对象名。
     *
     * <p>SQL 语句模式下对象映射只需要选择目标表，源端表和字段由 SQL 决定。
     * 因此该字段可以帮助后端判断“当前 SQL 输出列是否已经具备进入字段映射步骤的条件”。</p>
     */
    @Size(max = 256, message = "目标对象名不能超过 256 个字符")
    private String targetObjectName;

    /**
     * 用户填写的 SQL 正文。
     *
     * <p>该字段会进入 datasource-management 做受控只读探测，但 data-sync 不会把 SQL 写入日志，
     * 响应里也只返回 SHA-256 指纹、输出列和低敏提示。真正保存到模板时，仍应走模板创建/编辑接口的
     * {@code customSqlConfig} 合同，而不是把本检查接口当作保存入口。</p>
     */
    @Size(max = 20000, message = "SQL 长度不能超过 20000 个字符")
    private String sql;

    /**
     * 远程列探测的最大行数。
     *
     * <p>为了拿到 JDBC ResultSetMetaData，底层需要执行一次受限查询。默认只请求 1 行；
     * 即使 datasource-management 返回了样本行，data-sync 也会丢弃 rows，只保留列名、列数、耗时和 warning。</p>
     */
    private Integer maxRowsForColumnProbe;

    /**
     * 是否跳过远程探测。
     *
     * <p>true 表示只做本地静态检查，不访问真实数据源。这个模式适合用户输入过程中的实时提示；
     * false 或空表示在静态检查通过后继续调用 datasource-management 验证 SQL 是否真的能在源端执行。</p>
     */
    private Boolean skipRemoteProbe;
}
