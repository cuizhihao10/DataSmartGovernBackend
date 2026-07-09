/**
 * @Author : Cui
 * @Date: 2026/06/29 23:45
 * @Description DataSmart Govern Backend - SyncBatchRunnerBridgePlanSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 最小批量执行器桥接计划生成器。
 *
 * <p>本组件是 data-sync 从“控制面可规划”走向“真实执行闭环”的收敛点。它不会直接执行 JDBC，
 * 也不会读取 datasource-management 的凭据或连接串，而是把现有模板、任务、execution、workerPlan 和字段映射解析结果
 * 合成为一个内部桥接计划，明确告诉后续 connector runtime：当前是否可以派发、应该按什么读取策略和写入策略执行、
 * 以及为什么被阻断。</p>
 *
 * <p>这一步的产品价值在于：</p>
 * <p>1. 避免 data-sync 继续只停留在预览和 claim 层，而没有真实执行入口；</p>
 * <p>2. 避免为了快速闭环把 datasource-management 内部 runner 直接拉进 data-sync，造成双控制面和跨模块强耦合；</p>
 * <p>3. 为后续 HTTP/gRPC/SDK batch runner 留出稳定契约，让执行器只负责受控读写，data-sync 仍负责状态机、checkpoint 和回调。</p>
 */
@Component
public class SyncBatchRunnerBridgePlanSupport {

    /**
     * 当前最小 bridge 仅承诺关系型 JDBC 批处理。
     *
     * <p>Kafka、文件、对象存储、REST API、MongoDB 等连接器仍在产品规划内，但它们的读取边界、checkpoint、错误样本和吞吐模型
     * 与 JDBC 表批处理不同。这里先 fail-closed，避免表同步 runner 被滥用到不匹配的连接器场景。</p>
     */
    private static final Set<String> MINIMAL_JDBC_CONNECTORS = Set.of("MYSQL", "POSTGRESQL", "SQL_SERVER");

    private final SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport;
    private final SyncFilterExecutionContractSupport filterExecutionContractSupport;
    private final SyncTemplateScopeContractSupport scopeContractSupport;
    private final SyncOfflineRunnerContractSupport offlineRunnerContractSupport;
    private final SyncCustomSqlExecutionContractSupport customSqlExecutionContractSupport;
    private final SyncObjectMappingExecutionContractSupport objectMappingExecutionContractSupport;

    /**
     * 兼容既有单元测试的构造器。
     *
     * <p>早期测试只关心字段映射执行合同，因此只传入
     * {@link SyncFieldMappingExecutionContractSupport}。现在 bridge 还必须理解同步范围合同，所以这里补一个默认
     * {@link SyncTemplateScopeContractSupport}。生产环境仍优先走下面的 Spring 注入构造器，保证 ObjectMapper 等依赖可统一管理。</p>
     */
    public SyncBatchRunnerBridgePlanSupport(SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport) {
        this(fieldMappingExecutionContractSupport, new SyncFilterExecutionContractSupport(),
                new SyncTemplateScopeContractSupport(),
                new SyncOfflineRunnerContractSupport(),
                new SyncCustomSqlExecutionContractSupport(),
                new SyncObjectMappingExecutionContractSupport());
    }

    /**
     * Spring 注入构造器。
     *
     * <p>将“字段映射是否可直接执行”和“模板范围是否允许被最小 run-once bridge 执行”放在同一个组件里判断，是为了把执行前
     * 最后一层 fail-closed 门禁固定在 data-sync 控制面，而不是把安全判断下放给 datasource-management 或具体 JDBC worker。</p>
     */
    @Autowired
    public SyncBatchRunnerBridgePlanSupport(SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport,
                                            SyncFilterExecutionContractSupport filterExecutionContractSupport,
                                            SyncTemplateScopeContractSupport scopeContractSupport,
                                            SyncOfflineRunnerContractSupport offlineRunnerContractSupport,
                                            SyncCustomSqlExecutionContractSupport customSqlExecutionContractSupport,
                                            SyncObjectMappingExecutionContractSupport objectMappingExecutionContractSupport) {
        this.fieldMappingExecutionContractSupport = fieldMappingExecutionContractSupport;
        this.filterExecutionContractSupport = filterExecutionContractSupport;
        this.scopeContractSupport = scopeContractSupport;
        this.offlineRunnerContractSupport = offlineRunnerContractSupport;
        this.customSqlExecutionContractSupport = customSqlExecutionContractSupport;
        this.objectMappingExecutionContractSupport = objectMappingExecutionContractSupport;
    }

