/**
 * @Author : Cui
 * @Date: 2026/07/05 14:07
 * @Description DataSmart Govern Backend - SyncOfflineJobPlanSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * DataX 风格离线作业计划生成组件。
 *
 * <p>这个组件把同步模板转换为“低敏作业计划”，用于回答未来离线 runner 的核心编排问题：</p>
 * <p>1. 使用哪类 Reader 读取源端，例如 JDBC_READER、FILE_READER、OBJECT_STORAGE_READER；</p>
 * <p>2. 使用哪类 Writer 写入目标端，例如 JDBC_WRITER、FILE_WRITER、OBJECT_STORAGE_WRITER；</p>
 * <p>3. 任务是全量扫描、定时批量、自定义 SQL 结果集、回放、补数还是离线导入导出；</p>
 * <p>4. 是否需要任务级调度配置、checkpoint handoff、审批和专用离线 runner；</p>
 * <p>5. 当前为什么不能被最小 run-once bridge 直接执行。</p>
 *
 * <p>它不是一个真实执行器：不会连接源库、不会读取数据、不会写目标端、不会执行 SQL、不会分配 worker。
 * 这样设计的原因是商业化数据同步系统通常要把“控制面规划”和“执行面搬运”拆开：控制面可以提前给用户、Agent、
 * 运维和审批系统解释风险，执行面再根据计划读取低敏引用、凭据托管、限流、分片和 checkpoint。</p>
 *
 * <p>安全边界：本组件可以解析 JSON 来判断是否存在 mappings、statementRef 或 inline sql，但绝不把 JSON 原文、
 * SQL 正文、过滤条件、分区正文、字段映射正文或 checkpoint 值放进响应。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncOfflineJobPlanSupport {

    public static final String PLAN_READY = "PLAN_READY";
    public static final String PLAN_READY_REQUIRES_APPROVAL = "PLAN_READY_REQUIRES_APPROVAL";
    public static final String PLAN_READY_DEDICATED_RUNNER_REQUIRED = "PLAN_READY_DEDICATED_RUNNER_REQUIRED";
    public static final String NOT_OFFLINE_CHANNEL = "NOT_OFFLINE_CHANNEL";
    public static final String BLOCKED = "BLOCKED";

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_OFFLINE_JOB_PLAN";

    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;
    private final SyncTemplateScopeContractSupport scopeContractSupport;
    private final SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport;
    private final ObjectMapper objectMapper;

    /**
     * 生成离线作业计划。
     *
     * @param template 已经由服务层完成租户、项目、SELF 数据范围校验的同步模板。
     * @return 低敏离线作业计划。返回结果可以被 UI、Agent、审批流或运营台读取，但不能被当作可执行 SQL/job JSON。
     */
    public SyncOfflineJobPlanResponse buildPlan(SyncTemplate template) {
        List<String> issueCodes = new ArrayList<>();
        List<String> failClosedReasons = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();
        List<String> performanceNotes = new ArrayList<>();
        List<String> safetyNotes = new ArrayList<>();

        SyncMode syncMode = resolveMode(template, issueCodes, recommendedActions);
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template, issueCodes, recommendedActions);
        SyncTransferChannel transferChannel = SyncTransferChannelSupport.resolve(syncMode);
        boolean offlineChannel = transferChannel == SyncTransferChannel.OFFLINE;
        performanceNotes.add(SyncTransferChannelSupport.explanation(transferChannel));

        SyncTemplateScopeContract scopeContract = scopeContractSupport.evaluate(template);
        issueCodes.addAll(scopeContract.issueCodes());
        recommendedActions.addAll(scopeContract.recommendedActions());
        safetyNotes.addAll(scopeContract.warnings());

        SyncFieldMappingExecutionContract fieldMappingContract = fieldMappingExecutionContractSupport.parse(
                template.getFieldMappingConfig(), template.getPrimaryKeyField());
        issueCodes.addAll(fieldMappingContract.getIssueCodes());
        safetyNotes.addAll(fieldMappingContract.getWarnings());

        SyncConnectorCompatibilityView compatibility = resolveCompatibility(template, syncMode, issueCodes,
                recommendedActions, performanceNotes, safetyNotes);
        boolean connectorCompatibilitySupported = compatibility != null && compatibility.supported();
        boolean checkpointRequired = compatibility != null && compatibility.checkpointRequired();
        boolean checkpointHandoffRequired = checkpointRequired;
        CustomSqlStatementPolicy sqlStatementPolicy = resolveSqlStatementPolicy(template, scopeContract,
                issueCodes, recommendedActions, safetyNotes);

        evaluateWriteStrategy(template, writeStrategy, issueCodes, recommendedActions, safetyNotes);
        evaluateOfflineBoundary(offlineChannel, syncMode, scopeContract, fieldMappingContract,
                checkpointRequired, connectorCompatibilitySupported, failClosedReasons, recommendedActions,
                performanceNotes, safetyNotes);

        List<String> distinctIssues = distinct(issueCodes);
        boolean hardBlocked = hasHardBlockingIssue(distinctIssues, offlineChannel);
        boolean executableByMinimalBridge = offlineChannel
                && scopeContract.executableByMinimalBridge()
                && modeExecutableByMinimalBridge(syncMode)
                && !checkpointRequired
                && fieldMappingContract.directlyRunnableByMinimalBridge()
                && connectorCompatibilitySupported
                && !hardBlocked;
        boolean approvalRequired = scopeContract.requiresApproval()
                || writeStrategy != null && writeStrategy.isDestructiveRewrite()
                || syncMode == SyncMode.CUSTOM_SQL_QUERY;
        boolean dedicatedOfflineRunnerRequired = offlineChannel && !executableByMinimalBridge && !hardBlocked;
        boolean planReady = offlineChannel && !hardBlocked;
        boolean canCreateTaskDraft = syncMode != null
                && writeStrategy != null
                && connectorCompatibilitySupported
                && !scopeContract.hasBlockingIssues()
                && !hasWriteStrategyBlockingIssue(distinctIssues);

        String planStatus = resolvePlanStatus(offlineChannel, hardBlocked, approvalRequired,
                dedicatedOfflineRunnerRequired);
        String runnerBoundary = resolveRunnerBoundary(offlineChannel, executableByMinimalBridge,
                dedicatedOfflineRunnerRequired);
        String scheduleSemantics = SyncOfflineJobPlanClassificationSupport.scheduleSemantics(syncMode, recommendedActions);
        String checkpointPolicy = checkpointRequired
                ? "CHECKPOINT_HANDOFF_REQUIRED_BY_MODE"
                : "CHECKPOINT_OPTIONAL_OR_NOT_REQUIRED_FOR_BOUNDED_FULL_JOB";
        String approvalPolicy = approvalRequired
                ? "APPROVAL_REQUIRED_BEFORE_EXECUTION"
                : "APPROVAL_NOT_REQUIRED_BY_TEMPLATE_PLAN";

        return new SyncOfflineJobPlanResponse(
                template.getId(),
                template.getTenantId(),
                template.getProjectId(),
                template.getWorkspaceId(),
                template.getSourceDatasourceId(),
                template.getTargetDatasourceId(),
                normalize(template.getSourceConnectorType()),
                normalize(template.getTargetConnectorType()),
                syncMode == null ? normalize(template.getSyncMode()) : syncMode.name(),
                transferChannel == null ? null : transferChannel.name(),
                SyncTransferChannelSupport.referenceRuntime(transferChannel),
                scopeContract.scopeType(),
                offlineChannel,
                planStatus,
                planReady,
                canCreateTaskDraft,
                executableByMinimalBridge,
                dedicatedOfflineRunnerRequired,
                SyncOfflineJobPlanClassificationSupport.readerFamily(template.getSourceConnectorType()),
                SyncOfflineJobPlanClassificationSupport.writerFamily(template.getTargetConnectorType()),
                SyncOfflineJobPlanClassificationSupport.modeFamily(syncMode),
                SyncOfflineJobPlanClassificationSupport.shardStrategy(syncMode, scopeContract, template),
                scheduleSemantics,
                sqlStatementPolicy.policy(),
                checkpointPolicy,
                approvalPolicy,
                runnerBoundary,
                syncMode != null && syncMode.requiresTaskScheduleConfig(),
                sqlStatementPolicy.statementRefDeclared(),
                sqlStatementPolicy.inlineSqlDeclared(),
                checkpointRequired,
                checkpointHandoffRequired,
                approvalRequired,
                hasText(template.getFieldMappingConfig()),
                fieldMappingContract.directlyRunnableByMinimalBridge(),
                scopeContract.objectMappingDeclared(),
                scopeContract.selectedObjectCount(),
                hasText(template.getFilterConfig()),
                hasText(template.getPartitionConfig()),
                distinctIssues,
                distinct(failClosedReasons),
                distinct(recommendedActions),
                distinct(performanceNotes),
                distinct(safetyNotes),
                PAYLOAD_POLICY
        );
    }

    /**
     * 解析同步模式。
     *
     * <p>未知模式不抛异常，而是转成 issueCode。规划接口的价值在于一次性返回可解释问题清单，
     * 如果第一个错误就抛出，前端和 Agent 就无法继续给用户整理完整修复建议。</p>
     */
    private SyncMode resolveMode(SyncTemplate template, List<String> issueCodes, List<String> recommendedActions) {
        String syncMode = normalize(template.getSyncMode());
        if (syncMode == null) {
            issueCodes.add("SYNC_MODE_MISSING");
            recommendedActions.add("离线作业计划必须先声明用户可选传输模式，例如 FULL、SCHEDULED_FULL、SCHEDULED_BATCH 或 CUSTOM_SQL_QUERY");
            return null;
        }
        try {
            return SyncMode.valueOf(syncMode);
        } catch (IllegalArgumentException exception) {
            issueCodes.add("SYNC_MODE_UNSUPPORTED");
            recommendedActions.add("将 syncMode 调整为平台 SyncMode 枚举支持的值，再生成离线作业计划");
            return null;
        }
    }

    /**
     * 解析写入策略。
     *
     * <p>写入策略影响幂等、重试、回放和审批。离线计划一般只接收 OFFLINE 通道模式，
     * 但这里仍使用模式感知解析，保证如果未来有实时/离线混合预览入口复用本组件，
     * “实时空写入策略默认 UPDATE/merge”的产品语义不会被旧的 INSERT/APPEND 默认值覆盖。</p>
     */
    private SyncWriteStrategy resolveWriteStrategy(SyncTemplate template,
                                                   List<String> issueCodes,
                                                   List<String> recommendedActions) {
        try {
            return SyncWriteStrategy.fromValueForMode(template.getWriteStrategy(), template.getSyncMode());
        } catch (IllegalArgumentException exception) {
            issueCodes.add("WRITE_STRATEGY_UNSUPPORTED");
            recommendedActions.add("将 writeStrategy 调整为 INSERT 或 UPDATE；实时 CDC 模式可省略 writeStrategy，由后端默认 UPDATE/merge");
            return null;
        }
    }

    /**
     * 执行连接器能力矩阵检查。
     *
     * <p>离线计划允许“当前最小 bridge 不支持但未来专用 runner 可支持”的状态，但不允许连接器组合本身不兼容。
     * 例如 Kafka -> PostgreSQL 的 FULL 全表同步不是合理离线计划，应被标记为阻断。</p>
     */
    private SyncConnectorCompatibilityView resolveCompatibility(SyncTemplate template,
                                                                SyncMode syncMode,
                                                                List<String> issueCodes,
                                                                List<String> recommendedActions,
                                                                List<String> performanceNotes,
                                                                List<String> safetyNotes) {
        if (!hasText(template.getSourceConnectorType())
                || !hasText(template.getTargetConnectorType())
                || syncMode == null) {
            issueCodes.add("CONNECTOR_FACTS_INCOMPLETE");
            recommendedActions.add("生成离线作业计划前必须补全源端/目标端 connector type；推荐通过 datasource-management 能力快照补齐");
            return null;
        }
        try {
            SyncConnectorCompatibilityView compatibility = connectorCapabilityRegistry.checkCompatibility(
                    template.getSourceConnectorType(), template.getTargetConnectorType(), syncMode.name());
            if (!compatibility.supported()) {
                issueCodes.add("CONNECTOR_COMPATIBILITY_UNSUPPORTED");
            }
            issueCodes.addAll(compatibility.issueCodes());
            recommendedActions.addAll(compatibility.recommendedActions());
            performanceNotes.addAll(compatibility.performanceNotes());
            safetyNotes.addAll(compatibility.safetyNotes());
            return compatibility;
        } catch (PlatformBusinessException exception) {
            issueCodes.add("CONNECTOR_COMPATIBILITY_UNSUPPORTED");
            recommendedActions.add("连接器类型不在当前能力矩阵内，请先登记连接器能力或调整模板源端/目标端类型");
            return null;
        }
    }

    /**
     * 解析自定义 SQL 的 statementRef 策略。
     *
     * <p>这里故意只读取是否存在 sql/statementRef，不返回 SQL 正文，也不返回 statementRef 的具体值。
     * statementRef 本身可能暴露客户内部 SQL 仓库命名、业务主题或表名，因此外部响应只给布尔事实和策略码。</p>
     */
    private CustomSqlStatementPolicy resolveSqlStatementPolicy(SyncTemplate template,
                                                               SyncTemplateScopeContract scopeContract,
                                                               List<String> issueCodes,
                                                               List<String> recommendedActions,
                                                               List<String> safetyNotes) {
        if (!scopeContract.customSqlScope()) {
            return new CustomSqlStatementPolicy("NOT_APPLICABLE", false, false);
        }
        if (!hasText(template.getCustomSqlConfig())) {
            return new CustomSqlStatementPolicy("STATEMENT_REF_OR_READ_ONLY_SQL_REQUIRED", false, false);
        }
        try {
            JsonNode root = objectMapper.readTree(template.getCustomSqlConfig());
            boolean statementRefDeclared = hasText(jsonText(root, "statementRef"));
            boolean inlineSqlDeclared = hasText(jsonText(root, "sql"));
            if (statementRefDeclared) {
                return new CustomSqlStatementPolicy("STATEMENT_REF_DECLARED_LOW_SENSITIVE", true, inlineSqlDeclared);
            }
            if (inlineSqlDeclared) {
                recommendedActions.add("自定义 SQL 当前以内联只读 SQL 建模，生产执行建议迁移为 statementRef，由受控 SQL 仓库托管正文");
                safetyNotes.add("普通响应不返回 SQL 正文；inline SQL 即使通过只读校验，也应在审批和审计中按高风险处理");
                return new CustomSqlStatementPolicy("INLINE_SQL_DECLARED_RECOMMEND_STATEMENT_REF", false, true);
            }
            return new CustomSqlStatementPolicy("STATEMENT_REF_OR_READ_ONLY_SQL_REQUIRED", false, false);
        } catch (Exception exception) {
            issueCodes.add("CUSTOM_SQL_JSON_INVALID");
            recommendedActions.add("修正 customSqlConfig 为合法 JSON；离线计划不会回显配置正文");
            return new CustomSqlStatementPolicy("CUSTOM_SQL_CONFIG_INVALID", false, false);
        }
    }

    /**
     * 写入策略补充检查。
     */
    private void evaluateWriteStrategy(SyncTemplate template,
                                       SyncWriteStrategy writeStrategy,
                                       List<String> issueCodes,
                                       List<String> recommendedActions,
                                       List<String> safetyNotes) {
        if (writeStrategy == null) {
            return;
        }
        if (writeStrategy.requiresConflictKey() && !hasText(template.getPrimaryKeyField())) {
            issueCodes.add("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
            recommendedActions.add(writeStrategy.name() + " 离线写入必须声明 primaryKeyField，确保重试、回放和补数具备幂等基础");
        }
        if (writeStrategy.isDestructiveRewrite()) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_APPROVAL");
            recommendedActions.add("OVERWRITE 离线作业必须进入审批，并在执行前确认影响范围、备份和回滚预案");
            safetyNotes.add("覆盖式写入可能清空或替换目标范围，不能由 Agent 静默执行");
        }
    }

    /**
     * 评估离线执行边界。
     *
     * <p>failClosedReasons 不是“模板一定错误”，而是“为什么当前入口不能继续执行”的原因。
     * 例如 OBJECT_LIST 是合法产品场景，但当前最小 run-once bridge 不支持，所以要标记专用 runner 必需。</p>
     */
    private void evaluateOfflineBoundary(boolean offlineChannel,
                                         SyncMode syncMode,
                                         SyncTemplateScopeContract scopeContract,
                                         SyncFieldMappingExecutionContract fieldMappingContract,
                                         boolean checkpointRequired,
                                         boolean connectorCompatibilitySupported,
                                         List<String> failClosedReasons,
                                         List<String> recommendedActions,
                                         List<String> performanceNotes,
                                         List<String> safetyNotes) {
        if (!offlineChannel) {
            failClosedReasons.add("REALTIME_CHANNEL_NOT_ACCEPTED_BY_OFFLINE_JOB_PLAN");
            recommendedActions.add("CDC_STREAMING 应进入 Debezium/Kafka Connect-style 实时通道，而不是 DataX-style 离线作业计划");
            return;
        }
        if (!connectorCompatibilitySupported) {
            failClosedReasons.add("CONNECTOR_CAPABILITY_NOT_READY_FOR_OFFLINE_PLAN");
        }
        if (!scopeContract.executableByMinimalBridge()) {
            failClosedReasons.add("DEDICATED_OFFLINE_RUNNER_REQUIRED_FOR_SCOPE");
            recommendedActions.add("多对象、整 schema、整库计划需要先做对象级 fan-out 或元数据发现，再把子对象交给最小 run-once bridge；不能把父级范围直接交给单对象 runner");
        }
        if (!modeExecutableByMinimalBridge(syncMode)) {
            failClosedReasons.add("DEDICATED_OFFLINE_RUNNER_REQUIRED_FOR_MODE");
            recommendedActions.add("增量、回放、补数、导入导出仍需要专用离线 runner 的水位、分片、审批和报告能力；当前最小 bridge 只覆盖用户主模式中的 FULL、SCHEDULED_FULL、SCHEDULED_BATCH、CUSTOM_SQL_QUERY，以及历史兼容 ONE_TIME_MIGRATION");
        }
        if (checkpointRequired) {
            failClosedReasons.add("CHECKPOINT_HANDOFF_REQUIRED_FOR_OFFLINE_RUNNER");
            recommendedActions.add("需要在专用 runner 中实现 checkpoint 持久化、恢复单元和幂等写入，不应由当前最小 bridge 伪造恢复能力");
        }
        if (!fieldMappingContract.directlyRunnableByMinimalBridge()) {
            failClosedReasons.add("FIELD_MAPPING_REQUIRES_OFFLINE_TRANSFORM_CONTRACT");
            recommendedActions.add("字段改名、表达式、类型转换或缺失字段映射需要离线 transform 合同，不能直接交给最小 bridge");
        }
        performanceNotes.add("离线 runner 后续应补齐 reader/writer 并发、fetchSize、batchSize、限流、失败样本、运行报告和资源配额");
        safetyNotes.add("离线作业计划只描述低敏控制面，不包含凭据、SQL 正文、字段映射原文、过滤条件或样本数据");
    }

    private String resolvePlanStatus(boolean offlineChannel,
                                     boolean hardBlocked,
                                     boolean approvalRequired,
                                     boolean dedicatedOfflineRunnerRequired) {
        if (!offlineChannel) {
            return NOT_OFFLINE_CHANNEL;
        }
        if (hardBlocked) {
            return BLOCKED;
        }
        if (approvalRequired) {
            return PLAN_READY_REQUIRES_APPROVAL;
        }
        if (dedicatedOfflineRunnerRequired) {
            return PLAN_READY_DEDICATED_RUNNER_REQUIRED;
        }
        return PLAN_READY;
    }

    private String resolveRunnerBoundary(boolean offlineChannel,
                                         boolean executableByMinimalBridge,
                                         boolean dedicatedOfflineRunnerRequired) {
        if (!offlineChannel) {
            return "NOT_OFFLINE_USE_REALTIME_CDC_PIPELINE";
        }
        if (executableByMinimalBridge) {
            return "MINIMAL_RUN_ONCE_BRIDGE_CAN_EXECUTE_AFTER_PRECHECK";
        }
        if (dedicatedOfflineRunnerRequired) {
            return "DEDICATED_DATAX_STYLE_OFFLINE_RUNNER_REQUIRED";
        }
        return "OFFLINE_PLAN_BLOCKED_BEFORE_RUNNER_SELECTION";
    }

    private boolean modeExecutableByMinimalBridge(SyncMode syncMode) {
        /*
         * 这里定义“规划层认为当前 v1 可以进入最小 bridge 的模式边界”。
         *
         * FULL / SCHEDULED_FULL / ONE_TIME_MIGRATION：单次 execution 天然是有界读写；
         * SCHEDULED_FULL：调度频率由 task.scheduleConfig 控制，单次触发仍然复用 FULL_OBJECT_SCAN；
         * SCHEDULED_BATCH：调度频率由 task.scheduleConfig 控制，单次触发仍是一段有界批处理窗口，因此不再要求
         * 专用 runner 才能执行；
         * CUSTOM_SQL_QUERY：只允许只读 SQL/statementRef，结果集会被 datasource-management 包装为受控 Reader，
         * 但仍然需要审批上下文放行，避免用户直接绕过对象/字段权限。
         */
        return syncMode == SyncMode.FULL
                || syncMode == SyncMode.SCHEDULED_FULL
                || syncMode == SyncMode.ONE_TIME_MIGRATION
                || syncMode == SyncMode.SCHEDULED_BATCH
                || syncMode == SyncMode.CUSTOM_SQL_QUERY;
    }

    private boolean hasHardBlockingIssue(List<String> issueCodes, boolean offlineChannel) {
        if (!offlineChannel) {
            return false;
        }
        return issueCodes.stream().anyMatch(issueCode ->
                "SYNC_MODE_MISSING".equals(issueCode)
                        || "SYNC_MODE_UNSUPPORTED".equals(issueCode)
                        || "WRITE_STRATEGY_UNSUPPORTED".equals(issueCode)
                        || "CONNECTOR_FACTS_INCOMPLETE".equals(issueCode)
                        || "CONNECTOR_COMPATIBILITY_UNSUPPORTED".equals(issueCode)
                        || "SOURCE_MODE_UNSUPPORTED".equals(issueCode)
                        || "TARGET_MODE_UNSUPPORTED".equals(issueCode)
                        || "SOURCE_CONNECTOR_NOT_READABLE".equals(issueCode)
                        || "TARGET_CONNECTOR_NOT_WRITABLE".equals(issueCode)
                        || "SYNC_SCOPE_TYPE_UNSUPPORTED".equals(issueCode)
                        || "SYNC_SCOPE_MODE_MISMATCH".equals(issueCode)
                        || "SINGLE_OBJECT_SOURCE_NOT_DECLARED".equals(issueCode)
                        || "SINGLE_OBJECT_TARGET_NOT_DECLARED".equals(issueCode)
                        || "OBJECT_MAPPING_CONFIG_REQUIRED".equals(issueCode)
                        || "OBJECT_MAPPING_JSON_INVALID".equals(issueCode)
                        || "OBJECT_MAPPING_EMPTY".equals(issueCode)
                        || "OBJECT_MAPPING_TOO_LARGE".equals(issueCode)
                        || "OBJECT_MAPPING_IDENTIFIER_UNSAFE".equals(issueCode)
                        || "SCHEMA_FULL_REQUIRES_SCHEMA_PAIR".equals(issueCode)
                        || "DATABASE_FULL_REQUIRES_DISCOVERY_POLICY".equals(issueCode)
                        || "CUSTOM_SQL_CONFIG_REQUIRED".equals(issueCode)
                        || "CUSTOM_SQL_JSON_INVALID".equals(issueCode)
                        || "CUSTOM_SQL_QUERY_MISSING".equals(issueCode)
                        || "CUSTOM_SQL_RAW_SQL_UNSAFE".equals(issueCode)
                        || "CUSTOM_SQL_TARGET_OBJECT_REQUIRED".equals(issueCode)
                        || "CUSTOM_SQL_FIELD_MAPPING_REQUIRED".equals(issueCode)
                        || "PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE".equals(issueCode)
                        || "FIELD_MAPPING_PARSE_FAILED".equals(issueCode)
                        || "FIELD_MAPPING_SCHEMA_UNSUPPORTED".equals(issueCode)
                        || "FIELD_MAPPING_PAIR_INCOMPLETE".equals(issueCode)
                        || "FIELD_MAPPING_IDENTIFIER_UNSAFE".equals(issueCode)
                        || "SELECTED_COLUMNS_NOT_RESOLVED".equals(issueCode)
                        || "WRITE_COLUMNS_NOT_RESOLVED".equals(issueCode)
                        || "PRIMARY_KEY_IDENTIFIER_UNSAFE".equals(issueCode)
                        || "PRIMARY_KEY_NOT_PRESENT_IN_FIELD_MAPPING".equals(issueCode));
    }

    private boolean hasWriteStrategyBlockingIssue(List<String> issueCodes) {
        return issueCodes.contains("WRITE_STRATEGY_UNSUPPORTED")
                || issueCodes.contains("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
    }

    private String jsonText(JsonNode node, String fieldName) {
        if (node == null || node.get(fieldName) == null || node.get(fieldName).isNull()) {
            return null;
        }
        String text = node.get(fieldName).asText();
        return hasText(text) ? text.trim() : null;
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 自定义 SQL statementRef 低敏解析结果。
     *
     * @param policy 策略码。
     * @param statementRefDeclared 是否声明 statementRef。
     * @param inlineSqlDeclared 是否声明 inline SQL 正文。
     */
    private record CustomSqlStatementPolicy(String policy,
                                            boolean statementRefDeclared,
                                            boolean inlineSqlDeclared) {
    }
}
