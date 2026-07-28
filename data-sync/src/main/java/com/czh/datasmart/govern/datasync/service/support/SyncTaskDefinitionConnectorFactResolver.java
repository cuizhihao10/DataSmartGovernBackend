/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - SyncTaskDefinitionConnectorFactResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceCapabilityProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotClient;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 同步任务定义连接器事实解析器。
 *
 * <p>这个组件是 data-sync 与 datasource-management 能力快照契约真正进入业务闭环的位置。它负责回答一个问题：
 * 当用户、前端或 Agent 只提供 sourceDatasourceId/targetDatasourceId 时，data-sync 如何安全地获得
 * sourceConnectorType/targetConnectorType，并判断这些数据源是否适合进入任务规划？</p>
 *
 * <p>为什么不把逻辑写在 DataSyncServiceImpl：</p>
 * <p>1. 主 Service 已经负责任务、执行、checkpoint、错误样本和审计入口，继续堆逻辑会破坏 500 行约束；</p>
 * <p>2. connector fact 补全本质是“跨服务事实解析 + 安全预检”，它有独立的失败策略、配置和测试边界；</p>
 * <p>3. 后续如果加入缓存、批量快照读取、签名鉴权、跨项目审批或灰度策略，应该在这里扩展，而不是改动主流程。</p>
 *
 * <p>敏感数据边界：</p>
 * <p>本组件只消费低敏字段：connectorType、tenantId、projectId、workspaceId、eligibleForTaskPlanning、
 * canRead/canWrite、issueCodes、recommendedActions。它不读取连接串、账号、密码、SQL、样本、topic、bucket、
 * 文件路径、模型输出或内部 endpoint。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskDefinitionConnectorFactResolver {

    private static final String SOURCE_SIDE = "源端";
    private static final String TARGET_SIDE = "目标端";

    private final DatasourceCapabilitySnapshotClient capabilitySnapshotClient;
    private final DataSyncDatasourceCapabilityProperties properties;
    private final SyncQuerySupport querySupport;

    /**
     * 为任务定义补全并校验连接器事实。
     *
     * @param definition 已经由创建请求转换出的任务定义。方法会在对象上回填 sourceConnectorType/targetConnectorType。
     * @param actorContext 当前调用者上下文，用于透传 traceId 与服务间审计。
     */
    public void resolveConnectorFacts(SyncTaskDefinition definition, SyncActorContext actorContext) {
        if (definition == null) {
            return;
        }

        /*
         * 先把调用方显式传入的 connector type 做大小写归一化。
         * 即使后续远程能力快照关闭，也要保证落库字段统一为 MYSQL/POSTGRESQL/KAFKA 这类标准枚举，
         * 避免列表筛选、兼容性校验和审计摘要出现 mysql、Mysql、MYSQL 混杂。
         */
        String sourceConnectorType = normalize(definition.getSourceConnectorType());
        String targetConnectorType = normalize(definition.getTargetConnectorType());
        definition.setSourceConnectorType(sourceConnectorType);
        definition.setTargetConnectorType(targetConnectorType);

        /*
         * datasourceId 为空时不做远程调用，让后面的 SyncTaskDefinitionValidationSupport 返回更贴近用户输入的基础校验错误。
         * 这样不会因为缺少 datasourceId 而误触发“远端依赖不可用”这类不准确提示。
         */
        if (definition.getSourceDatasourceId() == null || definition.getTargetDatasourceId() == null) {
            return;
        }

        if (!properties.isEnabled()) {
            return;
        }

        boolean sourceNeedsSnapshot = sourceConnectorType == null || properties.isValidateProvidedConnectorFacts();
        boolean targetNeedsSnapshot = targetConnectorType == null || properties.isValidateProvidedConnectorFacts();
        if (!sourceNeedsSnapshot && !targetNeedsSnapshot) {
            return;
        }

        DatasourceCapabilitySnapshotView sourceSnapshot = sourceNeedsSnapshot
                ? loadAndValidateSnapshot(definition, definition.getSourceDatasourceId(), SOURCE_SIDE, true, false, actorContext)
                : null;
        DatasourceCapabilitySnapshotView targetSnapshot = targetNeedsSnapshot
                ? loadAndValidateSnapshot(definition, definition.getTargetDatasourceId(), TARGET_SIDE, false, true, actorContext)
                : null;

        String resolvedSourceConnectorType = resolveConnectorType(
                SOURCE_SIDE, sourceConnectorType, sourceSnapshot, definition.getSourceDatasourceId());
        String resolvedTargetConnectorType = resolveConnectorType(
                TARGET_SIDE, targetConnectorType, targetSnapshot, definition.getTargetDatasourceId());
        definition.setSourceConnectorType(resolvedSourceConnectorType);
        definition.setTargetConnectorType(resolvedTargetConnectorType);
    }

    /**
     * 读取并校验单侧数据源能力快照。
     *
     * <p>这里的校验是任务规划阶段的“事实可信度校验”，不是最终执行校验。最终执行还需要任务状态、
     * worker 租约、checkpoint、字段映射、最近连接测试有效期和执行器能力共同满足。</p>
     */
    private DatasourceCapabilitySnapshotView loadAndValidateSnapshot(SyncTaskDefinition definition,
                                                                     Long datasourceId,
                                                                     String sideName,
                                                                     boolean requireReadable,
                                                                     boolean requireWritable,
                                                                     SyncActorContext actorContext) {
        DatasourceCapabilitySnapshotView snapshot = capabilitySnapshotClient.getSnapshot(datasourceId, actorContext);
        if (snapshot == null) {
            throw validationError(sideName + "数据源能力快照为空，datasourceId=" + datasourceId);
        }
        validateSnapshotIdentity(snapshot, datasourceId, sideName);
        validateSnapshotScope(definition, snapshot, sideName);
        validateSnapshotPlanningEligibility(snapshot, sideName);
        validateReadWriteCapability(snapshot, sideName, requireReadable, requireWritable);
        return snapshot;
    }

    /**
     * 校验快照身份是否与请求 datasourceId 一致。
     *
     * <p>跨服务调用不能只相信“HTTP 路径里传了哪个 id”，还要检查响应主体中的 datasourceId。
     * 如果两者不一致，说明远端映射、代理缓存或序列化链路存在严重问题，必须 fail-closed。</p>
     */
    private void validateSnapshotIdentity(DatasourceCapabilitySnapshotView snapshot, Long requestedDatasourceId, String sideName) {
        if (!requestedDatasourceId.equals(snapshot.getDatasourceId())) {
            throw validationError(sideName + "数据源能力快照 datasourceId 不一致，requestedDatasourceId="
                    + requestedDatasourceId + ", snapshotDatasourceId=" + snapshot.getDatasourceId());
        }
    }

    /**
     * 校验任务定义与数据源快照的数据范围一致性。
     *
     * <p>当前默认要求同租户、同项目；workspace 在双方都明确存在时也要求一致。这样做偏保守，但更符合商业化
     * 数据治理平台的安全默认值。未来如果产品要支持跨项目或跨工作空间同步，应该由 permission-admin 返回明确授权、
     * 审批单或跨项目数据流策略，再在这里引入“允许跨范围”的显式条件，而不是静默放行。</p>
     */
    private void validateSnapshotScope(SyncTaskDefinition definition, DatasourceCapabilitySnapshotView snapshot, String sideName) {
        if (definition.getTenantId() != null && snapshot.getTenantId() != null
                && !definition.getTenantId().equals(snapshot.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    sideName + "数据源租户与任务租户不一致，taskTenantId=" + definition.getTenantId()
                            + ", datasourceTenantId=" + snapshot.getTenantId());
        }
        if (definition.getProjectId() != null && snapshot.getProjectId() != null
                && !definition.getProjectId().equals(snapshot.getProjectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    sideName + "数据源项目与任务项目不一致，taskProjectId=" + definition.getProjectId()
                            + ", datasourceProjectId=" + snapshot.getProjectId()
                            + "；如需跨项目同步，应先接入 permission-admin 跨项目授权或审批策略");
        }
        if (definition.getWorkspaceId() != null && snapshot.getWorkspaceId() != null
                && !definition.getWorkspaceId().equals(snapshot.getWorkspaceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    sideName + "数据源历史工作空间与任务范围不一致，taskWorkspaceId=" + definition.getWorkspaceId()
                            + ", datasourceWorkspaceId=" + snapshot.getWorkspaceId());
        }
    }

    /**
     * 校验数据源是否允许进入任务规划。
     *
     * <p>eligibleForTaskPlanning 与 eligibleForExecutionPrecheck 的区别很重要：
     * 前者表示可以创建配置草稿或做任务定义校验，后者表示距离真实执行更近。任务创建阶段只要求前者，
     * 避免因为连接测试尚未完成就完全阻止用户先保存草稿；但如果数据源停用、已删除或连接器仍是路线图预留，
     * 则不应该进入任务规划。</p>
     */
    private void validateSnapshotPlanningEligibility(DatasourceCapabilitySnapshotView snapshot, String sideName) {
        if (!Boolean.TRUE.equals(snapshot.getEligibleForTaskPlanning())) {
            throw validationError(sideName + "数据源暂不允许进入同步任务规划，datasourceId=" + snapshot.getDatasourceId()
                    + ", issueCodes=" + lowSensitiveList(snapshot.getIssueCodes())
                    + ", recommendedActions=" + lowSensitiveList(snapshot.getRecommendedActions()));
        }
    }

    /**
     * 校验源端可读、目标端可写能力。
     *
     * <p>连接器存在不代表适合当前角色。比如对象存储可能只作为目标归档，某些 API 数据源可能只读不可写。
     * 如果不在任务定义阶段校验读写方向，后续会把明显不可执行的任务推进到 worker 队列，增加失败噪声和排障成本。</p>
     */
    private void validateReadWriteCapability(DatasourceCapabilitySnapshotView snapshot,
                                             String sideName,
                                             boolean requireReadable,
                                             boolean requireWritable) {
        if (requireReadable && !Boolean.TRUE.equals(snapshot.getCanRead())) {
            throw validationError(sideName + "数据源不具备源端读取能力，datasourceId=" + snapshot.getDatasourceId()
                    + ", connectorType=" + normalize(snapshot.getConnectorType()));
        }
        if (requireWritable && !Boolean.TRUE.equals(snapshot.getCanWrite())) {
            throw validationError(sideName + "数据源不具备目标端写入能力，datasourceId=" + snapshot.getDatasourceId()
                    + ", connectorType=" + normalize(snapshot.getConnectorType()));
        }
    }

    /**
     * 从快照中解析 connector type，并在严格模式下校验调用方显式传入值是否与快照一致。
     */
    private String resolveConnectorType(String sideName,
                                        String requestConnectorType,
                                        DatasourceCapabilitySnapshotView snapshot,
                                        Long datasourceId) {
        if (snapshot == null) {
            return requestConnectorType;
        }
        String snapshotConnectorType = normalize(snapshot.getConnectorType());
        if (snapshotConnectorType == null) {
            throw validationError(sideName + "数据源能力快照缺少 connectorType，datasourceId=" + datasourceId
                    + ", issueCodes=" + lowSensitiveList(snapshot.getIssueCodes()));
        }
        if (requestConnectorType != null && !requestConnectorType.equals(snapshotConnectorType)) {
            throw validationError(sideName + "请求中的 connectorType 与数据源能力快照不一致，datasourceId=" + datasourceId
                    + ", requestConnectorType=" + requestConnectorType
                    + ", snapshotConnectorType=" + snapshotConnectorType);
        }
        return snapshotConnectorType;
    }

    private PlatformBusinessException validationError(String message) {
        return new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, message);
    }

    private String normalize(String value) {
        String normalized = querySupport.normalizeCode(value);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private List<String> lowSensitiveList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