    /**
     * 兼容新增对象映射解析器之前的五参数构造方式。
     *
     * <p>很多单元测试会直接 new 本类，只关心字段映射、过滤条件或 Runner 合同分支。保留该构造器可以让测试不用为了
     * Spring 依赖演进而批量改造，同时生产环境仍通过上面的完整构造器注入同一个
     * {@link SyncObjectMappingExecutionContractSupport} Bean。</p>
     */
    public SyncBatchRunnerBridgePlanSupport(SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport,
                                            SyncFilterExecutionContractSupport filterExecutionContractSupport,
                                            SyncTemplateScopeContractSupport scopeContractSupport,
                                            SyncOfflineRunnerContractSupport offlineRunnerContractSupport,
                                            SyncCustomSqlExecutionContractSupport customSqlExecutionContractSupport) {
        this(fieldMappingExecutionContractSupport, filterExecutionContractSupport, scopeContractSupport,
                offlineRunnerContractSupport, customSqlExecutionContractSupport,
                new SyncObjectMappingExecutionContractSupport());
    }

    /**
     * 兼容旧单元测试和少量手工构造调用点的构造器。
     *
     * <p>本轮新增 {@link SyncCustomSqlExecutionContractSupport} 后，Spring 会优先使用上面的完整构造器；
     * 但仓库里还有一些测试直接 new 四参数构造器。保留该重载可以让老测试继续聚焦 bridge 行为，
     * 同时默认给它补一个无状态的自定义 SQL 解析器。</p>
     */
    public SyncBatchRunnerBridgePlanSupport(SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport,
                                            SyncFilterExecutionContractSupport filterExecutionContractSupport,
                                            SyncTemplateScopeContractSupport scopeContractSupport,
                                            SyncOfflineRunnerContractSupport offlineRunnerContractSupport) {
        this(fieldMappingExecutionContractSupport, filterExecutionContractSupport, scopeContractSupport,
                offlineRunnerContractSupport, new SyncCustomSqlExecutionContractSupport());
    }

    /**
     * 构建内部批量执行器桥接计划。
     *
     * @param execution 当前已认领的执行记录。正常情况下已由 lease 服务置为 RUNNING。
     * @param task execution 所属同步任务。
     * @param template 同步模板，提供对象定位、写入策略和字段映射配置。
     * @param workerPlan claim 后生成的低敏 worker 执行计划。
     * @return 内部桥接计划。dispatchable=false 时，调用方不应派发真实读写。
     */
    public SyncBatchRunnerBridgePlan buildPlan(SyncExecution execution,
                                               SyncTask task,
                                               SyncTemplate template,
                                               SyncWorkerExecutionPlanView workerPlan) {
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (execution == null || task == null || template == null || workerPlan == null) {
            issueCodes.add("BRIDGE_INPUT_CONTEXT_MISSING");
            return blockedPlan(execution, task, template, workerPlan, null, null, null, issueCodes, warnings);
        }

        issueCodes.addAll(workerPlan.issueCodes());
        if (!workerPlan.available() || "BLOCKED".equals(workerPlan.planStatus())) {
            issueCodes.add("WORKER_PLAN_BLOCKED");
        }
        if (!isMinimalJdbcConnector(template.getSourceConnectorType())
                || !isMinimalJdbcConnector(template.getTargetConnectorType())) {
            issueCodes.add("MINIMAL_JDBC_BATCH_BRIDGE_CONNECTOR_UNSUPPORTED");
        }
        if (!isMinimalJdbcBatchMode(template.getSyncMode())) {
            issueCodes.add("MINIMAL_JDBC_BATCH_BRIDGE_MODE_UNSUPPORTED");
        }
        if (SyncWriteStrategy.OVERWRITE == resolveWriteStrategy(template)) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_APPROVED_BRIDGE_POLICY");
        }

