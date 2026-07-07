/**
 * @Author : Cui
 * @Date: 2026/05/07 21:27
 * @Description DataSmart Govern Backend - SyncTemplate.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步模板实体。
 *
 * <p>同步模板保存的是“可复用的同步配置”，不是某一次运行记录。
 * 真实产品中，同一个模板可能被手动运行、定时运行、失败重试、历史回放或补数多次使用。
 * 因此模板要和任务、执行记录、checkpoint 分开建模，避免后续为了保存运行状态不断修改配置定义。
 */
@Data
@TableName("data_sync_template")
public class SyncTemplate {

    /**
     * 模板主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     * 多租户平台必须从最早的数据模型就保留租户边界，否则后续权限、配额、审计和账单都会返工。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>租户只能表达“属于哪个公司/组织”，项目用于表达租户内部的业务域、团队或数据产品边界。
     * permission-admin 的 PROJECT 数据范围最终会落到该字段，避免项目负责人看到同租户其他项目的同步模板。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>workspaceId 比 projectId 更偏产品协作空间，可用于多环境、多团队看板、空间级配额和空间级审计。
     * 当前先作为可选字段保留，后续如果平台把项目和空间统一建模，也可以在服务层映射为同一个治理域。
     */
    private Long workspaceId;

    /**
     * 模板名称。
     * 主要面向用户识别，例如“CRM 客户表每日同步到数仓”。
     */
    private String name;

    /**
     * 模板描述。
     */
    private String description;

    /**
     * 源数据源 ID。
     * 只保存 datasource-management 中登记的数据源 ID，不在本表重复保存 JDBC URL、密码等敏感连接信息。
     */
    private Long sourceDatasourceId;

    /**
     * 目标数据源 ID。
     */
    private Long targetDatasourceId;

    /**
     * 源端 schema 名称。
     *
     * <p>schema 是执行器定位源端对象的低敏结构化元数据。它只表达“对象位于哪个逻辑命名空间”，不包含
     * JDBC URL、host、port、database 连接地址、账号、密钥或 SQL 条件。对于 MySQL 可理解为库名，
     * 对 PostgreSQL/SQL Server 可理解为 schema；对于文件、API、消息队列等非关系型连接器，后续可以为空或由
     * connector runtime 解释为命名空间。</p>
     */
    private String sourceSchemaName;

    /**
     * 源端对象名称。
     *
     * <p>真实 batch runner 至少需要知道从哪个表、视图、topic、文件逻辑对象或 API 资源读取数据。过去 data-sync
     * 只保存 datasourceId 和 syncMode，导致 workerPlan 只能说明“从哪个数据源到哪个数据源”，却无法说明“同步哪个对象”，
     * 这会阻塞执行闭环。因此这里补充对象名，但仍不保存 SQL、where 条件、样本数据或完整文件路径。</p>
     */
    private String sourceObjectName;

    /**
     * 目标端 schema 名称。
     *
     * <p>含义与 sourceSchemaName 对称，用于后续写入器定位目标对象所在命名空间。该字段属于配置元数据，
     * 不应被写入普通审计摘要、runtime event 或低敏投影正文。</p>
     */
    private String targetSchemaName;

    /**
     * 目标端对象名称。
     *
     * <p>执行器需要根据该字段确定写入目标。它和 targetDatasourceId 共同组成“写到哪里”的低敏定位契约；
     * 真正的连接、事务、SQL 模板和凭据仍由受控 connector runtime 根据 datasourceId 读取。</p>
     */
    private String targetObjectName;

    /**
     * 源端连接器类型。
     *
     * <p>该字段冗余保存的是低敏能力事实快照，例如 MYSQL、POSTGRESQL、KAFKA。
     * 它不是 datasource-management 的替代品，不保存连接串、库名、topic、bucket、账号或密钥。
     * 冗余它的原因是模板校验、任务创建、执行器调度和运营查询都需要快速知道“这个模板大致属于哪类连接器组合”，
     * 避免每次都跨服务回查 datasource-management。</p>
     */
    private String sourceConnectorType;

    /**
     * 目标端连接器类型。
     *
     * <p>与 sourceConnectorType 配合后，data-sync 可以判断 FULL、INCREMENTAL、CDC、OFFLINE_EXPORT 等模式是否适合当前源/目标组合。
     * 例如 Kafka 不应被当作传统 FULL 表同步源，文件目标也不应直接承接 CDC_STREAMING。</p>
     */
    private String targetConnectorType;

    /**
     * 同步模式。
     *
     * <p>面向新建任务/模板的一级传输模式只允许 FULL、SCHEDULED_FULL、SCHEDULED_BATCH、
     * CUSTOM_SQL_QUERY、CDC_STREAMING。历史增量、回放、补数、离线导入导出等枚举只作为内部恢复、
     * 运维动作或兼容数据存在，不能再由前端新建任务页面直接选择。</p>
     */
    private String syncMode;

