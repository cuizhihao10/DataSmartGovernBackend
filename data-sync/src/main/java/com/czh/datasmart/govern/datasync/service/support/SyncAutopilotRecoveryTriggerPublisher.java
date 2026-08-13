/**
 * @Author : Cui
 * @Date: 2026/08/11 18:50
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把 data-sync 的 execution failed 事实转换成 durable Autopilot 恢复触发事件。
 *
 * <p>本类不调用模型、不选择修复动作，也不直接重跑任务。它只做三件事：</p>
 * <p>1. 从任务定义读取首次确认后持久化的授权快照，并验证租户/项目/有效期；</p>
 * <p>2. 计算错误指纹、轮次和重复次数，关闭上一轮已失败的恢复案例；</p>
 * <p>3. 把低敏触发合同交给 outbox，由 Kafka 异步唤醒 Agent Runtime。</p>
 *
 * <p>这种拆分保证“任务失败”不会因为 Python 或 Kafka 暂时不可用而丢失，同时也避免
 * data-sync 越权替模型决定检索策略或修复方案。</p>
 */
@Slf4j
@Component
public class SyncAutopilotRecoveryTriggerPublisher {

    private static final String SCHEMA_VERSION = "datasmart.autopilot.recovery-trigger.v1";
    private static final int MAX_ISSUE_CODES = 20;

    private final SyncTaskDefinitionMapper definitionMapper;
    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncAutopilotRecoveryCaseService caseService;
    private final SyncAutopilotRecoveryTriggerOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final SyncAutopilotRecoveryMetrics metrics;

    /**
     * Spring 生产构造器，把持久化依赖和低基数观测组件一次性注入。
     *
     * @param definitionMapper 读取任务定义及首次确认后的 Autopilot 授权快照
     * @param caseMapper 查找当前 execution 对应的 active recovery case
     * @param caseService 通过 receipt 和乐观锁推进 recovery case
     * @param outboxService 原子写入并尝试投递 Kafka trigger
     * @param objectMapper 解析并规范化持久授权快照
     * @param metrics 记录 trigger 与最终恢复结果，不承载业务 ID
     */
    @Autowired
    public SyncAutopilotRecoveryTriggerPublisher(
            SyncTaskDefinitionMapper definitionMapper,
            SyncAutopilotRecoveryCaseMapper caseMapper,
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerOutboxService outboxService,
            ObjectMapper objectMapper,
            SyncAutopilotRecoveryMetrics metrics) {
        this.definitionMapper = definitionMapper;
        this.caseMapper = caseMapper;
        this.caseService = caseService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 单元测试兼容构造器，使既有业务测试可以只关注持久化与策略，不必创建 MeterRegistry。
     *
     * <p>生产 Spring 容器始终选择上面的六参数构造器；这里的 {@code metrics=null} 只关闭测试指标，
     * 不改变 trigger、case、receipt 或 outbox 行为。</p>
     */
    SyncAutopilotRecoveryTriggerPublisher(
            SyncTaskDefinitionMapper definitionMapper,
            SyncAutopilotRecoveryCaseMapper caseMapper,
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerOutboxService outboxService,
            ObjectMapper objectMapper) {
        this(definitionMapper, caseMapper, caseService, outboxService, objectMapper, null);
    }

    /**
     * Converts one persisted execution-failure fact into a governed, durable recovery trigger when authorized.
     *
     * <p>The task and execution are authoritative data-sync entities; {@code errorCode} and {@code issueCodes}
     * are sanitized into a stable fingerprint rather than transported as raw error text. In its independent
     * transaction, the method reloads and whitelists the task's authorization snapshot, proves task/definition/
     * execution tenant-project scope, calculates the bounded recovery cycle, and records a failed receipt for a
     * prior active recovery case when appropriate. It then writes a low-sensitive event through the durable
     * outbox; it does not call a model, retry a worker, or publish an arbitrary message directly.</p>
     *
     * <p>Missing, expired, malformed, inactive, or out-of-scope authorization fails closed by returning without
     * changing the already persisted sync failure. The derived event ID makes the same failure round idempotent
     * at the outbox boundary. A prior recovery failure advances only through its own receipt, and loop guards
     * stop exhausted cycles, repeated errors, and deadlines before another event is created. This preserves the
     * security boundary that Agent Runtime must reauthorize before proposing any repair.</p>
     *
     * @param task persisted task that owns the failed execution
     * @param execution persisted failed execution belonging to {@code task}
     * @param errorCode primary low-sensitive failure code; arbitrary prose is sanitized before use
     * @param issueCodes optional secondary codes used only for a bounded fingerprint/diagnostic list
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishFailed(SyncTask task,
                              SyncExecution execution,
                              String errorCode,
                              List<String> issueCodes) {
        if (!validScopeInputs(task, execution)) {
            recordTriggerRejected();
            return;
        }
        SyncTaskDefinition definition = definitionMapper.selectById(task.getId());
        if (definition == null || definition.getAutopilotPolicy() == null
                || definition.getAutopilotPolicy().isBlank()) {
            recordTriggerRejected();
            return;
        }

        ParsedAuthorization authorization;
        try {
            authorization = parseAuthorization(definition.getAutopilotPolicy());
        } catch (RuntimeException exception) {
            /*
             * 授权快照损坏属于配置事实不可信：安全做法是跳过自动恢复，同时不记录原始 JSON、
             * 异常正文或其他可能含敏感信息的字段。
             */
            log.warn("Autopilot recovery trigger was skipped, taskId={}, executionId={}, exceptionType={}",
                    task.getId(), execution.getId(), exception.getClass().getSimpleName());
            recordTriggerRejected();
            return;
        }
        if (!authorization.active() || !scopeMatches(task, execution, definition, authorization)) {
            recordTriggerRejected();
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!isAfterInstant(authorization.expiresAt(), now)) {
            recordTriggerRejected();
            return;
        }