        /*
         * workerPlan 是 worker claim 阶段生成的低敏执行计划，但 bridge 不能完全依赖它。
         * 原因有三点：
         * 1. 老版本 workerPlan 可能还没有携带 syncScopeType；
         * 2. 单元测试、灰度实例或跨版本滚动发布期间可能出现“模板已升级、workerPlan 仍旧”的短窗口；
         * 3. 真正派发 datasource-management run-once 前，data-sync 必须自己做最后一次范围合同校验。
         *
         * 因此这里再次解析 template 的范围合同：只有 SINGLE_OBJECT 且非 CUSTOM_SQL_QUERY 的模板才允许进入当前最小 bridge。
         * 多表、整 schema、整库迁移和自定义 SQL 可以被保存、预览、审批和生成任务草稿，但不能被这个单对象 run-once runner 偷偷执行。
         */
        SyncTemplateScopeContract scopeContract = scopeContractSupport.evaluate(template);
        issueCodes.addAll(scopeContract.issueCodes());
        warnings.addAll(scopeContract.warnings());
        if (!scopeContract.executableByMinimalBridge()) {
            issueCodes.add("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        }
        ResolvedObjectLocators objectLocators = resolveObjectLocators(template, issueCodes, warnings);

        SyncFieldMappingExecutionContract fieldMappingContract =
                fieldMappingExecutionContractSupport.parse(template.getFieldMappingConfig(), template.getPrimaryKeyField());
        issueCodes.addAll(fieldMappingContract.getIssueCodes());
        warnings.addAll(fieldMappingContract.getWarnings());
        if (!fieldMappingContract.directlyRunnableByMinimalBridge()) {
            issueCodes.add("FIELD_MAPPING_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
        }

        /*
         * filterConfig 是用户真正关心的“where 条件”。以前它只停留在模板字段里，最小 run-once 实际读取时没有消费，
         * 这会造成“配置页看起来设置了过滤条件，但真实同步全表读取”的严重产品风险。
         * 这里把 filterConfig 解析成内部执行契约：字段名和操作符必须安全，值只通过 internal 请求进入 PreparedStatement。
         */
        SyncFilterExecutionContract filterContract = filterExecutionContractSupport.parse(template.getFilterConfig());
        issueCodes.addAll(filterContract.getIssueCodes());
        warnings.addAll(filterContract.getWarnings());
        if (!filterContract.directlyRunnableByMinimalBridge()) {
            issueCodes.add("FILTER_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
        }

        SyncCustomSqlExecutionContract customSqlContract =
                customSqlExecutionContractSupport.parse(template.getSyncMode(), template.getCustomSqlConfig());
        issueCodes.addAll(customSqlContract.issueCodes());
        warnings.addAll(customSqlContract.warnings());

        List<String> distinctIssues = distinct(issueCodes);
        List<String> distinctWarnings = distinct(warnings);
        if (!blockingIssues(distinctIssues).isEmpty()) {
            return blockedPlan(execution, task, template, workerPlan, fieldMappingContract, filterContract, scopeContract,
                    distinctIssues, distinctWarnings);
        }
        SyncOfflineRunnerJobContract offlineRunnerContract = offlineRunnerContractSupport.buildFromBridgeFacts(
                execution, task, template, workerPlan, fieldMappingContract, scopeContract,
                distinctIssues, distinctWarnings, true);

        return new SyncBatchRunnerBridgePlan(
                true,
                "READY_TO_DISPATCH",
                execution.getTenantId(),
                execution.getProjectId(),
                execution.getWorkspaceId(),
                task.getId(),
                execution.getId(),
                template.getId(),
                template.getSourceDatasourceId(),
                template.getTargetDatasourceId(),
                normalize(template.getSourceConnectorType()),
                normalize(template.getTargetConnectorType()),
                normalize(template.getSyncMode()),
                readStrategy(template.getSyncMode()),
                runnerWriteStrategy(template),
                checkpointType(template.getSyncMode()),
                objectLocators.sourceObjectLocator(),
                objectLocators.targetObjectLocator(),
                fieldMappingContract,
                filterContract.getConditions(),
                customSqlContract.sql(),
                customSqlContract.sqlFingerprint(),
                offlineRunnerContract,
                template.getIncrementalField(),
                zeroIfNull(execution.getRecordsRead()),
                zeroIfNull(execution.getRecordsWritten()),
                zeroIfNull(execution.getFailedRecordCount()),
                distinctIssues,
                distinctWarnings,
                dispatchActions(workerPlan));
    }

    /**
     * 创建阻断计划。
     *
     * <p>阻断计划仍会尽量携带租户、项目、任务、execution 和模板 ID，方便上层 fail 回调或运营诊断定位；
     * 但不会要求调用方继续读取或写入真实数据。</p>
     */
    private SyncBatchRunnerBridgePlan blockedPlan(SyncExecution execution,
                                                  SyncTask task,
                                                  SyncTemplate template,
                                                  SyncWorkerExecutionPlanView workerPlan,
                                                  SyncFieldMappingExecutionContract fieldMappingContract,
                                                  SyncFilterExecutionContract filterContract,
                                                  SyncTemplateScopeContract scopeContract,
                                                  List<String> issueCodes,
                                                  List<String> warnings) {
        SyncOfflineRunnerJobContract offlineRunnerContract = offlineRunnerContractSupport.buildFromBridgeFacts(
                execution, task, template, workerPlan, fieldMappingContract, scopeContract,
                issueCodes, warnings, false);
        return new SyncBatchRunnerBridgePlan(
                false,
                "BLOCKED",
                execution == null ? null : execution.getTenantId(),
                execution == null ? null : execution.getProjectId(),
                execution == null ? null : execution.getWorkspaceId(),
                task == null ? null : task.getId(),
                execution == null ? null : execution.getId(),
                template == null ? null : template.getId(),
                template == null ? null : template.getSourceDatasourceId(),
                template == null ? null : template.getTargetDatasourceId(),
                template == null ? null : normalize(template.getSourceConnectorType()),
                template == null ? null : normalize(template.getTargetConnectorType()),
                template == null ? null : normalize(template.getSyncMode()),
                template == null ? null : readStrategy(template.getSyncMode()),
                template == null ? null : runnerWriteStrategy(template),
                template == null ? null : checkpointType(template.getSyncMode()),
                template == null ? null : objectLocator(template.getSourceSchemaName(), template.getSourceObjectName()),
                template == null ? null : objectLocator(template.getTargetSchemaName(), template.getTargetObjectName()),
                fieldMappingContract,
                filterContract == null ? List.of() : filterContract.getConditions(),
                null,
                null,
                offlineRunnerContract,
                template == null ? null : template.getIncrementalField(),
                zeroIfNull(execution == null ? null : execution.getRecordsRead()),
                zeroIfNull(execution == null ? null : execution.getRecordsWritten()),
                zeroIfNull(execution == null ? null : execution.getFailedRecordCount()),
                distinct(issueCodes),
                distinct(warnings),
                List.of(
                        "DO_NOT_DISPATCH_BATCH_RUNNER",
                        "CALL_FAIL_EXECUTION_WITH_LOW_SENSITIVE_REASON_OR_DEFER_FOR_TEMPLATE_FIX",
                        "KEEP_FIELD_MAPPING_AND_CONNECTION_DETAILS_OUT_OF_EVENTS"
                ));
    }

    /**
     * 过滤出真正阻断最小 bridge 的问题码。
     *
     * <p>workerPlan 中某些 issueCode 是风险提示，例如缺少 retryPolicy、timeoutPolicy 或 partitionPlan，
     * 当前不直接阻断最小闭环；但连接器不支持、状态不对、对象缺失、字段映射不可运行、覆盖写入未审批等问题必须阻断。</p>
     */
    private List<String> blockingIssues(List<String> issueCodes) {
        return issueCodes.stream()
                .filter(issueCode -> !nonBlockingIssue(issueCode))
                .toList();
    }

    private boolean nonBlockingIssue(String issueCode) {
        return "RETRY_POLICY_NOT_DECLARED".equals(issueCode)
                || "TIMEOUT_POLICY_NOT_DECLARED".equals(issueCode)
                || "PARTITION_PLAN_NOT_DECLARED".equals(issueCode)
                || "WRITE_STRATEGY_DEFAULTED_TO_APPEND".equals(issueCode)
                || "CHECKPOINT_BOUNDARY_NOT_DECLARED".equals(issueCode);
    }

    private List<String> dispatchActions(SyncWorkerExecutionPlanView workerPlan) {
        List<String> actions = new ArrayList<>();
        actions.add("DISPATCH_TO_CONNECTOR_RUNTIME_RUN_ONCE");
        actions.add("SEND_HEARTBEAT_UNTIL_RUNNER_RESULT_RETURNS");
        if (workerPlan.checkpointRequired()) {
            actions.add("WRITE_CHECKPOINT_AFTER_SAFE_BATCH_RESULT");
        }
        actions.add("CALL_COMPLETE_OR_FAIL_CALLBACK_AFTER_RUNNER_RESULT");
        return List.copyOf(actions);
    }

    private boolean isMinimalJdbcConnector(String connectorType) {
        return MINIMAL_JDBC_CONNECTORS.contains(normalize(connectorType));
    }

    private boolean isMinimalJdbcBatchMode(String syncMode) {
        SyncMode mode = resolveMode(syncMode);
        return mode == SyncMode.FULL
                || mode == SyncMode.INCREMENTAL_TIME
                || mode == SyncMode.INCREMENTAL_ID
                || mode == SyncMode.SCHEDULED_FULL
                || mode == SyncMode.SCHEDULED_BATCH
                || mode == SyncMode.ONE_TIME_MIGRATION
                || mode == SyncMode.REPLAY
                || mode == SyncMode.BACKFILL
                || mode == SyncMode.CUSTOM_SQL_QUERY;
    }

    private String readStrategy(String syncMode) {
        SyncMode mode = resolveMode(syncMode);
        if (mode == null) {
            return "UNKNOWN";
        }
        return switch (mode) {
            case FULL, SCHEDULED_FULL, ONE_TIME_MIGRATION -> "FULL_OBJECT_SCAN";
            case INCREMENTAL_TIME -> "INCREMENTAL_TIME_WINDOW";
            case INCREMENTAL_ID -> "INCREMENTAL_ID_RANGE";
            case SCHEDULED_BATCH -> "SCHEDULED_BATCH_WINDOW";
            case REPLAY -> "REPLAY_FROM_CHECKPOINT";
            case BACKFILL -> "BACKFILL_RANGE";
            case CDC_STREAMING -> "STREAMING_OFFSET";
            case OFFLINE_IMPORT, OFFLINE_EXPORT -> "ARTIFACT_STAGE";
            case CUSTOM_SQL_QUERY -> "CUSTOM_SQL_RESULT_SET";
        };
    }

    private String checkpointType(String syncMode) {
        SyncMode mode = resolveMode(syncMode);
        if (mode == null) {
            return "UNKNOWN";
        }
        return switch (mode) {
            case FULL, SCHEDULED_FULL, ONE_TIME_MIGRATION -> "NONE_OR_FINAL_WATERMARK";
            case INCREMENTAL_TIME -> "TIME_FIELD";
            case INCREMENTAL_ID -> "ID_FIELD";
            case SCHEDULED_BATCH -> "BATCH_WINDOW";
            case REPLAY -> "CHECKPOINT_REF";
            case BACKFILL -> "BACKFILL_RANGE";
            case CDC_STREAMING -> "STREAMING_OFFSET";
            case OFFLINE_IMPORT, OFFLINE_EXPORT -> "ARTIFACT_STAGE";
            case CUSTOM_SQL_QUERY -> "QUERY_RESULT_BOUNDARY";
        };
    }

    private String objectLocator(String schemaName, String objectName) {
        if (!hasText(objectName)) {
            return null;
        }
        if (!hasText(schemaName)) {
            return objectName.trim();
        }
        return schemaName.trim() + "." + objectName.trim();
    }

    /**
     * 解析真实执行应该使用的源/目标对象定位。
     *
     * <p>历史模板会把单对象同步的源表和目标表分别保存到 {@code sourceObjectName/targetObjectName} 顶层字段；
     * 新版创建向导则以 {@code objectMappingConfig.mappings} 作为用户选择对象的事实来源。两者同时存在时，必须优先使用
     * objectMappingConfig，因为它是用户在第二步“对象映射”里最后确认的配置。否则就会出现页面显示目标表 A，
     * 但 worker 实际写入旧目标表 B 的严重错写风险。</p>
     *
     * <p>当前最小 run-once bridge 只执行单对象，所以这里仅在 objectMappingConfig 恰好解析出一条映射时覆盖顶层字段。
     * 多条映射应由 OBJECT_LIST fan-out 或 DataX-style Runner 处理，而不是由单对象 bridge 私自取第一条。</p>
     */
    private ResolvedObjectLocators resolveObjectLocators(SyncTemplate template,
                                                         List<String> issueCodes,
                                                         List<String> warnings) {
        if (template == null) {
            return new ResolvedObjectLocators(null, null);
        }
        ResolvedObjectLocators legacyLocators = new ResolvedObjectLocators(
                objectLocator(template.getSourceSchemaName(), template.getSourceObjectName()),
                objectLocator(template.getTargetSchemaName(), template.getTargetObjectName()));
        if (!hasText(template.getObjectMappingConfig())) {
            return legacyLocators;
        }

        SyncObjectMappingExecutionContract objectContract = objectMappingExecutionContractSupport.parse(template);
        issueCodes.addAll(objectContract.issueCodes());
        warnings.addAll(objectContract.warnings());
        if (!objectContract.issueCodes().isEmpty()) {
            return legacyLocators;
        }
        if (objectContract.mappings().isEmpty()) {
            issueCodes.add("OBJECT_MAPPING_EXECUTABLE_ITEMS_EMPTY");
            return legacyLocators;
        }
        if (objectContract.mappings().size() > 1) {
            issueCodes.add("OBJECT_MAPPING_MULTIPLE_ITEMS_REQUIRE_FAN_OUT_RUNNER");
            return legacyLocators;
        }

        SyncObjectMappingExecutionItem item = objectContract.mappings().get(0);
        String sourceObjectLocator = objectLocator(item.sourceSchemaName(), item.sourceObjectName());
        String targetObjectLocator = objectLocator(item.targetSchemaName(), item.targetObjectName());
        if (!hasText(sourceObjectLocator) || !hasText(targetObjectLocator)) {
            issueCodes.add("OBJECT_MAPPING_LOCATOR_UNRESOLVED");
            return legacyLocators;
        }
        warnings.add("OBJECT_MAPPING_USED_AS_SINGLE_OBJECT_BRIDGE_LOCATOR");
        return new ResolvedObjectLocators(sourceObjectLocator, targetObjectLocator);
    }

    private SyncMode resolveMode(String syncMode) {
        String normalized = normalize(syncMode);
        if (normalized == null) {
            return null;
        }
        try {
            return SyncMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized == null ? defaultValue : normalized;
    }

    /**
     * 把控制面保存的产品级写入策略翻译成 runner 可执行策略。
     *
     * <p>当前新建向导只暴露 INSERT/UPDATE，但最小 JDBC runner 的合同仍沿用 APPEND/UPSERT。这里集中做翻译，避免在 Controller、
     * Service、workerPlan、offline job plan 多处散落 if/else。后续当真实 runner 也升级为 INSERT/UPDATE 语义时，只需要调整这一个方法。</p>
     */
    private String runnerWriteStrategy(SyncTemplate template) {
        return resolveWriteStrategy(template).toRunnerStrategy();
    }

    private SyncWriteStrategy resolveWriteStrategy(SyncTemplate template) {
        try {
            return SyncWriteStrategy.fromValueForMode(
                    template == null ? null : template.getWriteStrategy(),
                    template == null ? null : template.getSyncMode());
        } catch (IllegalArgumentException exception) {
            return SyncWriteStrategy.INSERT;
        }
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ResolvedObjectLocators(String sourceObjectLocator, String targetObjectLocator) {
    }
}