    /**
     * 同步范围类型。
     *
     * <p>syncMode 解决“如何同步”，syncScopeType 解决“同步哪些对象”。
     * 历史模板没有该字段时会按 SINGLE_OBJECT 兼容处理，即使用 sourceObjectName 与 targetObjectName
     * 表示单表/单对象同步。新增该字段后，产品可以明确表达：</p>
     * <p>1. OBJECT_LIST：用户勾选多张表并逐表配置映射；</p>
     * <p>2. SCHEMA_FULL：迁移整个 schema；</p>
     * <p>3. DATABASE_FULL：迁移整个数据源/数据库，通常需要更强审批；</p>
     * <p>4. CUSTOM_SQL_QUERY：把受控只读 SQL 查询结果写入目标对象。</p>
     *
     * <p>执行器暂时没有实现的范围会在 worker bridge 中 fail-closed，
     * 但控制面必须先把范围语义建模清楚，避免继续把“单表同步”误当成整个数据同步产品。</p>
     */
    private String syncScopeType;

    /**
     * 目标端写入策略。
     *
     * <p>常见值包括 APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE。该字段决定 runner 如何处理目标端冲突：
     * APPEND 只追加，UPSERT 需要主键或唯一键，OVERWRITE 具有覆盖风险。它是执行闭环的关键控制字段，
     * 因为不同写入策略会影响幂等、重试、回放、补数和人工审批要求。</p>
     */
    private String writeStrategy;

    /**
     * 主键或冲突判断字段。
     *
     * <p>当 writeStrategy 为 UPSERT、INSERT_IGNORE、REPLACE 等需要冲突判断的策略时，必须声明该字段。
     * 这里保存的是字段名，不保存字段值；字段值只会在真实执行时留在受控 worker 内存和目标端写入语句中。</p>
     */
    private String primaryKeyField;

    /**
     * 增量字段。
     *
     * <p>INCREMENTAL_TIME 与 INCREMENTAL_ID 模式必须知道用哪个字段推进 checkpoint。该字段只保存字段名，
     * 不保存 checkpoint 值、时间窗口、业务条件或样本数据；真实水位值应进入 checkpoint 表或 worker 内部状态。</p>
     */
    private String incrementalField;

    /**
     * 字段映射配置 JSON。
     * 真实项目中这里会包含源字段、目标字段、类型转换、默认值、脱敏规则和字段级异常处理策略。
     */
    private String fieldMappingConfig;

    /**
     * 对象映射配置 JSON。
     *
     * <p>当 syncScopeType 为 OBJECT_LIST、SCHEMA_FULL 或 DATABASE_FULL 时，单独的 sourceObjectName/targetObjectName
     * 已经不足以表达用户在创建任务阶段选择的多张表、排除规则、目标命名策略和逐表字段映射。
     * 因此这里保存一个受控 JSON 配置，例如：</p>
     * <pre>
     * {
     *   "mappings": [
     *     {"sourceObject": "orders", "targetObject": "ods_orders"},
     *     {"sourceObject": "customers", "targetObject": "ods_customers"}
     *   ],
     *   "includePatterns": ["biz_*"],
     *   "excludeObjects": ["tmp_table"],
     *   "targetNamingStrategy": "KEEP_NAME"
     * }
     * </pre>
     *
     * <p>该字段仍然不能保存连接串、账号、密码、样本数据或完整导出文件路径。
     * 普通预览和审计只返回“是否声明”和对象数量摘要，不返回 JSON 正文。</p>
     */
    private String objectMappingConfig;

    /**
     * 过滤条件配置 JSON。
     * 例如按业务日期、租户字段、状态字段过滤源端数据。
     */
    private String filterConfig;

    /**
     * 自定义 SQL 配置 JSON。
     *
     * <p>该字段只用于 syncScopeType=CUSTOM_SQL_QUERY 或 syncMode=CUSTOM_SQL_QUERY。
     * 自定义 SQL 比普通 where/filterConfig 风险更高，必须独立存储、独立校验、独立审批：</p>
     * <p>1. 只允许只读 SELECT/WITH 查询；</p>
     * <p>2. 禁止 DDL/DML、存储过程、COPY、分号多语句和注释逃逸；</p>
     * <p>3. 预检响应、审计摘要、worker plan 不返回 SQL 正文，只返回 digest/声明状态/风险码；</p>
     * <p>4. 真实执行时仍应由 datasource-management 读取受控 datasource 凭据并参数化执行。</p>
     */
    private String customSqlConfig;

    /**
     * 分区与并发配置 JSON。
     * 例如按 id range、日期分区、hash 分片进行并行同步。
     */
    private String partitionConfig;

    /**
     * 重试策略 JSON。
     * 例如最大重试次数、退避间隔、可重试错误类型。
     */
    private String retryPolicy;

    /**
     * 超时策略 JSON。
     * 例如单批读取超时、整体执行超时、目标写入超时。
     */
    private String timeoutPolicy;

    /**
     * 是否启用模板。
     * 禁用模板后不应继续创建新的同步任务，但历史任务和执行记录仍然保留。
     */
    private Boolean enabled;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 更新人 ID。
     */
    private Long updatedBy;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
