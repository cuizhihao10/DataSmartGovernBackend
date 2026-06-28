/**
 * @Author : Cui
 * @Date: 2026/06/28 23:52
 * @Description DataSmart Govern Backend - DataSourceCapabilitySnapshotView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源能力快照视图。
 *
 * <p>这个对象是 datasource-management 面向 data-sync、agent-runtime、管理台向导和后续执行器的
 * “低敏事实契约”。它和已有的 {@code DataSourceConfig} 最大区别在于：这里绝不返回 JDBC URL、
 * 用户名、密码、host、port、database、topic、bucket、文件路径、SQL、样本数据或连接错误原文。</p>
 *
 * <p>为什么要单独建这个 DTO：</p>
 * <p>1. data-sync 模板校验需要知道 datasourceId 对应的连接器类型和能力，但不应该读取连接密钥；</p>
 * <p>2. Agent 做工具规划时只需要“能不能读、能不能写、能不能增量、是否需要审批/人工修复”等低敏事实；</p>
 * <p>3. 前端配置向导需要根据能力开关展示可选同步模式，但不能因为展示一个能力标签就把敏感连接配置带出去；</p>
 * <p>4. 后续如果连接器能力从代码注册表迁移到插件市场或配置中心，外部 API 契约可以保持稳定。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceCapabilitySnapshotView {

    /**
     * 快照契约版本。
     *
     * <p>跨微服务契约必须显式带版本。后续如果新增字段、拆分枚举或调整语义，
     * data-sync、agent-runtime 等消费者可以根据该字段做兼容处理，而不是隐式依赖当前 Java 类结构。</p>
     */
    private String snapshotVersion;

    /**
     * 低敏载荷策略。
     *
     * <p>固定说明本响应只允许携带能力枚举、状态枚举、布尔能力标志、建议和原因码。
     * 调用方看到该字段后，不能把它误认为可用于直接连接外部系统的完整配置。</p>
     */
    private String payloadPolicy;

    /**
     * 数据源主键 ID。
     *
     * <p>该 ID 用于让 data-sync 模板、Agent 工具计划和任务执行记录继续引用同一个数据源实例。
     * 它不是连接信息，不包含外部系统地址或凭据。</p>
     */
    private Long datasourceId;

    /**
     * 数据源所属租户。
     *
     * <p>跨服务消费时需要带上租户事实，便于下游在创建模板、任务或审计记录时再次校验租户边界，
     * 避免只凭 datasourceId 形成跨租户引用。</p>
     */
    private Long tenantId;

    /**
     * 数据源所属项目。
     *
     * <p>项目级权限是当前平台最重要的数据范围边界之一。data-sync 后续自动补全 connectorType 时，
     * 也要确保模板项目与源/目标数据源项目语义一致或经过明确授权。</p>
     */
    private Long projectId;

    /**
     * 数据源所属工作空间。
     *
     * <p>工作空间通常比项目更细，用于区分研发、生产、临时分析等协作范围。
     * 该字段可为空，表示当前数据源暂未细分到工作空间。</p>
     */
    private Long workspaceId;

    /**
     * 数据源生命周期状态。
     *
     * <p>例如 ACTIVE、INACTIVE。DELETED 数据源不会通过本接口返回，
     * 因为逻辑删除后的资源不应再参与模板规划、同步执行或 Agent 工具计划。</p>
     */
    private String datasourceStatus;

    /**
     * 最近一次连接测试状态。
     *
     * <p>例如 UNKNOWN、SUCCESS、FAILED。这里仅返回枚举，不返回 lastTestMessage，
     * 因为连接失败原因里可能包含 host、database、网络地址或驱动错误细节。</p>
     */
    private String connectionTestStatus;

    /**
     * 最近一次连接测试时间。
     *
     * <p>该时间用于判断健康事实是否过期。例如模板可以先创建草稿，
     * 但生产执行前通常需要要求最近一次成功连接测试仍在有效窗口内。</p>
     */
    private LocalDateTime lastTestTime;

    /**
     * 面向产品和执行器的健康状态。
     *
     * <p>该字段由 datasourceStatus 与 connectionTestStatus 共同推导：
     * ACTIVE + SUCCESS 通常为 CONNECTION_VERIFIED；
     * ACTIVE + FAILED 为 CONNECTION_FAILED；
     * ACTIVE + UNKNOWN 为空跑或待测试状态；
     * INACTIVE 则优先表达为 DATASOURCE_DISABLED。</p>
     */
    private String healthStatus;

    /**
     * 标准连接器类型。
     *
     * <p>例如 MYSQL、POSTGRESQL、SQL_SERVER、KAFKA、OBJECT_STORAGE。
     * 这是 data-sync 模板能力校验最需要消费的低敏字段。</p>
     */
    private String connectorType;

    /**
     * 连接器家族。
     *
     * <p>例如 RELATIONAL_JDBC、MESSAGE_STREAM、OBJECT_STORAGE、HTTP_API。
     * 家族信息用于前端分组、运营分析和后续执行器路由，但不暴露任何具体 endpoint。</p>
     */
    private String connectorFamily;

    /**
     * 当前实现阶段。
     *
     * <p>该字段用于诚实表达“产品模型已建模”和“真实执行器已完备”之间的差异。
     * 例如 ROADMAP_RESERVED 表示仅作为路线图预留，不应直接承诺生产执行能力。</p>
     */
    private String implementationStage;

    /**
     * 是否适合进入同步模板规划。
     *
     * <p>它表示当前数据源实例处于可用生命周期，并且连接器不是纯路线图预留。
     * 该字段不等价于“可以立即生产执行”，因为真实执行还需要权限、审批、队列、worker、checkpoint 和最近连接测试共同满足。</p>
     */
    private boolean eligibleForTemplatePlanning;

    /**
     * 是否通过执行前置健康预检。
     *
     * <p>该字段主要用于 worker 或 operator 做执行前提示：只有 ACTIVE、最近连接测试成功、
     * 且连接器不是路线图预留时才为 true。它仍不替代权限审批、任务状态机和执行器租约。</p>
     */
    private boolean eligibleForExecutionPrecheck;

    /**
     * 是否支持作为源端读取数据。
     */
    private boolean canRead;

    /**
     * 是否支持作为目标端写入数据。
     */
    private boolean canWrite;

    /**
     * 是否支持全量同步。
     */
    private boolean supportsFullSync;

    /**
     * 是否支持增量同步。
     *
     * <p>当前会把 INCREMENTAL_TIME 与 INCREMENTAL_ID 统一折叠为一个能力标志，
     * 调用方如果需要展示细分模式，应继续读取 supportedSyncModes。</p>
     */
    private boolean supportsIncrementalSync;

    /**
     * 是否支持流式或 CDC 类同步。
     */
    private boolean supportsStreaming;

    /**
     * 是否支持 schema/表/字段结构发现。
     */
    private boolean supportsSchemaDiscovery;

    /**
     * 是否支持字段映射。
     */
    private boolean supportsFieldMapping;

    /**
     * 是否支持 checkpoint 恢复。
     */
    private boolean supportsCheckpointResume;

    /**
     * 是否支持预览采样。
     *
     * <p>即使该字段为 true，真正采样也必须经过权限、脱敏、行数上限和审计控制；
     * 本字段只表达连接器能力，不授权访问真实样本。</p>
     */
    private boolean supportsPreviewSampling;

    /**
     * 是否适合后续发展分区并行。
     */
    private boolean supportsPartitionParallelism;

    /**
     * 支持的同步模式列表。
     *
     * <p>例如 FULL、INCREMENTAL_TIME、INCREMENTAL_ID、SCHEDULED_BATCH、REPLAY、BACKFILL。
     * 这是模板向导和模板校验最常消费的字段之一。</p>
     */
    private List<String> supportedSyncModes;

    /**
     * 支持的写入策略列表。
     *
     * <p>例如 APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE。
     * 当该数据源作为目标端时，data-sync 会用它判断模板 writeStrategy 是否允许。</p>
     */
    private List<String> supportedWriteStrategies;

    /**
     * 一致性说明。
     *
     * <p>仅返回低敏的产品语义说明，用于提示 at-least-once、幂等写入、
     * 事务边界、offset/checkpoint 等设计关注点，不包含任何真实数据内容。</p>
     */
    private List<String> consistencyNotes;

    /**
     * 性能建议。
     *
     * <p>用于提示批量大小、分区、索引、背压、fetchSize、checkpoint 间隔等优化方向，
     * 帮助使用者在配置阶段就意识到高并发或大表场景的风险。</p>
     */
    private List<String> performanceRecommendations;

    /**
     * 当前生产化限制。
     *
     * <p>用于明确当前仓库阶段还缺什么，例如真实执行器、CDC 位点、对象存储凭证托管等。
     * 这样调用方不会把“控制面可建模”误解成“生产执行已完全闭环”。</p>
     */
    private List<String> productionLimitations;

    /**
     * 建议下一步能力。
     *
     * <p>该字段服务于产品收敛路线，只记录最能推动闭环的后续动作，
     * 避免继续无限扩展局部展示字段。</p>
     */
    private List<String> recommendedNextCapabilities;

    /**
     * 阻断或风险原因码。
     *
     * <p>原因码比自然语言提示更适合被 data-sync、Agent 和前端消费。
     * 例如 CONNECTION_NOT_VERIFIED、CONNECTOR_ROADMAP_RESERVED、EXECUTOR_STAGE_PENDING。</p>
     */
    private List<String> issueCodes;

    /**
     * 推荐处理动作。
     *
     * <p>这些动作是低敏的人类可读建议，例如先执行连接测试、启用数据源、
     * 或先补齐真实执行器，不会包含 endpoint、凭据、SQL 或错误原文。</p>
     */
    private List<String> recommendedActions;

    /**
     * 快照生成时间。
     */
    private LocalDateTime generatedAt;
}
