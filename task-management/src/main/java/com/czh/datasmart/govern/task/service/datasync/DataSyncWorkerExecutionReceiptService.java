/**
 * @Author : Cui
 * @Date: 2026/06/22 10:38
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerExecutionReceipt;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerExecutionReceiptMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerExecutionReceiptEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DataSync worker 执行回执服务。
 *
 * <p>该服务补齐 DataSync 主链路中“命令已投递”和“下游真实执行”之间的断点。此前 task-management 只能知道
 * datasource-management 已接收或入队一个 data-sync 命令，却不知道 Runner 后续是否读到数据、是否写入成功、
 * 是否保存检查点、是否执行完成或失败。本服务把这些低敏执行事实写入 task-management 本地投影。</p>
 *
 * <p>为什么不直接更新 task 主状态：</p>
 * <p>1. 当前 Agent 异步工具任务在“下游已接受命令”时可能已经完成，Runner 之后的进度不一定还持有原 task lease；</p>
 * <p>2. 直接把执行回执写进主任务状态机会破坏已有任务生命周期校验，甚至把一个完成的命令投递任务重新改成运行中；</p>
 * <p>3. 独立 execution receipt 投影既能保留端到端历史，又不会污染 task-management 原有调度语义；</p>
 * <p>4. 后续管理台、Agent timeline、告警和审计可以从该投影聚合出用户可见状态。</p>
 *
 * <p>安全与合规边界：</p>
 * <p>本服务只接受低敏执行指标。即使下游传入 errorSummary/warnings，也会做常见密钥、endpoint、Bearer token
 * 和疑似 SQL 片段脱敏；响应视图仍不返回正文，只返回“是否存在”和可见性策略。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerExecutionReceiptService {

    private static final String RECORD_SCHEMA_VERSION =
            "datasmart.task.data-sync-worker-execution-receipt.record.v1";
    private static final String QUERY_SCHEMA_VERSION =
            "datasmart.task.data-sync-worker-execution-receipt.query.v1";
    private static final String DEFAULT_SOURCE_SERVICE = "datasource-management";
    /**
     * 普通 data-sync 任务没有 Agent command/outbox 时使用的低敏虚拟 commandId 前缀。
     *
     * <p>task-management 最早只从 Agent 工具命令角度接收 data-sync 回执，因此 execution receipt 会强制关联
     * task_data_sync_worker_command_outbox。现在数据同步任务也可以由页面直接创建和执行，这类任务没有 Agent command。
     * 如果继续强制查 outbox，就会出现 data-sync 已经成功、但 task-management receipt 一直 Conflict/RETRY_WAIT 的断点。</p>
     *
     * <p>该前缀只用于投影检索和问题诊断，不表示系统真实创建了 Agent command。</p>
     */
    private static final String STANDALONE_COMMAND_ID_PREFIX = "standalone-data-sync-task:";
    /**
     * 普通 data-sync 任务没有 task-management outbox 时使用的低敏虚拟 outboxId 前缀。
     */
    private static final String STANDALONE_OUTBOX_ID_PREFIX = "standalone-data-sync-execution:";
    private static final int DEFAULT_QUERY_LIMIT = 50;
    private static final int MAX_QUERY_LIMIT = 200;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_WARNING_SUMMARY_LENGTH = 1000;

    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|access[_-]?key|api[_-]?key|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern INTERNAL_ENDPOINT_PATTERN = Pattern.compile(
            "(?i)\\b(?:jdbc:|https?://|mysql://|postgresql://|redis://|mongodb://)\\S+"
    );
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern SQL_FRAGMENT_PATTERN = Pattern.compile(
            "(?i)\\b(select|insert|update|delete|merge|drop|alter|create)\\s+[^;]{0,180}"
    );

    private final DataSyncWorkerExecutionReceiptMapper receiptMapper;
    private final DataSyncWorkerCommandOutboxMapper outboxMapper;

    /**
     * 记录 datasource-management Runner 执行回执。
     *
     * <p>业务流程：</p>
     * <p>1. 校验 receiptId、eventType、syncTaskId、syncExecutionId 等基础字段；</p>
     * <p>2. 通过 commandId 或 syncTaskId + syncExecutionId 解析 task-management 本地 outbox；</p>
     * <p>3. 使用 outbox 中的 taskId、租户、项目、Agent run、audit 等上下文补齐 receipt；</p>
     * <p>4. 写入 receipt 表，依赖 receiptId 唯一键实现幂等；</p>
     * <p>5. 返回低敏视图，不暴露 errorSummary/warningSummary 正文。</p>
     *
     * @param request datasource-management Runner 的低敏执行回执。
     * @return 写入结果；重复 receiptId 会返回 duplicate=true。
     */
    @Transactional
    public DataSyncWorkerExecutionReceiptRecordResult recordReceipt(
            DataSyncWorkerExecutionReceiptRecordRequest request) {
        validateRecordRequest(request);
        LocalDateTime now = LocalDateTime.now();
        DataSyncWorkerExecutionReceiptEventType eventType =
                DataSyncWorkerExecutionReceiptEventType.parse(request.getEventType());
        DataSyncWorkerCommandOutbox outbox = resolveOutbox(request);
        DataSyncWorkerExecutionReceipt receipt = buildReceipt(request, eventType, outbox, now);
        List<String> warnings = buildRecordWarnings(request, outbox, eventType);

        try {
            receiptMapper.insert(receipt);
            return new DataSyncWorkerExecutionReceiptRecordResult(
                    RECORD_SCHEMA_VERSION,
                    true,
                    false,
                    now,
                    DataSyncWorkerExecutionReceiptViewAssembler.toView(receipt),
                    warnings
            );
        } catch (DuplicateKeyException exception) {
            DataSyncWorkerExecutionReceipt existing = loadByReceiptId(request.getReceiptId());
            warnings.add("检测到重复 execution receipt，已按 receiptId 幂等复用已有记录");
            return new DataSyncWorkerExecutionReceiptRecordResult(
                    RECORD_SCHEMA_VERSION,
                    true,
                    true,
                    now,
                    DataSyncWorkerExecutionReceiptViewAssembler.toView(existing),
                    warnings
            );
        }
    }

    /**
     * 查询执行回执历史。
     *
     * <p>该查询主要给内部诊断和后续管理台使用。它允许按 commandId、syncTaskId/syncExecutionId、taskId、租户项目和事件类型过滤，
     * 但不会返回错误正文、warning 正文或任何同步数据明细。</p>
     */
    @Transactional(readOnly = true)
    public DataSyncWorkerExecutionReceiptQueryResult queryReceipts(String commandId,
                                                                   Long syncTaskId,
                                                                   Long syncExecutionId,
                                                                   Long taskId,
                                                                   Long tenantId,
                                                                   Long projectId,
                                                                   String eventType,
                                                                   Integer limit) {
        DataSyncWorkerExecutionReceiptEventType parsedEventType = eventType == null || eventType.isBlank()
                ? null
                : DataSyncWorkerExecutionReceiptEventType.parse(eventType);
        int effectiveLimit = clamp(limit, DEFAULT_QUERY_LIMIT, MAX_QUERY_LIMIT);
        LambdaQueryWrapper<DataSyncWorkerExecutionReceipt> baseWrapper = buildQueryWrapper(
                commandId,
                syncTaskId,
                syncExecutionId,
                taskId,
                tenantId,
                projectId,
                parsedEventType,
                null
        );
        long totalCount = receiptMapper.selectCount(baseWrapper);
        Map<String, Long> eventTypeCounts = countByEventType(commandId, syncTaskId, syncExecutionId, taskId, tenantId, projectId);
        List<DataSyncWorkerExecutionReceipt> rows = receiptMapper.selectList(
                buildQueryWrapper(commandId, syncTaskId, syncExecutionId, taskId, tenantId, projectId, parsedEventType, effectiveLimit)
                        .orderByDesc(DataSyncWorkerExecutionReceipt::getEventTime)
                        .orderByDesc(DataSyncWorkerExecutionReceipt::getId)
        );
        List<DataSyncWorkerExecutionReceiptView> records = rows.stream()
                .map(DataSyncWorkerExecutionReceiptViewAssembler::toView)
                .toList();
        List<String> warnings = buildQueryWarnings(totalCount, records.size(), effectiveLimit);
        return new DataSyncWorkerExecutionReceiptQueryResult(
                QUERY_SCHEMA_VERSION,
                totalCount,
                eventTypeCounts,
                records,
                DataSyncWorkerExecutionReceiptViewAssembler.DETAIL_VISIBILITY_POLICY,
                warnings
        );
    }

    private DataSyncWorkerExecutionReceipt buildReceipt(DataSyncWorkerExecutionReceiptRecordRequest request,
                                                        DataSyncWorkerExecutionReceiptEventType eventType,
                                                        DataSyncWorkerCommandOutbox outbox,
                                                        LocalDateTime now) {
        DataSyncWorkerExecutionReceipt receipt = new DataSyncWorkerExecutionReceipt();
        receipt.setReceiptId(request.getReceiptId().trim());
        receipt.setCommandId(outbox.getCommandId());
        receipt.setOutboxId(outbox.getOutboxId());
        receipt.setTaskId(outbox.getTaskId());
        receipt.setAgentRunId(outbox.getAgentRunId());
        receipt.setAgentSessionId(outbox.getAgentSessionId());
        receipt.setAuditId(outbox.getAuditId());
        receipt.setTenantId(outbox.getTenantId());
        receipt.setProjectId(outbox.getProjectId());
        receipt.setWorkspaceId(outbox.getWorkspaceId());
        receipt.setSyncTaskId(request.getSyncTaskId());
        receipt.setSyncExecutionId(request.getSyncExecutionId());
        receipt.setEventType(eventType.name());
        receipt.setEventTime(request.getEventTime() == null ? now : request.getEventTime());
        receipt.setExecutorId(trimToNull(request.getExecutorId()));
        receipt.setSourceService(trimToDefault(request.getSourceService(), DEFAULT_SOURCE_SERVICE));
        receipt.setBatchRecordsRead(nonNegative(request.getBatchRecordsRead()));
        receipt.setBatchRecordsWritten(nonNegative(request.getBatchRecordsWritten()));
        receipt.setBatchFailedRecordCount(nonNegative(request.getBatchFailedRecordCount()));
        receipt.setTotalRecordsRead(nonNegative(request.getTotalRecordsRead()));
        receipt.setTotalRecordsWritten(nonNegative(request.getTotalRecordsWritten()));
        receipt.setTotalFailedRecordCount(nonNegative(request.getTotalFailedRecordCount()));
        receipt.setProgressPercent(normalizeProgressPercent(request.getProgressPercent()));
        receipt.setEndOfSource(Boolean.TRUE.equals(request.getEndOfSource()));
        receipt.setCompleted(eventType == DataSyncWorkerExecutionReceiptEventType.COMPLETE || Boolean.TRUE.equals(request.getCompleted()));
        receipt.setFailed(eventType == DataSyncWorkerExecutionReceiptEventType.FAILED || Boolean.TRUE.equals(request.getFailed()));
        receipt.setProgressReported(Boolean.TRUE.equals(request.getProgressReported()));
        receipt.setCheckpointPersisted(eventType == DataSyncWorkerExecutionReceiptEventType.CHECKPOINT
                || Boolean.TRUE.equals(request.getCheckpointPersisted()));
        receipt.setCheckpointType(trimToNull(request.getCheckpointType()));
        receipt.setCheckpointValueVisibility(trimToNull(request.getCheckpointValueVisibility()));
        receipt.setErrorSummary(sanitizeSummary(request.getErrorSummary(), MAX_SUMMARY_LENGTH));
        receipt.setWarningCount(request.getWarnings() == null ? 0 : request.getWarnings().size());
        receipt.setWarningSummary(sanitizeWarningSummary(request.getWarnings()));
        receipt.setCreateTime(now);
        receipt.setUpdateTime(now);
        return receipt;
    }

    private DataSyncWorkerCommandOutbox resolveOutbox(DataSyncWorkerExecutionReceiptRecordRequest request) {
        if (request.getCommandId() != null && !request.getCommandId().isBlank()) {
            return loadOutboxByCommandId(request.getCommandId());
        }
        DataSyncWorkerCommandOutbox outbox = outboxMapper.selectOne(
                new LambdaQueryWrapper<DataSyncWorkerCommandOutbox>()
                        .eq(DataSyncWorkerCommandOutbox::getSyncTaskId, request.getSyncTaskId())
                        .eq(DataSyncWorkerCommandOutbox::getSyncExecutionId, request.getSyncExecutionId())
                        .last("LIMIT 1")
        );
        if (outbox == null && request.getCommandId() != null && !request.getCommandId().isBlank()) {
            throw new IllegalStateException("未找到可关联的 DataSync worker outbox，拒绝记录孤立 execution receipt");
        }
        if (outbox == null) {
            return standaloneOutbox(request);
        }
        return outbox;
    }

    /**
     * 为页面/调度器直接创建的 data-sync 任务构造虚拟关联上下文。
     *
     * <p>这里不把 receipt 直接丢弃，也不要求 data-sync 反向伪造一个 Agent command。原因是普通同步任务已经是
     * data-sync 自己的一等业务对象，它的执行事实应该能进入 task-management 的跨模块投影视图。虚拟 outbox 只解决
     * 旧表结构中的非空字段和查询维度问题，不会触发任何真实下游投递、重试或 Agent timeline 副作用。</p>
     *
     * <p>taskId 暂时用 syncTaskId 兜底，是兼容现有 NOT NULL 约束的折中；真正的业务归属仍以 syncTaskId 和
     * syncExecutionId 为准，后续如果 task-management 建立正式的“外部任务引用表”，可以再把这里替换为真实关联。</p>
     */
    private DataSyncWorkerCommandOutbox standaloneOutbox(DataSyncWorkerExecutionReceiptRecordRequest request) {
        DataSyncWorkerCommandOutbox outbox = new DataSyncWorkerCommandOutbox();
        outbox.setCommandId(STANDALONE_COMMAND_ID_PREFIX + request.getSyncTaskId()
                + ":execution:" + request.getSyncExecutionId());
        outbox.setOutboxId(STANDALONE_OUTBOX_ID_PREFIX + request.getSyncExecutionId());
        outbox.setTaskId(request.getSyncTaskId());
        outbox.setTargetService(trimToDefault(request.getSourceService(), DEFAULT_SOURCE_SERVICE));
        outbox.setOperation("DATA_SYNC_STANDALONE_EXECUTION_RECEIPT");
        outbox.setStatus("RECEIPT_ONLY");
        outbox.setSyncTaskId(request.getSyncTaskId());
        outbox.setSyncExecutionId(request.getSyncExecutionId());
        return outbox;
    }

    private DataSyncWorkerCommandOutbox loadOutboxByCommandId(String commandId) {
        DataSyncWorkerCommandOutbox outbox = outboxMapper.selectOne(
                new LambdaQueryWrapper<DataSyncWorkerCommandOutbox>()
                        .eq(DataSyncWorkerCommandOutbox::getCommandId, commandId.trim())
                        .last("LIMIT 1")
        );
        if (outbox == null) {
            throw new IllegalStateException("DataSync worker outbox 不存在，无法记录 execution receipt: " + commandId);
        }
        return outbox;
    }

    private DataSyncWorkerExecutionReceipt loadByReceiptId(String receiptId) {
        DataSyncWorkerExecutionReceipt existing = receiptMapper.selectOne(
                new LambdaQueryWrapper<DataSyncWorkerExecutionReceipt>()
                        .eq(DataSyncWorkerExecutionReceipt::getReceiptId, receiptId.trim())
                        .last("LIMIT 1")
        );
        if (existing == null) {
            throw new IllegalStateException("execution receipt 幂等冲突后未能找到已有记录");
        }
        return existing;
    }

    private LambdaQueryWrapper<DataSyncWorkerExecutionReceipt> buildQueryWrapper(
            String commandId,
            Long syncTaskId,
            Long syncExecutionId,
            Long taskId,
            Long tenantId,
            Long projectId,
            DataSyncWorkerExecutionReceiptEventType eventType,
            Integer limit) {
        LambdaQueryWrapper<DataSyncWorkerExecutionReceipt> wrapper = new LambdaQueryWrapper<>();
        if (commandId != null && !commandId.isBlank()) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getCommandId, commandId.trim());
        }
        if (syncTaskId != null) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getSyncTaskId, syncTaskId);
        }
        if (syncExecutionId != null) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getSyncExecutionId, syncExecutionId);
        }
        if (taskId != null) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getTaskId, taskId);
        }
        if (tenantId != null) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getTenantId, tenantId);
        }
        if (projectId != null) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getProjectId, projectId);
        }
        if (eventType != null) {
            wrapper.eq(DataSyncWorkerExecutionReceipt::getEventType, eventType.name());
        }
        if (limit != null) {
            wrapper.last("LIMIT " + limit);
        }
        return wrapper;
    }

    private Map<String, Long> countByEventType(String commandId,
                                               Long syncTaskId,
                                               Long syncExecutionId,
                                               Long taskId,
                                               Long tenantId,
                                               Long projectId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.stream(DataSyncWorkerExecutionReceiptEventType.values()).forEach(type -> counts.put(
                type.name(),
                receiptMapper.selectCount(buildQueryWrapper(commandId, syncTaskId, syncExecutionId, taskId,
                        tenantId, projectId, type, null))
        ));
        return counts;
    }

    private List<String> buildRecordWarnings(DataSyncWorkerExecutionReceiptRecordRequest request,
                                             DataSyncWorkerCommandOutbox outbox,
                                             DataSyncWorkerExecutionReceiptEventType eventType) {
        List<String> warnings = new ArrayList<>();
        if ((request.getCommandId() == null || request.getCommandId().isBlank()) && isStandaloneOutbox(outbox)) {
            warnings.add("本次 execution receipt 来自普通 data-sync 任务，没有 Agent command/outbox；"
                    + "task-management 已使用 standalone 关联键保存低敏执行投影");
        }
        if ((request.getCommandId() == null || request.getCommandId().isBlank()) && !isStandaloneOutbox(outbox)) {
            warnings.add("本次 execution receipt 未携带 commandId，已通过 syncTaskId/syncExecutionId 回查 outbox 关联上下文");
        }
        if (eventType == DataSyncWorkerExecutionReceiptEventType.FAILED) {
            warnings.add("收到 FAILED execution receipt；任务中心仅记录低敏投影，不直接改写已完成的投递任务状态");
        }
        if (eventType == DataSyncWorkerExecutionReceiptEventType.PARTIALLY_SUCCEEDED) {
            warnings.add("收到 PARTIALLY_SUCCEEDED execution receipt；该状态表示下游已完成可完成部分，但仍存在失败对象或分片需要选择性重试");
        }
        if (!request.getSyncTaskId().equals(outbox.getSyncTaskId())
                || !request.getSyncExecutionId().equals(outbox.getSyncExecutionId())) {
            warnings.add("execution receipt 的 sync 引用与 outbox 快照存在差异，请检查下游回执链路是否存在重放或旧执行引用");
        }
        if (containsSensitiveLikeText(request.getErrorSummary())
                || request.getWarnings() != null && request.getWarnings().stream().anyMatch(this::containsSensitiveLikeText)) {
            warnings.add("errorSummary/warnings 已执行敏感片段脱敏和长度裁剪，API 响应不会返回正文");
        }
        return warnings;
    }

    private boolean isStandaloneOutbox(DataSyncWorkerCommandOutbox outbox) {
        return outbox != null
                && outbox.getCommandId() != null
                && outbox.getCommandId().startsWith(STANDALONE_COMMAND_ID_PREFIX);
    }

    private List<String> buildQueryWarnings(long totalCount, int returnedCount, int effectiveLimit) {
        List<String> warnings = new ArrayList<>();
        if (totalCount == 0) {
            warnings.add("当前过滤条件下没有 DataSync worker execution receipt");
        }
        if (totalCount > returnedCount && returnedCount == effectiveLimit) {
            warnings.add("查询结果已按 limit 截断，请缩小 commandId、syncExecutionId、taskId、租户或事件类型过滤条件");
        }
        return warnings;
    }

    private void validateRecordRequest(DataSyncWorkerExecutionReceiptRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker execution receipt 请求不能为空");
        }
        requireText(request.getReceiptId(), "receiptId");
        DataSyncWorkerExecutionReceiptEventType.parse(request.getEventType());
        requirePositive(request.getSyncTaskId(), "syncTaskId");
        requirePositive(request.getSyncExecutionId(), "syncExecutionId");
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    private void requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于 0");
        }
    }

    private Long nonNegative(Long value) {
        if (value == null || value < 0) {
            return 0L;
        }
        return value;
    }

    private Integer normalizeProgressPercent(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(0, Math.min(100, value));
    }

    private int clamp(Integer requested, int defaultValue, int maxValue) {
        if (requested == null || requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, maxValue);
    }

    private String sanitizeWarningSummary(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        String joined = String.join(" | ", warnings);
        return sanitizeSummary(joined, MAX_WARNING_SUMMARY_LENGTH);
    }

    private String sanitizeSummary(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        String sanitized = SECRET_ASSIGNMENT_PATTERN.matcher(normalized).replaceAll("$1=<已隐藏>");
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("Bearer <已隐藏>");
        sanitized = INTERNAL_ENDPOINT_PATTERN.matcher(sanitized).replaceAll("<内部地址已隐藏>");
        sanitized = SQL_FRAGMENT_PATTERN.matcher(sanitized).replaceAll("<疑似SQL已隐藏>");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private boolean containsSensitiveLikeText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return SECRET_ASSIGNMENT_PATTERN.matcher(value).find()
                || BEARER_TOKEN_PATTERN.matcher(value).find()
                || INTERNAL_ENDPOINT_PATTERN.matcher(value).find()
                || SQL_FRAGMENT_PATTERN.matcher(value).find();
    }

    private String trimToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
