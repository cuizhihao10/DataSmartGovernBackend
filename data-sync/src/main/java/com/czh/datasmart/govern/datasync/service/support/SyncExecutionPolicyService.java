/**
 * @Author : Cui
 * @Date: 2026/07/09 22:39
 * @Description DataSmart Govern Backend - SyncExecutionPolicyService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicyQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicySnapshotView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicyUpsertRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicyView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicySnapshot;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据同步执行策略服务。
 *
 * <p>本服务负责把“管理员配置的策略”转成“执行器真正可消费的参数”。它处在 data-sync 控制面内，
 * 不连接源库或目标库，也不执行数据搬运；它只做三件事：</p>
 *
 * <p>1. 提供管理员策略 CRUD API，让系统默认、项目级、连接器/数据源级、任务级策略可管理；</p>
 * <p>2. 在 execution 运行前解析有效策略，保证生效顺序为：任务级覆盖 > 项目级策略 > 数据源/连接器策略 > 系统默认策略；</p>
 * <p>3. 在每次 execution 中保存低敏策略快照，让历史运行可解释、可审计、可排障。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncExecutionPolicyService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final Set<String> POLICY_WRITE_ROLES = Set.of(
            "OPERATOR",
            "TENANT_ADMINISTRATOR",
            "PLATFORM_ADMINISTRATOR",
            "SERVICE_ACCOUNT"
    );
    private static final Set<String> PLATFORM_POLICY_ROLES = Set.of("PLATFORM_ADMINISTRATOR", "SERVICE_ACCOUNT");
    private static final Set<String> SUPPORTED_SCOPE_TYPES = Set.of(
            "SYSTEM", "PROJECT", "CONNECTOR", "DATASOURCE", "TASK"
    );
    private static final Set<String> SUPPORTED_CONNECTOR_ROLES = Set.of("ANY", "SOURCE", "TARGET");

    private final SyncExecutionPolicyMapper policyMapper;
    private final SyncExecutionPolicySnapshotMapper snapshotMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncTemplateMapper templateMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询执行策略。
     *
     * <p>列表查询会把 tenantId=0 的系统默认策略纳入可见结果，因为租户级任务运行时也会继承系统默认值；
     * 但写操作仍受角色和租户边界限制。</p>
     */
    public PlatformPageResponse<SyncExecutionPolicyView> pagePolicies(SyncExecutionPolicyQueryCriteria criteria,
                                                                      SyncActorContext actorContext) {
        SyncExecutionPolicyQueryCriteria safeCriteria = criteria == null
                ? new SyncExecutionPolicyQueryCriteria(null, null, null, null, null, null,
                null, null, null, 1L, 20L)
                : criteria;
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                safeCriteria.tenantId(), safeCriteria.projectId(), null, actorContext);
        LambdaQueryWrapper<SyncExecutionPolicy> wrapper = new LambdaQueryWrapper<SyncExecutionPolicy>()
                .orderByDesc(SyncExecutionPolicy::getUpdateTime)
                .orderByDesc(SyncExecutionPolicy::getId);

        if (visibility.tenantId() == null) {
            if (safeCriteria.tenantId() != null) {
                wrapper.eq(SyncExecutionPolicy::getTenantId, safeCriteria.tenantId());
            }
        } else {
            wrapper.in(SyncExecutionPolicy::getTenantId, distinctTenants(PLATFORM_TENANT_ID, visibility.tenantId()));
        }
        /*
         * 项目筛选不能简单写成 project_id = 当前项目。
         *
         * SYSTEM 以及一部分租户级 CONNECTOR/DATASOURCE 策略的 project_id 合法值就是 NULL，
         * 但它们仍会被当前项目中的任务继承。如果列表接口只返回项目自身策略，管理员页面看到的内容就会
         * 少于运行时真正参与合并的内容，最终出现“页面明明没有这条策略，execution 却使用了它”的审计断层。
         *
         * 因此当前项目视图展示两类策略：
         * 1. project_id = 当前项目：项目级和任务级覆盖；
         * 2. project_id IS NULL：系统、租户、连接器或数据源公共策略。
         *
         * 权限边界仍由 tenantId、gateway 数据范围和后续 scope 条件控制，这里只是补齐继承链可见性，
         * 并不会让项目角色看到其他项目的专属策略。
         */
        if (visibility.projectId() != null) {
            wrapper.and(projectScope -> projectScope
                    .eq(SyncExecutionPolicy::getProjectId, visibility.projectId())
                    .or()
                    .isNull(SyncExecutionPolicy::getProjectId));
        }
        eqIfPresent(wrapper, SyncExecutionPolicy::getScopeType, normalizeCode(safeCriteria.scopeType()));
        eqIfPresent(wrapper, SyncExecutionPolicy::getScopeKey, trimToNull(safeCriteria.scopeKey()));
        eqIfPresent(wrapper, SyncExecutionPolicy::getDatasourceId, safeCriteria.datasourceId());
        eqIfPresent(wrapper, SyncExecutionPolicy::getConnectorType, normalizeCode(safeCriteria.connectorType()));
        eqIfPresent(wrapper, SyncExecutionPolicy::getConnectorRole, normalizeCode(safeCriteria.connectorRole()));
        eqIfPresent(wrapper, SyncExecutionPolicy::getSyncTaskId, safeCriteria.syncTaskId());
        eqIfPresent(wrapper, SyncExecutionPolicy::getEnabled, safeCriteria.enabled());

        IPage<SyncExecutionPolicy> page = policyMapper.selectPage(
                new Page<>(pageNo(safeCriteria.current()), pageSize(safeCriteria.size())), wrapper);
        List<SyncExecutionPolicyView> records = page.getRecords().stream()
                .map(this::toView)
                .toList();
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /**
     * 创建或更新管理员执行策略。
     *
     * <p>该方法有两个安全约束：</p>
     * <p>1. 只有 OPERATOR、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR、SERVICE_ACCOUNT 能写策略；</p>
     * <p>2. tenantId=0 的平台级策略只能由平台管理员或服务账号维护，避免租户管理员修改全局默认值影响其他租户。</p>
     */
    @Transactional
    public SyncExecutionPolicyView upsertPolicy(SyncExecutionPolicyUpsertRequest request,
                                                SyncActorContext actorContext) {
        assertPolicyWriteAllowed(actorContext);
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "执行策略请求体不能为空");
        }
        SyncExecutionPolicy policy = request.getId() == null
                ? new SyncExecutionPolicy()
                : getPolicyForUpdate(request.getId(), actorContext);
        String scopeType = requireScopeType(request.getScopeType());
        Long tenantId = dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext);
        if (PLATFORM_TENANT_ID == tenantId && !PLATFORM_POLICY_ROLES.contains(normalizeRole(actorContext))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "只有平台管理员或服务账号可以维护 tenantId=0 的全局执行策略");
        }
        Long projectId = resolveProjectId(scopeType, request, actorContext);
        validateScopeRequiredFields(scopeType, request, projectId);
        validateNumericBounds(request);

        policy.setTenantId(tenantId);
        policy.setProjectId(projectId);
        policy.setScopeType(scopeType);
        policy.setScopeKey(resolveScopeKey(scopeType, request, projectId));
        policy.setScopeName(defaultText(request.getScopeName(), policy.getScopeKey()));
        policy.setPolicyCode(defaultText(normalizeCode(request.getPolicyCode()), scopeType + "_DEFAULT"));
        policy.setPolicyName(defaultText(request.getPolicyName(), policy.getPolicyCode()));
        policy.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        policy.setDatasourceId(request.getDatasourceId());
        policy.setConnectorType(normalizeCode(request.getConnectorType()));
        policy.setConnectorRole(defaultText(normalizeCode(request.getConnectorRole()), "ANY"));
        policy.setSyncTaskId(request.getSyncTaskId());
        policy.setTargetRowsPerShard(request.getTargetRowsPerShard());
        policy.setMinShardCount(request.getMinShardCount());
        policy.setMaxShardCount(request.getMaxShardCount());
        policy.setMaxChannel(request.getMaxChannel());
        policy.setTaskGroupSize(request.getTaskGroupSize());
        policy.setReadBatchSize(request.getReadBatchSize());
        policy.setWriteBatchSize(request.getWriteBatchSize());
        policy.setCommitIntervalRecords(request.getCommitIntervalRecords());
        policy.setTimeoutSeconds(request.getTimeoutSeconds());
        policy.setMaxRetryCount(request.getMaxRetryCount());
        policy.setMaxDirtyRecordCount(request.getMaxDirtyRecordCount());
        policy.setMaxDirtyRecordRatio(request.getMaxDirtyRecordRatio());
        policy.setPriority(request.getPriority() == null ? defaultPriority(scopeType) : request.getPriority());
        policy.setDescription(trimToNull(request.getDescription()));
        policy.setUpdatedBy(actorContext == null ? null : actorContext.actorId());
        policy.setUpdateTime(LocalDateTime.now());
        if (policy.getId() == null) {
            policy.setCreatedBy(actorContext == null ? null : actorContext.actorId());
            policy.setCreateTime(LocalDateTime.now());
            policyMapper.insert(policy);
        } else {
            policyMapper.updateById(policy);
        }
        return toView(policy);
    }

    /**
     * 禁用执行策略。
     *
     * <p>策略禁用采用软删除语义：保留原配置用于审计和回滚，但不再参与运行时解析。</p>
     */
    @Transactional
    public void disablePolicy(Long id, SyncActorContext actorContext) {
        assertPolicyWriteAllowed(actorContext);
        SyncExecutionPolicy policy = getPolicyForUpdate(id, actorContext);
        policy.setEnabled(Boolean.FALSE);
        policy.setUpdatedBy(actorContext == null ? null : actorContext.actorId());
        policy.setUpdateTime(LocalDateTime.now());
        policyMapper.updateById(policy);
    }

    /**
     * 查询某次执行的策略快照。
     *
     * <p>该方法先读取任务和 execution 做数据范围校验，再读取快照，避免用户通过猜测 executionId 查看其他项目的运行治理参数。</p>
     */
    public SyncExecutionPolicySnapshotView getSnapshot(Long taskId,
                                                       Long executionId,
                                                       SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecution(task, executionId);
        SyncExecutionPolicySnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<SyncExecutionPolicySnapshot>()
                        .eq(SyncExecutionPolicySnapshot::getSyncTaskId, task.getId())
                        .eq(SyncExecutionPolicySnapshot::getExecutionId, execution.getId())
                        .last("LIMIT 1"));
        if (snapshot == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "当前 execution 尚未生成执行策略快照，可能是历史运行或执行尚未进入计划阶段");
        }
        return toSnapshotView(snapshot);
    }

    /**
     * 按任务和模板解析有效策略。
     *
     * <p>分片 fan-out 在读取 partitionConfig 与 range-probe 前会调用该方法，用于确定 targetRowsPerShard、maxChannel、
     * taskGroupSize、dirty threshold 等参数。</p>
     */
    public SyncEffectiveExecutionPolicy resolveEffectivePolicy(SyncTask task,
                                                               SyncTemplate template,
                                                               SyncActorContext actorContext) {
        ExecutionPolicyFacts facts = ExecutionPolicyFacts.from(task, template);
        return resolveEffectivePolicy(facts);
    }

    /**
     * 按桥接计划解析有效策略。
     *
     * <p>普通 run-once 执行路径已经拥有 {@link SyncBatchRunnerBridgePlan}，不需要回查模板也能确定源端/目标端连接器、
     * 数据源和任务归属。</p>
     */
    public SyncEffectiveExecutionPolicy resolveEffectivePolicy(SyncBatchRunnerBridgePlan bridgePlan,
                                                               SyncTask task,
                                                               SyncActorContext actorContext) {
        ExecutionPolicyFacts facts = ExecutionPolicyFacts.from(bridgePlan, task);
        return resolveEffectivePolicy(facts);
    }

    /**
     * 保存或更新某次 execution 的策略快照。
     *
     * <p>run-once 和分片 fan-out 可能都会尝试保存快照。这里按 syncTaskId + executionId 做幂等 upsert：
     * 早期保存的普通策略快照可以被后续带 resolvedShardCount 的分片快照补充，但不会产生重复记录。</p>
     */
    @Transactional
    public void saveSnapshot(SyncTask task,
                             SyncExecution execution,
                             SyncEffectiveExecutionPolicy policy,
                             Integer resolvedShardCount,
                             Integer resolvedChannel,
                             String triggerStage) {
        if (task == null || execution == null) {
            return;
        }
        SyncEffectiveExecutionPolicy safePolicy = policy == null
                ? SyncEffectiveExecutionPolicy.defaults(task.getTenantId(), task.getProjectId(), task.getId())
                : policy;
        String snapshotJson = writeSnapshotJson(safePolicy, resolvedShardCount, resolvedChannel, triggerStage);
        SyncExecutionPolicySnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<SyncExecutionPolicySnapshot>()
                        .eq(SyncExecutionPolicySnapshot::getSyncTaskId, task.getId())
                        .eq(SyncExecutionPolicySnapshot::getExecutionId, execution.getId())
                        .last("LIMIT 1"));
        if (snapshot == null) {
            snapshot = new SyncExecutionPolicySnapshot();
            snapshot.setTenantId(task.getTenantId());
            snapshot.setProjectId(task.getProjectId());
            snapshot.setSyncTaskId(task.getId());
            snapshot.setExecutionId(execution.getId());
            snapshot.setCreateTime(LocalDateTime.now());
        }
        snapshot.setPolicyCodeSummary(truncate(String.join(" > ", safePolicy.matchedPolicyCodes()), 500));
        snapshot.setResolutionOrder(safePolicy.resolutionOrder());
        snapshot.setTargetRowsPerShard(safePolicy.targetRowsPerShard());
        snapshot.setResolvedShardCount(resolvedShardCount);
        snapshot.setResolvedChannel(resolvedChannel);
        snapshot.setTaskGroupSize(safePolicy.effectiveTaskGroupSize(
                resolvedShardCount == null ? 1 : Math.max(1, resolvedShardCount)));
        snapshot.setReadBatchSize(safePolicy.effectiveReadBatchSize());
        snapshot.setWriteBatchSize(safePolicy.effectiveWriteBatchSize());
        snapshot.setCommitIntervalRecords(safePolicy.effectiveCommitIntervalRecords());
        snapshot.setTimeoutSeconds(safePolicy.effectiveTimeoutSeconds());
        snapshot.setMaxRetryCount(safePolicy.effectiveMaxRetryCount());
        snapshot.setMaxDirtyRecordCount(safePolicy.effectiveMaxDirtyRecordCount());
        snapshot.setMaxDirtyRecordRatio(BigDecimal.valueOf(safePolicy.effectiveMaxDirtyRecordRatio()));
        snapshot.setSnapshotJson(snapshotJson);
        snapshot.setPayloadPolicy(SyncEffectiveExecutionPolicy.SNAPSHOT_PAYLOAD_POLICY);
        snapshot.setUpdateTime(LocalDateTime.now());
        if (snapshot.getId() == null) {
            snapshotMapper.insert(snapshot);
        } else {
            snapshotMapper.updateById(snapshot);
        }
    }

    private SyncEffectiveExecutionPolicy resolveEffectivePolicy(ExecutionPolicyFacts facts) {
        SyncEffectiveExecutionPolicy effective = SyncEffectiveExecutionPolicy.defaults(
                facts.tenantId(), facts.projectId(), facts.syncTaskId());
        List<SyncExecutionPolicy> policies = policyMapper.selectList(
                new LambdaQueryWrapper<SyncExecutionPolicy>()
                        .in(SyncExecutionPolicy::getTenantId, distinctTenants(PLATFORM_TENANT_ID, facts.tenantId()))
                        .eq(SyncExecutionPolicy::getEnabled, Boolean.TRUE));
        List<SyncExecutionPolicy> matched = policies.stream()
                .filter(policy -> matches(policy, facts))
                .sorted(Comparator
                        .comparingInt((SyncExecutionPolicy policy) -> scopeLayer(policy.getScopeType()))
                        .thenComparingInt(policy -> policy.getPriority() == null ? defaultPriority(policy.getScopeType()) : policy.getPriority())
                        .thenComparing(policy -> policy.getId() == null ? 0L : policy.getId()))
                .toList();
        for (SyncExecutionPolicy policy : matched) {
            effective = effective.merge(policy);
        }
        return effective;
    }

    private boolean matches(SyncExecutionPolicy policy, ExecutionPolicyFacts facts) {
        if (policy == null || facts == null) {
            return false;
        }
        String scopeType = normalizeCode(policy.getScopeType());
        if ("SYSTEM".equals(scopeType)) {
            return true;
        }
        if ("PROJECT".equals(scopeType)) {
            return same(policy.getProjectId(), facts.projectId())
                    || same(policy.getScopeKey(), "PROJECT:" + facts.projectId());
        }
        if ("TASK".equals(scopeType)) {
            return same(policy.getSyncTaskId(), facts.syncTaskId())
                    || same(policy.getScopeKey(), "TASK:" + facts.syncTaskId());
        }
        if ("DATASOURCE".equals(scopeType)) {
            return datasourceMatches(policy, facts);
        }
        if ("CONNECTOR".equals(scopeType)) {
            return connectorMatches(policy, facts);
        }
        return false;
    }

    private boolean datasourceMatches(SyncExecutionPolicy policy, ExecutionPolicyFacts facts) {
        String role = defaultText(normalizeCode(policy.getConnectorRole()), "ANY");
        if ("SOURCE".equals(role)) {
            return same(policy.getDatasourceId(), facts.sourceDatasourceId());
        }
        if ("TARGET".equals(role)) {
            return same(policy.getDatasourceId(), facts.targetDatasourceId());
        }
        return same(policy.getDatasourceId(), facts.sourceDatasourceId())
                || same(policy.getDatasourceId(), facts.targetDatasourceId());
    }

    private boolean connectorMatches(SyncExecutionPolicy policy, ExecutionPolicyFacts facts) {
        String connectorType = normalizeCode(policy.getConnectorType());
        String role = defaultText(normalizeCode(policy.getConnectorRole()), "ANY");
        /*
         * connectorType 为空不是“脏数据”，而是执行策略体系里的通用读写默认层：
         * - connectorRole=SOURCE + connectorType=null 表示任意源端连接器的默认读取策略；
         * - connectorRole=TARGET + connectorType=null 表示任意目标端连接器的默认写入策略；
         * - 后续如果管理员新增 MYSQL/ORACLE/KAFKA 等具体 connectorType 策略，会在同一 CONNECTOR 层按
         *   priority 和 id 顺序继续覆盖通用默认值。
         *
         * 这样设计的原因是用户提到的“MySQL 源端默认读取、PostgreSQL 目标端默认写入”只是示例，而不是产品边界。
         * 商业化数据同步工具必须先有跨连接器的默认读写治理，再允许特定连接器做容量和稳定性例外。
         */
        if ("SOURCE".equals(role)) {
            return hasSourceSide(facts) && connectorTypeMatches(connectorType, facts.sourceConnectorType());
        }
        if ("TARGET".equals(role)) {
            return hasTargetSide(facts) && connectorTypeMatches(connectorType, facts.targetConnectorType());
        }
        return (hasSourceSide(facts) && connectorTypeMatches(connectorType, facts.sourceConnectorType()))
                || (hasTargetSide(facts) && connectorTypeMatches(connectorType, facts.targetConnectorType()));
    }

    private boolean connectorTypeMatches(String policyConnectorType, String actualConnectorType) {
        return policyConnectorType == null || same(policyConnectorType, actualConnectorType);
    }

    private boolean hasSourceSide(ExecutionPolicyFacts facts) {
        return facts.sourceDatasourceId() != null || trimToNull(facts.sourceConnectorType()) != null;
    }

    private boolean hasTargetSide(ExecutionPolicyFacts facts) {
        return facts.targetDatasourceId() != null || trimToNull(facts.targetConnectorType()) != null;
    }

    private SyncExecutionPolicy getPolicyForUpdate(Long id, SyncActorContext actorContext) {
        SyncExecutionPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "执行策略不存在: " + id);
        }
        dataScopeSupport.validateTenantReadable(policy.getTenantId(), actorContext);
        if (PLATFORM_TENANT_ID == safeLong(policy.getTenantId())
                && !PLATFORM_POLICY_ROLES.contains(normalizeRole(actorContext))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "只有平台管理员或服务账号可以修改全局执行策略");
        }
        return policy;
    }

    private SyncTask getTask(Long taskId, SyncActorContext actorContext) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
        }
        dataScopeSupport.validateOwnedReadable(task.getTenantId(), task.getProjectId(), task.getOwnerId(),
                actorContext, "同步任务执行策略快照");
        return task;
    }

    private SyncExecution getExecution(SyncTask task, Long executionId) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步执行记录不存在: " + executionId);
        }
        if (!same(execution.getSyncTaskId(), task.getId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "执行记录不属于当前同步任务，taskId=" + task.getId() + ", executionId=" + executionId);
        }
        return execution;
    }

    private SyncExecutionPolicyView toView(SyncExecutionPolicy policy) {
        return new SyncExecutionPolicyView(
                policy.getId(),
                policy.getTenantId(),
                policy.getProjectId(),
                policy.getScopeType(),
                policy.getScopeKey(),
                policy.getScopeName(),
                policy.getPolicyCode(),
                policy.getPolicyName(),
                policy.getEnabled(),
                policy.getDatasourceId(),
                policy.getConnectorType(),
                policy.getConnectorRole(),
                policy.getSyncTaskId(),
                policy.getTargetRowsPerShard(),
                policy.getMinShardCount(),
                policy.getMaxShardCount(),
                policy.getMaxChannel(),
                policy.getTaskGroupSize(),
                policy.getReadBatchSize(),
                policy.getWriteBatchSize(),
                policy.getCommitIntervalRecords(),
                policy.getTimeoutSeconds(),
                policy.getMaxRetryCount(),
                policy.getMaxDirtyRecordCount(),
                policy.getMaxDirtyRecordRatio(),
                policy.getPriority(),
                policy.getDescription(),
                policy.getCreateTime(),
                policy.getUpdateTime()
        );
    }

    private SyncExecutionPolicySnapshotView toSnapshotView(SyncExecutionPolicySnapshot snapshot) {
        return new SyncExecutionPolicySnapshotView(
                snapshot.getId(),
                snapshot.getTenantId(),
                snapshot.getProjectId(),
                snapshot.getSyncTaskId(),
                snapshot.getExecutionId(),
                snapshot.getPolicyCodeSummary(),
                parsePolicyCodes(snapshot.getPolicyCodeSummary()),
                snapshot.getResolutionOrder(),
                snapshot.getTargetRowsPerShard(),
                snapshot.getResolvedShardCount(),
                snapshot.getResolvedChannel(),
                snapshot.getTaskGroupSize(),
                snapshot.getReadBatchSize(),
                snapshot.getWriteBatchSize(),
                snapshot.getCommitIntervalRecords(),
                snapshot.getTimeoutSeconds(),
                snapshot.getMaxRetryCount(),
                snapshot.getMaxDirtyRecordCount(),
                snapshot.getMaxDirtyRecordRatio(),
                snapshot.getPayloadPolicy(),
                snapshot.getSnapshotJson(),
                snapshot.getCreateTime(),
                snapshot.getUpdateTime()
        );
    }

    private List<String> parsePolicyCodes(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        return List.of(summary.split("\\s*>\\s*"));
    }

    private String writeSnapshotJson(SyncEffectiveExecutionPolicy policy,
                                     Integer resolvedShardCount,
                                     Integer resolvedChannel,
                                     String triggerStage) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("payloadPolicy", SyncEffectiveExecutionPolicy.SNAPSHOT_PAYLOAD_POLICY);
        snapshot.put("resolutionOrder", policy.resolutionOrder());
        snapshot.put("matchedPolicyCodes", policy.matchedPolicyCodes());
        snapshot.put("triggerStage", triggerStage);
        snapshot.put("targetRowsPerShard", policy.targetRowsPerShard());
        snapshot.put("minShardCount", policy.minShardCount());
        snapshot.put("maxShardCount", policy.maxShardCount());
        snapshot.put("resolvedShardCount", resolvedShardCount);
        snapshot.put("resolvedChannel", resolvedChannel);
        snapshot.put("taskGroupSize", policy.effectiveTaskGroupSize(
                resolvedShardCount == null ? 1 : Math.max(1, resolvedShardCount)));
        snapshot.put("readBatchSize", policy.effectiveReadBatchSize());
        snapshot.put("writeBatchSize", policy.effectiveWriteBatchSize());
        snapshot.put("commitIntervalRecords", policy.effectiveCommitIntervalRecords());
        snapshot.put("timeoutSeconds", policy.effectiveTimeoutSeconds());
        snapshot.put("maxRetryCount", policy.effectiveMaxRetryCount());
        snapshot.put("maxDirtyRecordCount", policy.effectiveMaxDirtyRecordCount());
        snapshot.put("maxDirtyRecordRatio", policy.effectiveMaxDirtyRecordRatio());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            return "{\"payloadPolicy\":\"" + SyncEffectiveExecutionPolicy.SNAPSHOT_PAYLOAD_POLICY
                    + "\",\"serialization\":\"FAILED\"}";
        }
    }

    private void assertPolicyWriteAllowed(SyncActorContext actorContext) {
        if (!POLICY_WRITE_ROLES.contains(normalizeRole(actorContext))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色不能维护数据同步执行策略，仅运营、租户管理员、平台管理员或服务账号可操作");
        }
    }

    private String requireScopeType(String scopeType) {
        String normalized = normalizeCode(scopeType);
        if (!SUPPORTED_SCOPE_TYPES.contains(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "执行策略 scopeType 必须是 SYSTEM、PROJECT、CONNECTOR、DATASOURCE 或 TASK");
        }
        return normalized;
    }

    private Long resolveProjectId(String scopeType,
                                  SyncExecutionPolicyUpsertRequest request,
                                  SyncActorContext actorContext) {
        if (!"PROJECT".equals(scopeType) && !"TASK".equals(scopeType)) {
            return request.getProjectId();
        }
        return dataScopeSupport.resolveProjectForCreate(request.getProjectId(), actorContext);
    }

    private void validateScopeRequiredFields(String scopeType,
                                             SyncExecutionPolicyUpsertRequest request,
                                             Long projectId) {
        if ("PROJECT".equals(scopeType) && projectId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "PROJECT 策略必须归属到具体项目");
        }
        if ("CONNECTOR".equals(scopeType)) {
            /*
             * 连接器作用域允许 connectorType 留空，表示“任意连接器类型”的通用源端/目标端策略。
             * 例如 DEFAULT_SOURCE_READ 只关心 SOURCE 读取方向，不应该被绑定到 MySQL；否则 PostgreSQL、Oracle、
             * SQL Server、文件、API 等新连接器上线时都会缺少基础治理参数，和真实可商用产品的扩展目标冲突。
             */
            if (!SUPPORTED_CONNECTOR_ROLES.contains(defaultText(normalizeCode(request.getConnectorRole()), "ANY"))) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "connectorRole 必须是 SOURCE、TARGET 或 ANY");
            }
        }
        if ("DATASOURCE".equals(scopeType) && request.getDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "DATASOURCE 策略必须填写 datasourceId");
        }
        if ("TASK".equals(scopeType) && request.getSyncTaskId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "TASK 策略必须填写 syncTaskId");
        }
    }

    private void validateNumericBounds(SyncExecutionPolicyUpsertRequest request) {
        requirePositive(request.getTargetRowsPerShard(), "targetRowsPerShard");
        requirePositive(request.getMinShardCount(), "minShardCount");
        requirePositive(request.getMaxShardCount(), "maxShardCount");
        requirePositive(request.getMaxChannel(), "maxChannel");
        requirePositive(request.getTaskGroupSize(), "taskGroupSize");
        requirePositive(request.getReadBatchSize(), "readBatchSize");
        requirePositive(request.getWriteBatchSize(), "writeBatchSize");
        requirePositive(request.getCommitIntervalRecords(), "commitIntervalRecords");
        requirePositive(request.getTimeoutSeconds(), "timeoutSeconds");
        requireNonNegative(request.getMaxRetryCount(), "maxRetryCount");
        requireNonNegative(request.getMaxDirtyRecordCount(), "maxDirtyRecordCount");
        if (request.getMaxDirtyRecordRatio() != null && request.getMaxDirtyRecordRatio().compareTo(BigDecimal.ZERO) < 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "maxDirtyRecordRatio 不能为负数");
        }
        if (request.getMinShardCount() != null && request.getMaxShardCount() != null
                && request.getMinShardCount() > request.getMaxShardCount()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "minShardCount 不能大于 maxShardCount");
        }
    }

    private String resolveScopeKey(String scopeType,
                                   SyncExecutionPolicyUpsertRequest request,
                                   Long projectId) {
        String explicit = trimToNull(request.getScopeKey());
        if (explicit != null) {
            return explicit;
        }
        return switch (scopeType) {
            case "SYSTEM" -> "SYSTEM";
            case "PROJECT" -> "PROJECT:" + projectId;
            case "CONNECTOR" -> "CONNECTOR:"
                    + defaultText(normalizeCode(request.getConnectorRole()), "ANY")
                    + ":" + defaultText(normalizeCode(request.getConnectorType()), "ANY");
            case "DATASOURCE" -> "DATASOURCE:" + request.getDatasourceId();
            case "TASK" -> "TASK:" + request.getSyncTaskId();
            default -> scopeType;
        };
    }

    private int scopeLayer(String scopeType) {
        return switch (normalizeCode(scopeType)) {
            case "SYSTEM" -> 10;
            case "CONNECTOR" -> 20;
            case "DATASOURCE" -> 25;
            case "PROJECT" -> 30;
            case "TASK" -> 40;
            default -> 0;
        };
    }

    private int defaultPriority(String scopeType) {
        return scopeLayer(scopeType);
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<SyncExecutionPolicy> wrapper,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<SyncExecutionPolicy, T> column,
                                 T value) {
        if (value != null && (!(value instanceof String stringValue) || !stringValue.isBlank())) {
            wrapper.eq(column, value);
        }
    }

    private List<Long> distinctTenants(Long... tenantIds) {
        Set<Long> values = new LinkedHashSet<>();
        for (Long tenantId : tenantIds) {
            if (tenantId != null) {
                values.add(tenantId);
            }
        }
        if (values.isEmpty()) {
            values.add(PLATFORM_TENANT_ID);
        }
        return new ArrayList<>(values);
    }

    private long pageNo(Long current) {
        return current == null || current < 1 ? 1L : current;
    }

    private long pageSize(Long size) {
        return size == null || size < 1 ? 20L : Math.min(size, 200L);
    }

    private void requirePositive(Number value, String fieldName) {
        if (value != null && value.longValue() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 必须大于 0");
        }
    }

    private void requireNonNegative(Number value, String fieldName) {
        if (value != null && value.longValue() < 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 不能为负数");
        }
    }

    private Long safeLong(Long value) {
        return value == null ? PLATFORM_TENANT_ID : value;
    }

    private boolean same(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String defaultText(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String normalizeRole(SyncActorContext actorContext) {
        String role = actorContext == null ? null : actorContext.actorRole();
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 策略解析所需的低敏执行事实。
     *
     * <p>把任务、模板、bridge plan 统一压缩成该 record，可以避免策略服务直接依赖模板 JSON 或具体 runner 类型。</p>
     */
    private record ExecutionPolicyFacts(
            Long tenantId,
            Long projectId,
            Long syncTaskId,
            Long sourceDatasourceId,
            Long targetDatasourceId,
            String sourceConnectorType,
            String targetConnectorType
    ) {
        private static ExecutionPolicyFacts from(SyncTask task, SyncTemplate template) {
            return new ExecutionPolicyFacts(
                    task == null ? null : task.getTenantId(),
                    task == null ? null : task.getProjectId(),
                    task == null ? null : task.getId(),
                    template == null ? null : template.getSourceDatasourceId(),
                    template == null ? null : template.getTargetDatasourceId(),
                    normalize(template == null ? null : template.getSourceConnectorType()),
                    normalize(template == null ? null : template.getTargetConnectorType())
            );
        }

        private static ExecutionPolicyFacts from(SyncBatchRunnerBridgePlan bridgePlan, SyncTask task) {
            return new ExecutionPolicyFacts(
                    bridgePlan == null ? (task == null ? null : task.getTenantId()) : bridgePlan.getTenantId(),
                    bridgePlan == null ? (task == null ? null : task.getProjectId()) : bridgePlan.getProjectId(),
                    bridgePlan == null ? (task == null ? null : task.getId()) : bridgePlan.getSyncTaskId(),
                    bridgePlan == null ? null : bridgePlan.getSourceDatasourceId(),
                    bridgePlan == null ? null : bridgePlan.getTargetDatasourceId(),
                    normalize(bridgePlan == null ? null : bridgePlan.getSourceConnectorType()),
                    normalize(bridgePlan == null ? null : bridgePlan.getTargetConnectorType())
            );
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
        }
    }
}
