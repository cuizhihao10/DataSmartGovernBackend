/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - DatasourceCapabilitySnapshotView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * data-sync 本地使用的数据源能力快照视图。
 *
 * <p>这个类是 datasource-management `DataSourceCapabilitySnapshotView` 的“契约镜像”，只复制 data-sync
 * 在模板规划阶段真正需要消费的低敏字段。它不是完整数据源详情，也不是连接配置，更不是执行凭据。</p>
 *
 * <p>为什么在 data-sync 中重新定义，而不是直接引用 datasource-management 的 DTO：</p>
 * <p>1. 微服务之间应该依赖 JSON 契约，而不是互相引用对方 Controller 层 Java 类；</p>
 * <p>2. data-sync 只消费一部分字段，局部模型能减少误用其他字段的机会；</p>
 * <p>3. 后续如果 datasource-management 增加展示字段，data-sync 不会被迫重新编译；</p>
 * <p>4. 本类可以用 data-sync 的业务注释解释字段如何参与模板校验，而不是只复述数据源模块语义。</p>
 */
@Data
public class DatasourceCapabilitySnapshotView {

    /**
     * 快照契约版本，用于未来灰度兼容。
     */
    private String snapshotVersion;

    /**
     * 载荷安全策略说明。期望值类似 LOW_SENSITIVE_CAPABILITY_SNAPSHOT。
     */
    private String payloadPolicy;

    /**
     * 数据源 ID，必须与请求中的 datasourceId 一致。
     */
    private Long datasourceId;

    /**
     * 数据源所属租户。
     *
     * <p>模板创建时会与 template.tenantId 对比，避免一个租户的同步模板引用另一个租户的数据源。</p>
     */
    private Long tenantId;

    /**
     * 数据源所属项目。
     *
     * <p>当前 data-sync 默认要求模板 projectId 与源/目标数据源 projectId 一致。未来如果要支持跨项目同步，
     * 应由 permission-admin 提供明确授权或审批策略，而不是让 data-sync 隐式放行。</p>
     */
    private Long projectId;

    /**
     * 数据源所属工作空间，可为空。
     */
    private Long workspaceId;

    /**
     * 数据源生命周期状态，例如 ACTIVE、INACTIVE。
     */
    private String datasourceStatus;

    /**
     * 最近连接测试状态，例如 UNKNOWN、SUCCESS、FAILED。
     */
    private String connectionTestStatus;

    /**
     * 最近连接测试时间，只用于判断事实新鲜度，不用于访问外部系统。
     */
    private LocalDateTime lastTestTime;

    /**
     * 面向产品的健康状态摘要，例如 CONNECTION_VERIFIED、CONNECTION_FAILED、CONNECTION_NOT_VERIFIED。
     */
    private String healthStatus;

    /**
     * 标准连接器类型，例如 MYSQL、POSTGRESQL、KAFKA、OBJECT_STORAGE。
     *
     * <p>这是模板补全最核心的字段。data-sync 会把它写入 SyncTemplate 的 sourceConnectorType/targetConnectorType，
     * 后续再交给 SyncConnectorCapabilityRegistry 做源端、目标端和 syncMode 的兼容性判断。</p>
     */
    private String connectorType;

    /**
     * 连接器家族，例如 RELATIONAL_JDBC、MESSAGE_STREAM。
     */
    private String connectorFamily;

    /**
     * 当前实现阶段，例如 PRODUCTION_READY、MVP_SUPPORTED、ROADMAP_RESERVED。
     */
    private String implementationStage;

    /**
     * 是否允许进入模板规划。
     *
     * <p>true 只表示“可以创建或校验模板”，不等于“可以立即生产执行”。执行前仍需权限、审批、worker、
     * checkpoint、最近连接测试、字段映射等更多条件共同满足。</p>
     */
    private Boolean eligibleForTemplatePlanning;

    /**
     * 是否通过执行前健康预检。
     */
    private Boolean eligibleForExecutionPrecheck;

    /**
     * 是否可作为源端读取。
     */
    private Boolean canRead;

    /**
     * 是否可作为目标端写入。
     */
    private Boolean canWrite;

    /**
     * 支持的同步模式列表。
     */
    private List<String> supportedSyncModes;

    /**
     * 支持的写入策略列表。
     */
    private List<String> supportedWriteStrategies;

    /**
     * 阻断或风险原因码。
     */
    private List<String> issueCodes;

    /**
     * 推荐处理动作。
     */
    private List<String> recommendedActions;

    /**
     * 快照生成时间。
     */
    private LocalDateTime generatedAt;
}
