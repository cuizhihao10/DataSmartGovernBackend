/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DataSync worker command outbox 调度查询服务。
 *
 * <p>这个服务承接上一阶段 outbox 写入能力之后的“调度控制面”职责：它不负责创建 outbox，也不负责调用
 * datasource-management/data-sync 的真实执行入口，而是负责从 outbox 中领取可投递命令，并提供低敏诊断视图。</p>
 *
 * <p>为什么不把这些方法继续写进 {@link DataSyncWorkerCommandOutboxService}：</p>
 * <p>1. 原服务已经负责 stage、dispatching 标记、success/failure receipt 记录，继续追加查询和 claim 会让单类过大；</p>
 * <p>2. claim/diagnostics 面向 dispatcher 和运维控制面，而 stage/receipt 面向业务 adapter，两个调用方生命周期不同；</p>
 * <p>3. 后续如果升级为 Kafka publisher、定时补偿器、租约续期或死信处理，本服务可以自然扩展为调度子域。</p>
 *
 * <p>并发语义：</p>
 * <p>当前实现采用“先查询候选记录，再用状态条件更新抢占”的方式。即使两个 dispatcher 同时读到同一条 PENDING 记录，
 * 也只有第一个把状态从 PENDING/DEFERRED 更新为 DISPATCHING 的实例会领取成功；第二个实例的条件更新会返回 0，
 * 从而跳过该命令。这比单纯 select 后 updateById 更安全。未来如果吞吐和并发继续提升，可以进一步升级到数据库
 * {@code FOR UPDATE SKIP LOCKED}、独立 lease 表或乐观锁版本号。</p>
 *
 * <p>低敏边界：</p>
 * <p>本服务的所有返回结果都通过 {@link DataSyncWorkerCommandOutboxView} 生成，不返回 payloadJson、错误正文、
 * SQL、工具参数、连接串、样本数据、prompt、模型输出或内部下游地址。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxDispatchService {

    private static final String CLAIM_SCHEMA_VERSION = "datasmart.task.data-sync-worker-outbox.claim.v1";
    private static final String DIAGNOSTICS_SCHEMA_VERSION = "datasmart.task.data-sync-worker-outbox.diagnostics.v1";
    private static final String ERROR_BODY_HIDDEN_POLICY = "ERROR_SUMMARY_BODY_STORED_BUT_NOT_EXPOSED";
    private static final String ERROR_BODY_EMPTY_POLICY = "NO_ERROR_SUMMARY";
    private static final String LOW_SENSITIVE_POLICY = "只返回 ID、状态、次数、时间、receipt 和下游低敏引用；不返回 payload_json、SQL、工具实参、样本数据、prompt、模型输出、连接串、凭据或错误正文。";
    private static final int DEFAULT_CLAIM_LIMIT = 20;
    private static final int MAX_CLAIM_LIMIT = 100;
    private static final int DEFAULT_DIAGNOSTICS_LIMIT = 50;
    private static final int MAX_DIAGNOSTICS_LIMIT = 200;

    private final DataSyncWorkerCommandOutboxMapper outboxMapper;

    /**
     * 领取可投递的 DataSync worker outbox 命令。
     *
     * <p>业务流程：</p>
     * <p>1. 校验 executorId，并把 limit 裁剪到安全范围；</p>
     * <p>2. 查询 PENDING 和可选的到期 DEFERRED 命令；</p>
     * <p>3. 对每条候选记录执行带状态条件的 update，抢占成功才返回给 dispatcher；</p>
     * <p>4. 成功领取的命令进入 DISPATCHING，attemptCount 加一，dispatchedAt/updateTime 更新为当前时间。</p>
     *
     * <p>注意：本方法只完成“领取”和“状态推进”，不会调用真实 data-sync worker。这样可以让自动 dispatcher、手动补偿器、
     * 未来 Kafka publisher 复用同一套领取语义，而不把网络投递、业务执行和 outbox 状态抢占耦合在一起。</p>
     *
     * @param request 领取请求，包含 executorId、租户/项目过滤、limit 和是否包含 DEFERRED。
     * @return 本次领取到的低敏 outbox 列表和提示信息。
     */
    @Transactional
    public DataSyncWorkerOutboxClaimResult claimDispatchCandidates(DataSyncWorkerOutboxClaimRequest request) {
        validateClaimRequest(request);
        LocalDateTime now = LocalDateTime.now();
        int effectiveLimit = clamp(request.getLimit(), DEFAULT_CLAIM_LIMIT, MAX_CLAIM_LIMIT);
        List<String> claimableStatuses = claimableStatusNames(request.includeDeferredCommands());
        List<DataSyncWorkerCommandOutbox> candidates = outboxMapper.selectList(
                buildClaimCandidateWrapper(request, claimableStatuses, now, effectiveLimit)
        );
        List<DataSyncWorkerCommandOutboxView> claimed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (DataSyncWorkerCommandOutbox candidate : candidates) {
            DataSyncWorkerCommandOutboxStatus currentStatus = parseRequiredStatus(candidate.getStatus());
            if (currentStatus.terminal()) {
                warnings.add("跳过已终态 outbox: " + candidate.getOutboxId());
                continue;
            }
            if (candidate.getNextRetryAt() != null && candidate.getNextRetryAt().isAfter(now)) {
                warnings.add("跳过尚未到达 nextRetryAt 的 outbox: " + candidate.getOutboxId());
                continue;
            }

            int nextAttemptCount = safeInt(candidate.getAttemptCount()) + 1;
            int updated = tryClaimOne(candidate, claimableStatuses, now, nextAttemptCount);
            if (updated <= 0) {
                warnings.add("并发领取竞争失败，其他 dispatcher 可能已经处理 outbox: " + candidate.getOutboxId());
                continue;
            }

            candidate.setStatus(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name());
            candidate.setAttemptCount(nextAttemptCount);
            candidate.setDispatchedAt(now);
            candidate.setUpdateTime(now);
            claimed.add(toView(candidate));
        }

        if (claimed.isEmpty()) {
            warnings.add("当前没有可领取的 DataSync worker outbox 命令");
        }
        return new DataSyncWorkerOutboxClaimResult(
                CLAIM_SCHEMA_VERSION,
                request.getExecutorId().trim(),
                now,
                request.getLimit(),
                effectiveLimit,
                claimed.size(),
                claimed,
                warnings
        );
    }

    /**
     * 查询 DataSync worker outbox 的低敏诊断信息。
     *
     * <p>诊断接口面向生产排障，不面向普通业务查询。它回答的是“命令在哪个投递状态”“有没有积压”“是否已经回写下游 receipt”，
     * 而不是展示真实同步 payload。即使内部表里有 payloadJson，本方法也不会读取并返回 payload 正文。</p>
     *
     * @param request 诊断过滤条件，可按租户、项目、taskId、commandId、状态和最近记录数量过滤。
     * @return 低敏聚合统计和最近记录。
     */
    @Transactional(readOnly = true)
    public DataSyncWorkerOutboxDiagnosticsResult diagnose(DataSyncWorkerOutboxDiagnosticsRequest request) {
        DataSyncWorkerOutboxDiagnosticsRequest safeRequest = request == null
                ? new DataSyncWorkerOutboxDiagnosticsRequest()
                : request;
        DataSyncWorkerCommandOutboxStatus requestedStatus = parseOptionalStatus(safeRequest.getStatus());
        int effectiveLimit = clamp(safeRequest.getLimit(), DEFAULT_DIAGNOSTICS_LIMIT, MAX_DIAGNOSTICS_LIMIT);
        long totalCount = outboxMapper.selectCount(buildDiagnosticsWrapper(safeRequest, requestedStatus, null));
        Map<String, Long> statusCounts = countByStatus(safeRequest);
        List<DataSyncWorkerCommandOutbox> recentRows = outboxMapper.selectList(
                buildDiagnosticsWrapper(safeRequest, requestedStatus, effectiveLimit)
                        .orderByDesc(DataSyncWorkerCommandOutbox::getUpdateTime)
                        .orderByDesc(DataSyncWorkerCommandOutbox::getId)
        );
        List<DataSyncWorkerCommandOutboxView> recentRecords = recentRows.stream()
                .map(this::toView)
                .toList();
        List<String> warnings = buildDiagnosticsWarnings(statusCounts, recentRecords, totalCount, effectiveLimit);

        return new DataSyncWorkerOutboxDiagnosticsResult(
                DIAGNOSTICS_SCHEMA_VERSION,
                requestedStatus == null ? null : requestedStatus.name(),
                totalCount,
                statusCounts,
                recentRecords,
                LOW_SENSITIVE_POLICY,
                warnings
        );
    }

    private LambdaQueryWrapper<DataSyncWorkerCommandOutbox> buildClaimCandidateWrapper(
            DataSyncWorkerOutboxClaimRequest request,
            List<String> claimableStatuses,
            LocalDateTime now,
            int limit) {
        LambdaQueryWrapper<DataSyncWorkerCommandOutbox> wrapper = new LambdaQueryWrapper<>();
        applyTenantProjectFilters(wrapper, request.getTenantId(), request.getProjectId());
        wrapper.in(DataSyncWorkerCommandOutbox::getStatus, claimableStatuses)
                .and(candidate -> candidate.isNull(DataSyncWorkerCommandOutbox::getNextRetryAt)
                        .or()
                        .le(DataSyncWorkerCommandOutbox::getNextRetryAt, now))
                .orderByAsc(DataSyncWorkerCommandOutbox::getNextRetryAt)
                .orderByAsc(DataSyncWorkerCommandOutbox::getAttemptCount)
                .orderByAsc(DataSyncWorkerCommandOutbox::getId)
                .last("LIMIT " + limit);
        return wrapper;
    }

    private int tryClaimOne(DataSyncWorkerCommandOutbox candidate,
                            List<String> claimableStatuses,
                            LocalDateTime now,
                            int nextAttemptCount) {
        DataSyncWorkerCommandOutbox update = new DataSyncWorkerCommandOutbox();
        update.setStatus(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name());
        update.setAttemptCount(nextAttemptCount);
        update.setDispatchedAt(now);
        update.setUpdateTime(now);
        return outboxMapper.update(
                update,
                new LambdaUpdateWrapper<DataSyncWorkerCommandOutbox>()
                        .eq(DataSyncWorkerCommandOutbox::getId, candidate.getId())
                        .in(DataSyncWorkerCommandOutbox::getStatus, claimableStatuses)
                        .and(row -> row.isNull(DataSyncWorkerCommandOutbox::getNextRetryAt)
                                .or()
                                .le(DataSyncWorkerCommandOutbox::getNextRetryAt, now))
        );
    }

    private LambdaQueryWrapper<DataSyncWorkerCommandOutbox> buildDiagnosticsWrapper(
            DataSyncWorkerOutboxDiagnosticsRequest request,
            DataSyncWorkerCommandOutboxStatus status,
            Integer limit) {
        LambdaQueryWrapper<DataSyncWorkerCommandOutbox> wrapper = new LambdaQueryWrapper<>();
        applyTenantProjectFilters(wrapper, request.getTenantId(), request.getProjectId());
        if (request.getTaskId() != null) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getTaskId, request.getTaskId());
        }
        if (request.getCommandId() != null && !request.getCommandId().isBlank()) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getCommandId, request.getCommandId().trim());
        }
        if (status != null) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getStatus, status.name());
        }
        if (limit != null) {
            wrapper.last("LIMIT " + limit);
        }
        return wrapper;
    }

    private Map<String, Long> countByStatus(DataSyncWorkerOutboxDiagnosticsRequest request) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.stream(DataSyncWorkerCommandOutboxStatus.values())
                .forEach(status -> counts.put(
                        status.name(),
                        outboxMapper.selectCount(buildDiagnosticsWrapper(request, status, null))
                ));
        return counts;
    }

    private List<String> buildDiagnosticsWarnings(Map<String, Long> statusCounts,
                                                  List<DataSyncWorkerCommandOutboxView> recentRecords,
                                                  long totalCount,
                                                  int effectiveLimit) {
        List<String> warnings = new ArrayList<>();
        if (statusCounts.getOrDefault(DataSyncWorkerCommandOutboxStatus.FAILED.name(), 0L) > 0) {
            warnings.add("存在 FAILED outbox，需要检查不可恢复失败原因和人工恢复策略");
        }
        if (statusCounts.getOrDefault(DataSyncWorkerCommandOutboxStatus.DEAD_LETTER.name(), 0L) > 0) {
            warnings.add("存在 DEAD_LETTER outbox，需要运维或平台管理员介入处理");
        }
        if (statusCounts.getOrDefault(DataSyncWorkerCommandOutboxStatus.DEFERRED.name(), 0L) > 0) {
            warnings.add("存在 DEFERRED outbox，dispatcher 应在 nextRetryAt 到达后重试");
        }
        if (totalCount > recentRecords.size() && recentRecords.size() == effectiveLimit) {
            warnings.add("最近记录已按 limit 截断，更多结果请缩小租户、项目、taskId 或 commandId 过滤条件");
        }
        if (totalCount == 0) {
            warnings.add("当前过滤条件下没有 DataSync worker outbox 记录");
        }
        return warnings;
    }

    private void applyTenantProjectFilters(LambdaQueryWrapper<DataSyncWorkerCommandOutbox> wrapper,
                                           Long tenantId,
                                           Long projectId) {
        if (tenantId != null) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getTenantId, tenantId);
        }
        if (projectId != null) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getProjectId, projectId);
        }
    }

    private List<String> claimableStatusNames(boolean includeDeferred) {
        List<String> statuses = new ArrayList<>();
        statuses.add(DataSyncWorkerCommandOutboxStatus.PENDING.name());
        if (includeDeferred) {
            statuses.add(DataSyncWorkerCommandOutboxStatus.DEFERRED.name());
        }
        return statuses;
    }

    private DataSyncWorkerCommandOutboxStatus parseRequiredStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalStateException("DataSync worker outbox 状态不能为空");
        }
        return parseOptionalStatus(status);
    }

    private DataSyncWorkerCommandOutboxStatus parseOptionalStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DataSyncWorkerCommandOutboxStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("未知 DataSync worker outbox 状态: " + status);
        }
    }

    private void validateClaimRequest(DataSyncWorkerOutboxClaimRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker outbox 领取请求不能为空");
        }
        if (request.getExecutorId() == null || request.getExecutorId().isBlank()) {
            throw new IllegalArgumentException("executorId 不能为空");
        }
    }

    private int clamp(Integer requested, int defaultValue, int maxValue) {
        if (requested == null || requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, maxValue);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private DataSyncWorkerCommandOutboxView toView(DataSyncWorkerCommandOutbox outbox) {
        boolean hasLastError = outbox.getLastError() != null && !outbox.getLastError().isBlank();
        return new DataSyncWorkerCommandOutboxView(
                outbox.getId(),
                outbox.getOutboxId(),
                outbox.getCommandId(),
                outbox.getIdempotencyKey(),
                outbox.getTaskId(),
                outbox.getAgentRunId(),
                outbox.getAgentSessionId(),
                outbox.getAuditId(),
                outbox.getToolCode(),
                outbox.getTargetService(),
                outbox.getOperation(),
                outbox.getTenantId(),
                outbox.getProjectId(),
                outbox.getWorkspaceId(),
                outbox.getTemplateId(),
                outbox.getSyncTemplateId(),
                outbox.getStatus(),
                outbox.getAttemptCount(),
                outbox.getPayloadSizeBytes(),
                outbox.getPayloadTruncated(),
                outbox.getNextRetryAt(),
                outbox.getDispatchedAt(),
                outbox.getReceiptId(),
                outbox.getSyncTaskId(),
                outbox.getSyncExecutionId(),
                outbox.getSideEffectStarted(),
                outbox.getSideEffectExecuted(),
                hasLastError,
                hasLastError ? ERROR_BODY_HIDDEN_POLICY : ERROR_BODY_EMPTY_POLICY,
                outbox.getCreateTime(),
                outbox.getUpdateTime()
        );
    }
}