        String fingerprint = errorFingerprint(errorCode, issueCodes);
        SyncAutopilotRecoveryCase active = caseMapper.selectRecoveringByCurrentExecution(
                task.getTenantId(), task.getId(), execution.getId());
        Long rootExecutionId = active == null ? execution.getId() : active.getRootExecutionId();
        int cycle = active == null ? 1 : Math.max(1, active.getCycle()) + 1;
        int repeatedErrorCount = active == null ? 0
                : Objects.equals(active.getLastErrorFingerprint(), fingerprint)
                ? Math.max(0, active.getRepeatedErrorCount()) + 1
                : 0;
        String previousRepairFingerprint = active == null ? null : active.getRepairFingerprint();
        OffsetDateTime deadlineAt = active != null && active.getDeadlineAt() != null
                ? OffsetDateTime.of(active.getDeadlineAt(), ZoneOffset.UTC)
                : earliest(now.plusMinutes(authorization.maxTotalDurationMinutes()),
                authorization.expiresAt());

        if (active != null) {
            recordFailedRecoveryAttempt(active, execution, cycle, fingerprint, repeatedErrorCount);
        }
        if (cycle > authorization.maxRecoveryCycles()
                || repeatedErrorCount >= authorization.maxRepeatedErrorCount()
                || !isAfterInstant(deadlineAt, now)) {
            log.info("Autopilot recovery trigger stopped by bounded loop guard, taskId={}, executionId={}, cycle={}",
                    task.getId(), execution.getId(), cycle);
            recordTriggerRejected();
            return;
        }

