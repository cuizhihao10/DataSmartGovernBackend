/**
 * @Author : Cui
 * @Date: 2026/06/20 02:42
 * @Description DataSmart Govern Backend - SyncBatchExecutionPlanBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步批处理执行计划构建器。
 *
 * <p>该组件的职责是把“模板配置 + 任务运行上下文 + 数据源类型 + execution 记录”
 * 翻译为 worker 可以消费的结构化执行计划。</p>
 *
 * <p>为什么这一步要放在 Java 控制面：</p>
 * <p>1. 模板、任务状态、审批、队列、租约、checkpoint 都在 Java 业务层管理；</p>
 * <p>2. 如果 worker 自己解释同步模式和写入策略，多个 worker 版本很容易出现语义分歧；</p>
 * <p>3. 控制面统一生成计划后，后续可以把计划版本、灰度、审计、回放、兼容性校验集中治理；</p>
 * <p>4. 该计划仍不暴露 raw SQL 和凭证，避免把敏感执行细节推到普通 API 响应里。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncBatchExecutionPlanBuilder {

    private static final String PLAN_VERSION = "datasmart.datasource.sync-batch-plan.v1";
    private static final String EXECUTION_BOUNDARY = "BATCH_EXECUTION_CONTRACT_NO_RAW_SQL_NO_CREDENTIALS";

    /**
     * 执行器配置。
     * 当前读取推荐 fetchSize、写入批大小、checkpoint 保存间隔等默认值。
     */
    private final SyncExecutorProperties syncExecutorProperties;

    /**
     * 构建批处理执行计划。
     *
     * @param task 已被执行器认领并进入 RUNNING 的同步任务。
     * @param template 任务绑定的同步模板。
     * @param source 源端数据源配置。这里只读取 ID 和 type，不读取或返回连接串与凭证。
     * @param target 目标端数据源配置。这里只读取 ID 和 type，不读取或返回连接串与凭证。
     * @param execution 本次执行记录。
     * @param executorId 当前执行器实例标识。
     * @param leaseExpireAt 当前认领租约过期时间。
     * @return 低敏、结构化、可版本化的批处理执行计划。
     */
    public SyncBatchExecutionPlan buildPlan(SyncTask task,
                                            SyncTemplate template,
                                            DataSourceConfig source,
                                            DataSourceConfig target,
                                            SyncExecution execution,
                                            String executorId,
                                            LocalDateTime leaseExpireAt) {
        SyncMode syncMode = SyncMode.fromValue(template.getSyncMode());
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
        List<String> warnings = collectWarnings(template, syncMode, writeStrategy);

        return new SyncBatchExecutionPlan(
                PLAN_VERSION,
                EXECUTION_BOUNDARY,
                task.getId(),
                execution.getId(),
                buildReadPlan(template, source, syncMode),
                buildWritePlan(template, target, writeStrategy),
                buildCheckpointPlan(template, syncMode),
                buildRuntimeControlPlan(task, executorId, leaseExpireAt),
                warnings,
                LocalDateTime.now()
        );
    }

    /**
     * 构建源端读取计划。
     *
     * <p>读取计划只回答“worker 应该以什么语义读取哪个源端对象”，不负责生成真正的 JDBC SQL。
     * 这样做有两个好处：第一，控制面可以统一表达 FULL、INCREMENTAL、REPLAY、BACKFILL 等模式；
     * 第二，真实 SQL 生成仍留在连接器 worker 内部，便于按 MySQL、PostgreSQL、SQL Server 等方言差异隔离实现，
     * 同时避免把 where 条件、业务过滤表达式或样本数据暴露到普通 claim 响应中。</p>
     */
    private SyncBatchExecutionPlan.ReadPlan buildReadPlan(SyncTemplate template,
                                                          DataSourceConfig source,
                                                          SyncMode syncMode) {
        return new SyncBatchExecutionPlan.ReadPlan(
                source.getType(),
                source.getId(),
                objectLocator(template.getSourceSchemaName(), template.getSourceObjectName()),
                resolveReadStrategy(syncMode),
                syncMode.name(),
                template.getIncrementalField(),
                hasText(template.getPartitionConfig()),
                recommendedFetchSize(),
                requiredReadCapabilities(syncMode)
        );
    }

    /**
     * 构建目标端写入计划。
     *
     * <p>写入计划把模板中的 writeStrategy 翻译为 worker 更容易执行的冲突处理语义。
     * 例如 UPSERT 不只是一个字符串，它隐含“目标端必须能按主键或唯一键判断冲突、重复执行不能产生重复数据”的业务要求。
     * 当前计划只返回字段名、批大小、提交间隔和能力要求，不返回真实 INSERT/UPDATE/MERGE 语句；
     * 真实写入语句应由后续 `SyncBatchWriter` 根据连接器方言在受控执行环境内生成。</p>
     */
    private SyncBatchExecutionPlan.WritePlan buildWritePlan(SyncTemplate template,
                                                            DataSourceConfig target,
                                                            SyncWriteStrategy writeStrategy) {
        return new SyncBatchExecutionPlan.WritePlan(
                target.getType(),
                target.getId(),
                objectLocator(template.getTargetSchemaName(), template.getTargetObjectName()),
                writeStrategy.name(),
                resolveConflictPolicy(writeStrategy),
                writeStrategy.requiresTargetUniqueConstraint(),
                template.getPrimaryKeyField(),
                recommendedWriteBatchSize(),
                recommendedCommitIntervalRecords(),
                requiredWriteCapabilities(writeStrategy)
        );
    }

    /**
     * 构建 checkpoint 计划。
     *
     * <p>checkpoint 是数据同步任务能否“失败后继续跑”的核心。
     * 全量任务通常只需要记录完成标记，而增量、回放、补数、CDC/流式任务必须记录水位、游标、分片范围或 offset。
     * 这里故意只描述 checkpoint 类型和保存节奏，不返回真实水位值；
     * 真实 checkpoint 值属于执行状态，应保存在 worker 内部状态或 sync checkpoint 表中，避免进入 API 摘要和审计展示层。</p>
     */
    private SyncBatchExecutionPlan.CheckpointPlan buildCheckpointPlan(SyncTemplate template,
                                                                      SyncMode syncMode) {
        boolean resumeRequired = requiresCheckpointResume(syncMode);
        return new SyncBatchExecutionPlan.CheckpointPlan(
                resolveCheckpointType(syncMode),
                resolveInitialCheckpointPolicy(syncMode),
                resumeRequired,
                hasText(template.getPartitionConfig()),
                recommendedCheckpointPersistEveryRecords(),
                "WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY"
        );
    }

    /**
     * 构建运行控制计划。
     *
     * <p>运行控制计划描述的是 worker 与 Java 控制面之间的协议，而不是数据读取写入本身。
     * 这里统一下发租约过期时间、是否需要心跳、超时、最大重试次数、幂等范围和必须回调的动作，
     * 后续真实 worker 执行时必须按这些约束持续回写 heartbeat/progress/complete/fail，
     * 否则控制面无法判断任务是否卡死、是否应该恢复租约、是否可以安全重试。</p>
     */
    private SyncBatchExecutionPlan.RuntimeControlPlan buildRuntimeControlPlan(SyncTask task,
                                                                              String executorId,
                                                                              LocalDateTime leaseExpireAt) {
        return new SyncBatchExecutionPlan.RuntimeControlPlan(
                executorId,
                leaseExpireAt,
                true,
                task.getTimeoutSeconds(),
                task.getMaxRetryCount(),
                "taskId:" + task.getId() + "/executionId:" + task.getLastExecutionId() + "/shard",
                List.of("heartbeat", "progress", "complete", "fail")
        );
    }

    /**
     * 收集执行前告警。
     *
     * <p>这些告警不一定立即阻断 claim，因为当前阶段的目标是把执行计划交给 worker；
     * 但它们会提示后续 worker 或运维侧注意潜在风险。
     * 例如 UPSERT 缺少主键会破坏幂等性，OVERWRITE 属于高风险覆盖写入，
     * 增量缺少 incrementalField 会导致水位无法推进，大表全量/补数缺少 partitionConfig 会影响并发与恢复能力。</p>
     */
    private List<String> collectWarnings(SyncTemplate template,
                                         SyncMode syncMode,
                                         SyncWriteStrategy writeStrategy) {
        List<String> warnings = new ArrayList<>();
        if (writeStrategy.requiresTargetUniqueConstraint() && !hasText(template.getPrimaryKeyField())) {
            warnings.add("当前写入策略需要主键或唯一键，但模板未配置 primaryKeyField，worker 执行前应再次阻断或要求补齐配置");
        }
        if (writeStrategy.isDestructiveRewrite()) {
            warnings.add("OVERWRITE 属于覆盖式写入，worker 执行前应确认审批、备份和回滚策略已经就绪");
        }
        if (requiresCheckpointResume(syncMode) && !hasText(template.getIncrementalField())
                && (syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID)) {
            warnings.add("增量模式缺少 incrementalField，worker 无法安全推进水位");
        }
        if (!hasText(template.getPartitionConfig())
                && (syncMode == SyncMode.FULL || syncMode == SyncMode.BACKFILL)) {
            warnings.add("全量或补数场景未配置 partitionConfig，大表执行时可能无法分区并行或精准恢复");
        }
        return warnings;
    }

    /**
     * 将产品层同步模式翻译为读取策略。
     *
     * <p>同步模式是用户/模板视角的配置，读取策略是 worker 视角的执行动作。
     * 通过这层映射，后续新增 PostgreSQL、Kafka、MongoDB、对象存储等连接器时，
     * 可以复用同一个控制面语义，再由各连接器决定如何落地读取。</p>
     */
    private String resolveReadStrategy(SyncMode syncMode) {
        return switch (syncMode) {
            case FULL, SCHEDULED_BATCH, OFFLINE_IMPORT, OFFLINE_EXPORT -> "FULL_OBJECT_SCAN";
            case INCREMENTAL_TIME -> "INCREMENTAL_TIME_WINDOW";
            case INCREMENTAL_ID -> "INCREMENTAL_ID_RANGE";
            case REPLAY -> "REPLAY_FROM_CHECKPOINT";
            case BACKFILL -> "BACKFILL_PARTITION_RANGE";
            case STREAMING, CDC -> "STREAMING_CONNECTOR_REQUIRED";
        };
    }

    /**
     * 将写入策略翻译为冲突处理策略。
     *
     * <p>这里把用户可理解的 APPEND/UPSERT/REPLACE/OVERWRITE 转成 worker 需要遵守的冲突语义。
     * 特别是 OVERWRITE 会被标记为需要审批的破坏性写入，后续可与 permission-admin 审批流、
     * task-management 任务状态机和审计日志联动，避免生产环境误覆盖。</p>
     */
    private String resolveConflictPolicy(SyncWriteStrategy writeStrategy) {
        return switch (writeStrategy) {
            case APPEND -> "APPEND_ONLY_NO_CONFLICT_CHECK";
            case UPSERT -> "UPSERT_BY_PRIMARY_OR_UNIQUE_KEY";
            case INSERT_IGNORE -> "IGNORE_ON_TARGET_CONFLICT";
            case REPLACE -> "REPLACE_ON_TARGET_CONFLICT";
            case OVERWRITE -> "DESTRUCTIVE_OVERWRITE_REQUIRES_APPROVAL";
        };
    }

    /**
     * 根据同步模式决定 checkpoint 类型。
     *
     * <p>不同模式的恢复语义完全不同：时间增量需要时间水位，ID 增量需要 ID 水位，
     * 回放需要历史游标，补数需要分片范围，流式/CDC 需要 offset。
     * 将类型显式写入计划，可以让 worker、审计、运维诊断在不读取模板全文的情况下理解恢复边界。</p>
     */
    private String resolveCheckpointType(SyncMode syncMode) {
        return switch (syncMode) {
            case INCREMENTAL_TIME -> "TIME_WATERMARK";
            case INCREMENTAL_ID -> "ID_WATERMARK";
            case REPLAY -> "REPLAY_CURSOR";
            case BACKFILL -> "PARTITION_RANGE_CURSOR";
            case STREAMING, CDC -> "STREAM_OFFSET";
            case FULL, SCHEDULED_BATCH, OFFLINE_IMPORT, OFFLINE_EXPORT -> "FULL_SCAN_COMPLETION_MARKER";
        };
    }

    /**
     * 决定没有历史 checkpoint 时的起始策略。
     *
     * <p>首次运行是同步系统最容易出错的场景之一。
     * 如果是增量任务，worker 需要知道应从模板过滤条件或可用最低水位开始；
     * 如果是回放任务，缺少历史 checkpoint 反而应该阻断；
     * 如果是全量任务，则通常不依赖历史 checkpoint。</p>
     */
    private String resolveInitialCheckpointPolicy(SyncMode syncMode) {
        return switch (syncMode) {
            case INCREMENTAL_TIME, INCREMENTAL_ID -> "START_FROM_TEMPLATE_FILTER_OR_LOWEST_AVAILABLE_WATERMARK";
            case REPLAY -> "START_FROM_REQUIRED_EXISTING_CHECKPOINT";
            case BACKFILL -> "START_FROM_PARTITION_RANGE_BEGIN";
            case STREAMING, CDC -> "START_FROM_CONNECTOR_OFFSET_POLICY";
            case FULL, SCHEDULED_BATCH, OFFLINE_IMPORT, OFFLINE_EXPORT -> "NO_PREVIOUS_CHECKPOINT_REQUIRED";
        };
    }

    /**
     * 判断当前同步模式是否需要断点续跑能力。
     *
     * <p>并不是所有任务都必须维护强 checkpoint。
     * 简单全量任务可以依赖任务完成标记，而增量、回放、补数、流式/CDC 一旦没有断点，
     * 失败后就可能重复写入、漏读或无法定位恢复位置，因此必须要求 worker 支持 resume。</p>
     */
    private boolean requiresCheckpointResume(SyncMode syncMode) {
        return syncMode == SyncMode.INCREMENTAL_TIME
                || syncMode == SyncMode.INCREMENTAL_ID
                || syncMode == SyncMode.REPLAY
                || syncMode == SyncMode.BACKFILL
                || syncMode == SyncMode.STREAMING
                || syncMode == SyncMode.CDC;
    }

    /**
     * 汇总读取侧 worker 必须具备的能力。
     *
     * <p>能力清单是后续执行器调度的基础。
     * 当前 claim 还没有按能力筛选 executor，但计划先把能力要求写清楚，
     * 之后可以自然扩展为“只把增量任务派给支持 checkpoint 的 worker”“只把回放任务派给支持 replay 的 worker”。</p>
     */
    private List<String> requiredReadCapabilities(SyncMode syncMode) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("JDBC_BATCH_READ");
        if (requiresCheckpointResume(syncMode)) {
            capabilities.add("CHECKPOINT_AWARE_READ");
        }
        if (syncMode == SyncMode.BACKFILL || syncMode == SyncMode.REPLAY) {
            capabilities.add("RANGE_OR_REPLAY_READ");
        }
        return capabilities;
    }

    /**
     * 汇总写入侧 worker 必须具备的能力。
     *
     * <p>写入能力关系到数据一致性与生产安全。
     * 普通追加只需要批量写入能力；UPSERT/REPLACE/INSERT_IGNORE 需要幂等冲突处理；
     * OVERWRITE 还必须具备破坏性写入保护能力，后续可对接审批、备份和回滚校验。</p>
     */
    private List<String> requiredWriteCapabilities(SyncWriteStrategy writeStrategy) {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("JDBC_BATCH_WRITE");
        if (writeStrategy.requiresTargetUniqueConstraint()) {
            capabilities.add("IDEMPOTENT_CONFLICT_WRITE");
        }
        if (writeStrategy.isDestructiveRewrite()) {
            capabilities.add("DESTRUCTIVE_WRITE_GUARD");
        }
        return capabilities;
    }

    /**
     * 拼接数据对象定位符。
     *
     * <p>objectLocator 只用于定位对象，例如 `schema.table`。
     * 它不是 SQL 片段，不包含 where、order by、limit 或任何样本数据，
     * 因此可以安全地进入执行计划和控制台摘要。</p>
     */
    private String objectLocator(String schemaName, String objectName) {
        if (!hasText(schemaName)) {
            return objectName;
        }
        return schemaName + "." + objectName;
    }

    /**
     * 读取推荐 fetchSize，并在配置异常时回退到安全默认值。
     */
    private Integer recommendedFetchSize() {
        return positiveOrDefault(syncExecutorProperties.getRecommendedJdbcFetchSize(), 1000);
    }

    /**
     * 读取推荐写入批大小，并在配置异常时回退到安全默认值。
     */
    private Integer recommendedWriteBatchSize() {
        return positiveOrDefault(syncExecutorProperties.getRecommendedJdbcWriteBatchSize(), 1000);
    }

    /**
     * 读取推荐提交间隔，并在配置异常时回退到安全默认值。
     */
    private Integer recommendedCommitIntervalRecords() {
        return positiveOrDefault(syncExecutorProperties.getRecommendedJdbcCommitIntervalRecords(), 1000);
    }

    /**
     * 读取推荐 checkpoint 保存间隔，并在配置异常时回退到安全默认值。
     */
    private Integer recommendedCheckpointPersistEveryRecords() {
        return positiveOrDefault(syncExecutorProperties.getCheckpointPersistEveryRecords(), 5000);
    }

    /**
     * 配置值兜底工具。
     *
     * <p>配置中心、环境变量或测试构造对象都可能传入 null/0/负数。
     * 对批处理参数来说，这类值会导致 worker 行为异常，因此这里统一回退到代码内默认值。</p>
     */
    private Integer positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    /**
     * 字符串非空判断。
     *
     * <p>这里不额外引入 Spring `StringUtils`，是为了保持该构建器足够轻量，
     * 也避免把简单判断分散到多个工具依赖中。</p>
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
