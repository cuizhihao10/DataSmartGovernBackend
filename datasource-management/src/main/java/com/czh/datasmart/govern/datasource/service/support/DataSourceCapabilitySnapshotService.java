/**
 * @Author : Cui
 * @Date: 2026/06/28 23:52
 * @Description DataSmart Govern Backend - DataSourceCapabilitySnapshotService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCapabilitySnapshotView;
import com.czh.datasmart.govern.datasource.entity.ConnectorCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据源能力快照构建服务。
 *
 * <p>该服务只负责把“数据源实例事实”和“连接器能力矩阵”合成为低敏快照，
 * 不负责 HTTP 路由、不读取外部数据源、不执行连接测试、不做元数据发现。</p>
 *
 * <p>为什么要从 {@code DataSourceManagementServiceImpl} 中拆出来：</p>
 * <p>1. 主服务已经承担创建、更新、启停、删除、连接测试、元数据发现和只读 SQL 代理，继续堆查询契约会变得臃肿；</p>
 * <p>2. 能力快照将来会被 data-sync、agent-runtime、网关和管理台共同消费，独立服务更适合做单元测试和后续替换；</p>
 * <p>3. 快照构建逻辑需要非常明确的脱敏边界，单独成类可以更容易审查“哪些字段允许返回，哪些字段绝不返回”。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSourceCapabilitySnapshotService {

    /**
     * 快照契约版本。
     *
     * <p>版本号放在服务常量中统一维护，避免 Controller、DTO 和测试各自硬编码不同字符串。</p>
     */
    private static final String SNAPSHOT_VERSION = "datasmart.datasource.capability-snapshot.v1";

    /**
     * 低敏载荷策略标识。
     *
     * <p>该策略用于提醒调用方：本响应只能用于能力判断、向导展示和执行前预检，
     * 不能用于真实连接外部系统。</p>
     */
    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_CAPABILITY_ONLY";

    /**
     * 连接器能力注册表。
     *
     * <p>快照服务不自己维护连接器规则，而是复用统一注册表。
     * 这样 MySQL、PostgreSQL、Kafka、对象存储等连接器能力只在一个地方演进。</p>
     */
    private final ConnectorCapabilityRegistry connectorCapabilityRegistry;

    /**
     * 构建低敏数据源能力快照。
     *
     * @param datasource 已经通过项目可见性校验的数据源实例。调用方必须先保证资源存在且可见。
     * @return 面向跨服务消费的低敏能力快照。
     *
     * <p>重要安全原则：</p>
     * <p>1. 只读取 {@code DataSourceConfig} 中的 ID、租户、项目、空间、状态、类型和连接测试枚举；</p>
     * <p>2. 不读取、不复制、不格式化 jdbcUrl、username、password、lastTestMessage；</p>
     * <p>3. 只从 {@code ConnectorCapabilityProfile} 复制产品能力字段，不触发真实连接或元数据采样。</p>
     */
    public DataSourceCapabilitySnapshotView buildSnapshot(DataSourceConfig datasource) {
        if (datasource == null) {
            throw new IllegalArgumentException("数据源能力快照构建失败：datasource 不能为空");
        }
        if (DataSourceStatus.DELETED.equals(datasource.getStatus())) {
            throw new IllegalStateException("已删除数据源不能生成能力快照: " + datasource.getId());
        }

        ConnectorCapabilityProfile profile = connectorCapabilityRegistry.getProfile(datasource.getType());
        String healthStatus = resolveHealthStatus(datasource);
        List<String> issueCodes = collectIssueCodes(datasource, profile);
        List<String> recommendedActions = collectRecommendedActions(issueCodes);
        boolean roadmapOnly = isRoadmapOnly(profile);
        boolean eligibleForTemplatePlanning = DataSourceStatus.ACTIVE.equals(datasource.getStatus()) && !roadmapOnly;
        boolean eligibleForExecutionPrecheck = eligibleForTemplatePlanning
                && ConnectionTestStatus.SUCCESS.equals(datasource.getLastTestStatus());

        return new DataSourceCapabilitySnapshotView(
                SNAPSHOT_VERSION,
                PAYLOAD_POLICY,
                datasource.getId(),
                datasource.getTenantId(),
                datasource.getProjectId(),
                datasource.getWorkspaceId(),
                datasource.getStatus(),
                datasource.getLastTestStatus(),
                datasource.getLastTestTime(),
                healthStatus,
                profile.getConnectorType(),
                profile.getConnectorFamily(),
                profile.getImplementationStage(),
                eligibleForTemplatePlanning,
                eligibleForExecutionPrecheck,
                profile.isCanRead(),
                profile.isCanWrite(),
                supportsMode(profile, SyncMode.FULL),
                supportsMode(profile, SyncMode.INCREMENTAL_TIME) || supportsMode(profile, SyncMode.INCREMENTAL_ID),
                supportsMode(profile, SyncMode.STREAMING) || supportsMode(profile, SyncMode.CDC),
                profile.isSupportsSchemaDiscovery(),
                profile.isSupportsFieldMapping(),
                profile.isSupportsCheckpointResume(),
                profile.isSupportsPreviewSampling(),
                profile.isSupportsPartitionParallelism(),
                profile.getSupportedSyncModes(),
                profile.getSupportedWriteStrategies(),
                profile.getConsistencyNotes(),
                profile.getPerformanceRecommendations(),
                profile.getProductionLimitations(),
                profile.getRecommendedNextCapabilities(),
                issueCodes,
                recommendedActions,
                LocalDateTime.now()
        );
    }

    /**
     * 推导面向产品消费的健康状态。
     *
     * <p>这里刻意不返回连接失败原文。失败原文可能包含数据库地址、端口、库名或驱动栈信息，
     * 只能留在受控审计或运维诊断通道，不能进入低敏能力快照。</p>
     */
    private String resolveHealthStatus(DataSourceConfig datasource) {
        if (DataSourceStatus.INACTIVE.equals(datasource.getStatus())) {
            return "DATASOURCE_DISABLED";
        }
        if (ConnectionTestStatus.SUCCESS.equals(datasource.getLastTestStatus())) {
            return "CONNECTION_VERIFIED";
        }
        if (ConnectionTestStatus.FAILED.equals(datasource.getLastTestStatus())) {
            return "CONNECTION_FAILED";
        }
        return "CONNECTION_NOT_TESTED";
    }

    /**
     * 收集机器可读的风险与阻断原因码。
     *
     * <p>原因码用于让 data-sync、Agent 和前端做稳定判断。
     * 自然语言文案可以变化，但原因码应尽量保持兼容。</p>
     */
    private List<String> collectIssueCodes(DataSourceConfig datasource, ConnectorCapabilityProfile profile) {
        List<String> issueCodes = new ArrayList<>();
        if (DataSourceStatus.INACTIVE.equals(datasource.getStatus())) {
            issueCodes.add("DATASOURCE_DISABLED");
        }
        if (ConnectionTestStatus.FAILED.equals(datasource.getLastTestStatus())) {
            issueCodes.add("CONNECTION_LAST_FAILED");
        }
        if (datasource.getLastTestStatus() == null || ConnectionTestStatus.UNKNOWN.equals(datasource.getLastTestStatus())) {
            issueCodes.add("CONNECTION_NOT_VERIFIED");
        }
        if (isRoadmapOnly(profile)) {
            issueCodes.add("CONNECTOR_ROADMAP_RESERVED");
        }
        if (isExecutorPending(profile)) {
            issueCodes.add("EXECUTOR_STAGE_PENDING");
        }
        if (!profile.isCanRead()) {
            issueCodes.add("CONNECTOR_READ_UNSUPPORTED");
        }
        if (!profile.isCanWrite()) {
            issueCodes.add("CONNECTOR_WRITE_UNSUPPORTED");
        }
        return issueCodes.stream().distinct().toList();
    }

    /**
     * 把原因码转换为低敏人工建议。
     *
     * <p>这些建议用于管理台、Agent nextActions 或 operator 提示。
     * 文案只描述治理动作，不拼接 datasourceName、JDBC URL、错误原文或任何外部系统地址。</p>
     */
    private List<String> collectRecommendedActions(List<String> issueCodes) {
        List<String> actions = new ArrayList<>();
        if (issueCodes.contains("DATASOURCE_DISABLED")) {
            actions.add("先由具备数据源管理权限的角色启用该数据源，再允许模板规划或执行。");
        }
        if (issueCodes.contains("CONNECTION_NOT_VERIFIED")) {
            actions.add("在进入生产执行前执行一次连接测试，并记录最近成功时间。");
        }
        if (issueCodes.contains("CONNECTION_LAST_FAILED")) {
            actions.add("先修复连接配置或网络可达性，并重新完成连接测试；低敏快照不会暴露失败原文。");
        }
        if (issueCodes.contains("CONNECTOR_ROADMAP_RESERVED")) {
            actions.add("该连接器仍属于路线图预留，暂不应承诺真实生产执行。");
        }
        if (issueCodes.contains("EXECUTOR_STAGE_PENDING")) {
            actions.add("控制面已具备建模能力，但上线前仍需确认 reader/writer、checkpoint、幂等回执和限流策略。");
        }
        if (issueCodes.contains("CONNECTOR_READ_UNSUPPORTED")) {
            actions.add("该连接器不适合作为同步源端，请选择具备读取能力的数据源。");
        }
        if (issueCodes.contains("CONNECTOR_WRITE_UNSUPPORTED")) {
            actions.add("该连接器不适合作为同步目标端，请选择具备写入能力的数据源。");
        }
        return actions.stream().distinct().toList();
    }

    /**
     * 判断能力画像是否只是路线图预留。
     *
     * <p>ROADMAP_RESERVED 可以出现在能力矩阵中用于产品规划和前端展示，
     * 但不能被解释为当前仓库已经具备真实执行能力。</p>
     */
    private boolean isRoadmapOnly(ConnectorCapabilityProfile profile) {
        return profile.getImplementationStage() != null
                && profile.getImplementationStage().contains("ROADMAP");
    }

    /**
     * 判断执行器是否仍处于待完善阶段。
     *
     * <p>该判断不会直接阻断模板规划，因为控制面可以先创建草稿；
     * 但它会进入 issueCodes，提醒执行前还需要确认真实 worker 能力。</p>
     */
    private boolean isExecutorPending(ConnectorCapabilityProfile profile) {
        return profile.getImplementationStage() != null
                && profile.getImplementationStage().contains("PENDING");
    }

    /**
     * 判断连接器是否支持某种同步模式。
     */
    private boolean supportsMode(ConnectorCapabilityProfile profile, SyncMode mode) {
        return profile.getSupportedSyncModes() != null
                && profile.getSupportedSyncModes().contains(mode.name());
    }
}