        SyncAutopilotRecoveryTriggerEvent event = new SyncAutopilotRecoveryTriggerEvent(
                SCHEMA_VERSION,
                eventId(authorization, task.getId(), rootExecutionId, execution.getId(), cycle, fingerprint),
                authorization.rootSessionId(),
                authorization.rootRunId(),
                authorization.tenantId(),
                authorization.applicationId(),
                authorization.projectId(),
                authorization.userId(),
                authorization.actorId(),
                authorization.agentId(),
                authorization.delegationId(),
                task.getId(),
                rootExecutionId,
                execution.getId(),
                cycle,
                authorization.maxRecoveryCycles(),
                deadlineAt.toString(),
                fingerprint,
                repeatedErrorCount,
                previousRepairFingerprint,
                safeIssueCodes(errorCode, issueCodes),
                authorization.snapshot(),
                authorization.snapshotDigest(),
                now.toString()
        );
        outboxService.enqueueAndDispatch(event);
        if (metrics != null) {
            metrics.recordTriggerAccepted();
        }
    }

    /**
     * Closes the active recovery case when the Autopilot-requeued execution has succeeded.
     *
     * <p>The method accepts authoritative task/execution entities and first proves they are in the same scope.
     * It finds only a {@code RECOVERY_STARTED} case for that tenant/task/current execution and records a
     * {@code RECOVERY_SUCCEEDED} receipt through the case service. The independent transaction keeps a failed
     * control-plane write from rolling back the worker's successful business transaction or task-management
     * receipt.</p>
     *
     * <p>The fixed receipt ID uses case and execution IDs, so repeated success callbacks replay safely rather
     * than incrementing the optimistic version twice. If no active case exists, the method is intentionally a
     * no-op. It never marks an arbitrary case recovered and never exposes a bypass around case-service state
     * and scope validation.</p>
     *
     * @param task persisted task that owns the completed execution
     * @param execution execution whose success may complete an active recovery case
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSucceeded(SyncTask task, SyncExecution execution) {
        if (!validScopeInputs(task, execution)) {
            return;
        }
        SyncAutopilotRecoveryCase active = caseMapper.selectRecoveringByCurrentExecution(
                task.getTenantId(), task.getId(), execution.getId());
        if (active == null) {
            return;
        }
        caseService.recordTransition(new SyncAutopilotRecoveryTransitionCommand(
                active.getCaseId(),
                active.getVersion(),
                "autopilot-recovery-succeeded:" + active.getCaseId() + ":" + execution.getId(),
                SyncAutopilotRecoveryReceiptType.RECOVERY_SUCCEEDED,
                execution.getId(),
                active.getCycle(),
                active.getLastErrorFingerprint(),
                active.getRepeatedErrorCount(),
                null
        ));
        if (metrics != null) {
            metrics.recordRecoverySucceeded();
        }
    }

    /**
     * Records that a previously started recovery attempt failed and therefore requires replanning attention.
     *
     * <p>The active case and failed execution identify a stable {@code RECOVERY_FAILED} receipt. The delegated
     * case service applies the only legal state transition with optimistic locking, updates the latest error
     * facts, and persists the attention reason. This method itself performs no direct SQL or worker retry; its
     * side effect is the receipt-backed case transition inside the surrounding independent transaction.</p>
     *
     * <p>Its receipt ID is deterministic for case/execution, making duplicate failure callbacks replay-safe.
     * Once the old case reaches {@code ATTENTION_REQUIRED}, a later model proposal needs a distinct repair
     * fingerprint to create a new case, so it cannot loop by reusing the failed repair plan.</p>
     *
     * @param active active recovery case associated with the previous Autopilot attempt
     * @param execution execution that failed during that attempt
     * @param cycle next bounded recovery cycle to retain on the case
     * @param errorFingerprint safe fingerprint of the failure facts
     * @param repeatedErrorCount bounded count of repeated equivalent failures
     */
    private void recordFailedRecoveryAttempt(SyncAutopilotRecoveryCase active,
                                             SyncExecution execution,
                                             int cycle,
                                             String errorFingerprint,
                                             int repeatedErrorCount) {
        caseService.recordTransition(new SyncAutopilotRecoveryTransitionCommand(
                active.getCaseId(),
                active.getVersion(),
                "autopilot-recovery-failed:" + active.getCaseId() + ":" + execution.getId(),
                SyncAutopilotRecoveryReceiptType.RECOVERY_FAILED,
                execution.getId(),
                cycle,
                errorFingerprint,
                repeatedErrorCount,
                "RECOVERY_FAILED_REPLANNING"
        ));
        if (metrics != null) {
            metrics.recordRecoveryFailed();
        }
    }

    /**
     * 记录一次未产生 trigger 的安全拒绝，并兼容不启用指标的隔离单元测试。
     *
     * <p>拒绝原因保留在代码分支、日志或持久业务状态中，不作为 Prometheus 标签，避免 errorCode、对象 ID
     * 或授权字段形成高基数序列。该方法没有业务副作用。</p>
     */
    private void recordTriggerRejected() {
        if (metrics != null) {
            metrics.recordTriggerRejected();
        }
    }

    /**
     * Parses the persisted authorization snapshot into a whitelisted, transport-safe representation.
     *
     * <p>Only explicit IDs, bounded budgets, action codes, timestamps, and lifecycle metadata are copied to a
     * {@link LinkedHashMap}; unrecognized JSON fields such as passwords, SQL, URLs, checkpoints, model fields,
     * or prompts are never retained in the result. The ordered whitelisted map is serialized and SHA-256 bound
     * so Agent Runtime can detect a changed transport contract without receiving the original policy body.</p>
     *
     * <p>This parser has no database, Kafka, or worker side effect and is deterministic for equivalent JSON.
     * It fails closed for malformed input, allowing {@link #publishFailed(SyncTask, SyncExecution, String, List)}
     * to skip automation safely. The returned value is not execution authority: callers still compare its scope,
     * active state, and expiry with local persistence before writing an outbox event.</p>
     *
     * @param policyJson persisted task definition authorization JSON
     * @return sanitized authorization facts plus a stable digest of the whitelisted snapshot
     * @throws IllegalArgumentException when the snapshot is malformed or cannot provide required safe fields
     */
    @SuppressWarnings("unchecked")
    private ParsedAuthorization parseAuthorization(String policyJson) {
        try {
            JsonNode root = objectMapper.readTree(policyJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Autopilot policy must be a JSON object");
            }
            String state = optionalText(root, "state", "ACTIVE").toUpperCase(Locale.ROOT);
            String policyId = requiredText(root, "policyId", "authorizationId");
            String policyVersion = optionalText(
                    root, "policyVersion", "datasmart.autopilot.authorization.v1");
            String rootSessionId = requiredText(root, "rootSessionId");
            String rootRunId = requiredText(root, "rootRunId");
            Long tenantId = requiredLong(root, "tenantId");
            Long applicationId = nullableLong(root, "applicationId");
            Long projectId = nullableLong(root, "projectId");
            String userId = requiredText(root, "userId");
            String actorId = requiredText(root, "actorId");
            String agentId = requiredText(root, "agentId");
            String delegationId = requiredText(root, "delegationId");
            int maxCycles = boundedInt(root, "maxRecoveryCycles", 5, 1, 10);
            int durationMinutes = boundedInt(root, "maxTotalDurationMinutes", 120, 5, 1440);
            int maxRepeatedErrors = boundedInt(root, "maxRepeatedErrorCount", 3, 1, 10);
            String maxRisk = optionalText(root, "maxAutomaticRiskLevel", "LOW")
                    .toUpperCase(Locale.ROOT);
            List<String> allowedActions = safeCodes(root.path("allowedRecoveryActions"));
            List<String> approvalActions = safeCodes(root.path("requireApprovalFor"));
            OffsetDateTime issuedAt = parseDateTime(optionalText(
                    root, "issuedAt", OffsetDateTime.now(ZoneOffset.UTC).toString()));
            OffsetDateTime expiresAt = parseDateTime(requiredText(root, "expiresAt"));
            String policyDigest = optionalText(root, "policyDigest", null);

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("policyId", policyId);
            snapshot.put("policyVersion", policyVersion);
            snapshot.put("executionMode", "AUTOPILOT");
            snapshot.put("state", state);
            snapshot.put("rootSessionId", rootSessionId);
            snapshot.put("rootRunId", rootRunId);
            snapshot.put("tenantId", tenantId);
            snapshot.put("applicationId", applicationId);
            snapshot.put("projectId", projectId);
            snapshot.put("userId", userId);
            snapshot.put("actorId", actorId);
            snapshot.put("agentId", agentId);
            snapshot.put("delegationId", delegationId);
            snapshot.put("maxRecoveryCycles", maxCycles);
            snapshot.put("maxTotalDurationMinutes", durationMinutes);
            snapshot.put("maxRepeatedErrorCount", maxRepeatedErrors);
            snapshot.put("maxAutomaticRiskLevel", maxRisk);
            snapshot.put("allowedRecoveryActions", allowedActions);
            snapshot.put("requireApprovalFor", approvalActions);
            snapshot.put("issuedAt", issuedAt.toString());
            snapshot.put("expiresAt", expiresAt.toString());
            if (policyDigest != null) {
                snapshot.put("policyDigest", policyDigest);
            }
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            return new ParsedAuthorization(
                    "ACTIVE".equals(state),
                    rootSessionId,
                    rootRunId,
                    tenantId,
                    applicationId,
                    projectId,
                    userId,
                    actorId,
                    agentId,
                    delegationId,
                    maxCycles,
                    durationMinutes,
                    maxRepeatedErrors,
                    expiresAt,
                    snapshot,
                    "sha256:" + SyncAutopilotDigestSupport.sha256(snapshotJson)
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot parse persisted Autopilot authorization", exception);
        }
    }

    /**
     * Verifies that task, definition, execution, and parsed authorization share one tenant/project boundary.
     *
     * <p>This pure, idempotent predicate is evaluated before any recovery-trigger outbox write. It does not try
     * to repair a mismatch or fall back to an omitted value: a false result causes the publisher to fail closed,
     * preventing a persisted authorization from one scope from waking recovery work for another scope.</p>
     *
     * @param task authoritative sync task
     * @param execution authoritative failed execution
     * @param definition authoritative task definition holding the policy snapshot
     * @param authorization sanitized authorization facts parsed from that snapshot
     * @return {@code true} only when all durable scope values match exactly
     */
    private boolean scopeMatches(SyncTask task,
                                 SyncExecution execution,
                                 SyncTaskDefinition definition,
                                 ParsedAuthorization authorization) {
        return Objects.equals(task.getTenantId(), authorization.tenantId())
                && Objects.equals(task.getProjectId(), authorization.projectId())
                && Objects.equals(execution.getTenantId(), authorization.tenantId())
                && Objects.equals(execution.getProjectId(), authorization.projectId())
                && Objects.equals(definition.getTenantId(), authorization.tenantId())
                && Objects.equals(definition.getProjectId(), authorization.projectId());
    }

    /**
     * Performs an early structural ownership check on the entities passed to the publisher.
     *
     * <p>The method proves the task/execution IDs exist, the execution belongs to the task, and tenant/project
     * values agree before the publisher reads a policy or computes an event ID. It is pure and idempotent. A
     * false result is a silent fail-closed outcome because publishing a trigger must never change business state
     * merely to report a caller wiring error or bridge an unexpected tenant boundary.</p>
     *
     * @param task candidate owning task
     * @param execution candidate failed/completed execution
     * @return {@code true} only for a complete same-task, same-tenant, same-project pair
     */
    private boolean validScopeInputs(SyncTask task, SyncExecution execution) {
        return task != null && task.getId() != null && task.getTenantId() != null
                && execution != null && execution.getId() != null
                && Objects.equals(task.getId(), execution.getSyncTaskId())
                && Objects.equals(task.getTenantId(), execution.getTenantId())
                && Objects.equals(task.getProjectId(), execution.getProjectId());
    }

    /**
     * Computes a stable, low-sensitive SHA-256 fingerprint for a primary error code and optional issue codes.
     *
     * <p>Each input is normalized by {@link #safeCode(String)}, and secondary codes are sorted before hashing,
     * so transport order and unsafe punctuation cannot change the identity of an equivalent failure. The method
     * is pure and idempotent, has no persistence or logging side effect, and never hashes raw exception bodies,
     * SQL, URLs, or logs. The fingerprint supports loop guards and case identity; it is not a diagnostic payload
     * or evidence that can independently authorize a recovery.</p>
     *
     * @param errorCode primary error code, normalized to {@code UNKNOWN} when absent
     * @param issueCodes optional secondary codes; null is treated as an empty list
     * @return lowercase SHA-256 digest of the canonical safe code list
     */
    public static String errorFingerprint(String errorCode, List<String> issueCodes) {
        List<String> normalized = new ArrayList<>();
        normalized.add(safeCode(errorCode));
        if (issueCodes != null) {
            issueCodes.stream().map(SyncAutopilotRecoveryTriggerPublisher::safeCode)
                    .filter(value -> !value.isBlank())
                    .sorted()
                    .forEach(normalized::add);
        }
        return SyncAutopilotDigestSupport.sha256(String.join("|", normalized));
    }

    /**
     * 为一次已授权的失败恢复循环生成确定性的 outbox 事件身份。
     *
     * <p>事件 ID 同时绑定授权会话与运行链路、任务与执行链路、当前循环以及低敏错误指纹。该计算没有
     * 副作用且可重复：同一失败回调会得到相同事件 ID，并由数据库唯一键完成去重；循环、当前执行或错误
     * 指纹发生变化时会得到新事件 ID，但新事件仍必须通过最大循环次数、重复错误次数和截止时间门禁。</p>
     *
     * @param authorization 已清洗且仍然有效的首次授权事实
     * @param taskId 所属数据同步任务 ID
     * @param rootExecutionId 自治恢复链路中的首次执行 ID
     * @param currentExecutionId 本轮刚刚失败的执行 ID
     * @param cycle 受最大循环次数约束的恢复轮次
     * @param errorFingerprint 用于关联同类故障的低敏摘要
     * @return 带固定前缀、可用于 outbox 去重的确定性事件 ID
     */
    private String eventId(ParsedAuthorization authorization,
                           Long taskId,
                           Long rootExecutionId,
                           Long currentExecutionId,
                           int cycle,
                           String errorFingerprint) {
        return "autopilot-trigger:" + SyncAutopilotDigestSupport.sha256(String.join("|",
                authorization.rootSessionId(),
                authorization.rootRunId(),
                String.valueOf(taskId),
                String.valueOf(rootExecutionId),
                String.valueOf(currentExecutionId),
                String.valueOf(cycle),
                errorFingerprint));
    }

    /**
     * Builds the bounded, sanitized issue-code list included in the trigger contract.
     *
     * <p>The primary code is always first, while secondary values are normalized, deduplicated, and capped so
     * an error report cannot grow an outbox payload without bound. The result is immutable and has no side
     * effect. It is deterministic for a fixed input order after normalization, but it is deliberately not a raw
     * error export: arbitrary prose is replaced with safe enum-like code text.</p>
     *
     * @param errorCode primary code to retain even when secondary input is absent
     * @param issueCodes optional secondary issue codes
     * @return immutable safe list containing at most {@code MAX_ISSUE_CODES} entries
     */
    private List<String> safeIssueCodes(String errorCode, List<String> issueCodes) {
        List<String> result = new ArrayList<>();
        result.add(safeCode(errorCode));
        if (issueCodes != null) {
            issueCodes.stream().map(SyncAutopilotRecoveryTriggerPublisher::safeCode)
                    .filter(value -> !result.contains(value))
                    .limit(MAX_ISSUE_CODES - 1L)
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * Normalizes arbitrary text into a bounded enum-like diagnostic code.
     *
     * <p>Blank input becomes {@code UNKNOWN}; all other text is uppercased, limited to a small ASCII character
     * whitelist, and truncated. This pure helper is intentionally lossy and idempotent for an already normalized
     * value. It is a data-minimization boundary used before hashing, logging, or sending issue codes, not a way
     * to preserve detailed errors or to validate that a code has business meaning.</p>
     *
     * @param value untrusted diagnostic text
     * @return safe bounded code text
     */
    private static String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_\\-:.]", "_");
        return normalized.substring(0, Math.min(96, normalized.length()));
    }

    /**
     * Reads a JSON action list into an immutable list of sanitized code strings.
     *
     * <p>A missing/non-array field means no actions; a present nontext element is rejected rather than coerced.
     * Each textual value passes through {@link #safeCode(String)}, so the authorization snapshot cannot carry
     * raw arbitrary text into Kafka. The method is pure and side-effect free; it does not decide whether an
     * action is permitted, which remains the policy/case-service responsibility.</p>
     *
     * @param node JSON node expected to contain an action-code array
     * @return immutable sanitized action list, or an empty list when the field is absent/non-array
     * @throws IllegalArgumentException when an array contains a nontext element
     */
    private List<String> safeCodes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Autopilot actions must be text codes");
            }
            result.add(safeCode(item.asText()));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the earlier of two authorization-relevant deadlines.
     *
     * <p>The publisher combines the per-recovery duration budget with the authorization expiry and must never
     * use the later value, which could keep automation alive after consent expires. This pure, idempotent helper
     * has no state side effect; its result is carried in the trigger so later components can stop an overdue
     * cycle rather than infer a new deadline.</p>
     *
     * @param first first nonnull deadline
     * @param second second nonnull deadline
     * @return whichever deadline occurs first
     */
    private OffsetDateTime earliest(OffsetDateTime first, OffsetDateTime second) {
        return isAfterInstant(first, second)
                ? second.withOffsetSameInstant(ZoneOffset.UTC)
                : first.withOffsetSameInstant(ZoneOffset.UTC);
    }

    /**
     * Compares two offset-aware timestamps by their absolute instant rather than their local clock fields.
     *
     * <p>Authorization snapshots may be issued in a customer time zone while data-sync uses UTC for durable
     * deadlines. Comparing local date-time fields would treat two different offsets as if they shared one wall
     * clock and could keep expired automation alive. Converting both values to {@link java.time.Instant} makes
     * expiry and bounded-loop checks independent of the offset supplied by the policy.</p>
     *
     * @param candidate timestamp that must occur after the reference instant
     * @param reference current or competing timestamp
     * @return {@code true} only when {@code candidate} is strictly later on the UTC timeline
     */
    private boolean isAfterInstant(OffsetDateTime candidate, OffsetDateTime reference) {
        return candidate.toInstant().isAfter(reference.toInstant());
    }

    /**
     * Reads a required nonblank textual authorization field, with one documented compatibility alias.
     *
     * <p>This parser accepts only JSON text and trims it after validation; it does not coerce numbers, arrays,
     * or objects into identifiers. The method is pure and idempotent. Required identifiers such as policy/session
     * IDs fail closed when absent so a malformed persisted snapshot cannot silently use a broader default scope
     * or emit a trigger with an ambiguous audit lineage.</p>
     *
     * @param root parsed authorization object
     * @param field preferred schema field name
     * @param alias optional older schema field name
     * @return trimmed text from the preferred field or alias
     * @throws IllegalArgumentException when neither source has nonblank textual content
     */
    private String requiredText(JsonNode root, String field, String alias) {
        JsonNode value = root.get(field);
        if ((value == null || value.isNull()) && alias != null) {
            value = root.get(alias);
        }
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText().trim();
    }

    private String requiredText(JsonNode root, String field) {
        return requiredText(root, field, null);
    }

    /**
     * Reads optional text while returning a caller-chosen safe default for omitted or unusable values.
     *
     * <p>This is used only for fields whose defaults are part of the fixed authorization contract, such as an
     * inactive/active state label or a schema version. It is pure and idempotent, does not mutate the parsed
     * tree, and never manufactures required identity/scope values. Callers must not use it for a security-bound
     * identifier where absence should instead fail closed through {@link #requiredText(JsonNode, String)}.</p>
     *
     * @param root parsed authorization object
     * @param field optional schema field name
     * @param fallback fixed contract default when the field is absent, nontext, or blank
     * @return trimmed field text or {@code fallback}
     */
    private String optionalText(JsonNode root, String field, String fallback) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText().trim();
    }

    /**
     * Reads a required integral authorization identifier without accepting text or fractional JSON values.
     *
     * <p>The method is pure, idempotent, and side-effect free. It preserves the distinction between an absent
     * field and a present invalid value so a policy cannot accidentally bind recovery work to a fabricated
     * tenant. Positive/range requirements that belong to a specific field are applied by the caller after this
     * structural check.</p>
     *
     * @param root parsed authorization object
     * @param field required integer field name
     * @return parsed long identifier
     * @throws IllegalArgumentException when the field is absent or not an integer
     */
    private Long requiredLong(JsonNode root, String field) {
        Long value = nullableLong(root, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /**
     * Reads an optional integral authorization field without widening invalid input to {@code null}.
     *
     * <p>Only an absent/null JSON field returns {@code null}; a present but nonintegral value is an invalid
     * persisted authorization. The method is pure and idempotent, and lets the caller distinguish a deliberately
     * optional project/application field from malformed scope data before publishing any trigger.</p>
     *
     * @param root parsed authorization object
     * @param field optional integer field name
     * @return parsed long value, or {@code null} only when the field is absent/null
     * @throws IllegalArgumentException when a present field cannot be represented as a long
     */
    private Long nullableLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    /**
     * Reads a bounded numeric recovery limit and applies a safe default only when omitted.
     *
     * <p>Present values must be integral and fall within the caller's inclusive safety range. This prevents a
     * corrupted policy from creating unbounded recovery cycles, duration, or repeated-error tolerance. The
     * helper is pure and idempotent; it calculates no retry and changes no persistent state itself.</p>
     *
     * @param root parsed authorization object
     * @param field numeric field name
     * @param fallback fixed safe default for an absent field
     * @param min smallest accepted explicit value
     * @param max largest accepted explicit value
     * @return fallback or validated integer
     * @throws IllegalArgumentException when a present value is invalid or outside the range
     */
    private int boundedInt(JsonNode root, String field, int fallback, int min, int max) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int parsed = value.intValue();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
        return parsed;
    }

    /**
     * Parses an offset-aware authorization timestamp used for issued/expiry comparisons.
     *
     * <p>The method has no clock, persistence, or transport side effect and is idempotent for the same ISO-8601
     * text. It intentionally allows parsing errors to propagate to the authorization parser, which then skips
     * automation rather than treating an unreadable expiry as valid indefinitely.</p>
     *
     * @param value required ISO-8601 timestamp text including an offset
     * @return parsed timestamp with its original offset information
     * @throws RuntimeException when the input cannot be parsed as an offset date-time
     */
    private OffsetDateTime parseDateTime(String value) {
        return OffsetDateTime.parse(value);
    }

    /** 解析后的白名单授权，不保留原始 JSON。 */
    private record ParsedAuthorization(
            boolean active,
            String rootSessionId,
            String rootRunId,
            Long tenantId,
            Long applicationId,
            Long projectId,
            String userId,
            String actorId,
            String agentId,
            String delegationId,
            int maxRecoveryCycles,
            int maxTotalDurationMinutes,
            int maxRepeatedErrorCount,
            OffsetDateTime expiresAt,
            Map<String, Object> snapshot,
            String snapshotDigest) {
    }
}
