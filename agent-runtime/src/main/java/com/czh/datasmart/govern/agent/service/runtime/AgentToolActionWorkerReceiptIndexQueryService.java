/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionWorkerReceiptIndexQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionWorkerReceiptIndexView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * worker receipt 低敏索引查询服务。
 *
 * <p>该服务位于 Controller 与 {@link AgentToolActionWorkerReceiptIndexService} 之间，专门负责把外部查询参数
 * 收口成安全的索引查询条件，并把底层 record 转成可公开给管理台的低敏 DTO。它不直接解析 runtime event
 * attributes，也不执行工具、不写 command outbox、不派发 worker。</p>
 *
 * <p>为什么不让 Controller 直接调用索引服务：
 * 1. Controller 只应该处理 HTTP 参数和 Header，不能承载数据范围、脱敏、证据码和生产缺口解释；
 * 2. 底层索引服务是事实物化/查询能力，不应该知道“哪些字段允许对外展示”；
 * 3. 未来如果新增 Prometheus 指标、审计导出、TTL 查询或真实 worker receipt，只需要扩展本查询服务。</p>
 *
 * <p>安全原则：本服务只接受 commandId 入口，不提供全量列表；只返回字段白名单；eventIdentityKey 只返回短指纹；
 * 任何 prompt、SQL、payload、arguments、sample、model output、credential 或 endpoint 字段都不会被读取或返回。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionWorkerReceiptIndexQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final String QUERY_MODE = "COMMAND_ID_REQUIRED_LOW_SENSITIVE_WORKER_RECEIPT_INDEX";

    private final AgentToolActionWorkerReceiptIndexService receiptIndexService;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;
    private final AgentToolActionResumeFactBundleProperties properties;

    /**
     * 按 commandId 查询 worker receipt 低敏索引。
     *
     * @param commandId 必填命令 ID。缺失时直接返回 BAD_REQUEST，避免接口被误用成全表搜索。
     * @param toolCode 可选工具编码。历史 receipt 可能没有 toolCode，因此底层索引会保持兼容匹配。
     * @param tenantId 调用方希望进一步缩小的租户条件，最终会与 Header 中的可信范围求交集。
     * @param projectId 调用方希望进一步缩小的项目条件，PROJECT 范围下必须落在授权项目集合内。
     * @param actorId 调用方希望进一步缩小的 actor 条件，SELF 范围下会被强制收口到当前 actor。
     * @param runId 可选 run 条件，建议恢复排障时传入，避免跨运行误采信同名 command。
     * @param sessionId 可选 session 条件，用于会话级恢复和 timeline 排障。
     * @param limit 单次返回上限，服务层会归一化到 1..500。
     * @param accessContext gateway/permission-admin 透传的可信访问上下文。
     * @return 低敏 receipt 索引查询响应。
     */
    public AgentToolActionWorkerReceiptIndexQueryResponse queryReceipts(String commandId,
                                                                        String toolCode,
                                                                        String tenantId,
                                                                        String projectId,
                                                                        String actorId,
                                                                        String runId,
                                                                        String sessionId,
                                                                        Integer limit,
                                                                        AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedCommandId = requiredCommandId(commandId);
        int appliedLimit = normalizedLimit(limit);
        AgentRuntimeEventProjectionQuery originalQuery = new AgentRuntimeEventProjectionQuery(
                trimToNull(tenantId),
                trimToNull(projectId),
                trimToNull(actorId),
                null,
                trimToNull(runId),
                trimToNull(sessionId),
                null,
                null,
                appliedLimit
        );
        AgentRuntimeEventProjectionQuery scopedQuery = accessSupport.restrict(originalQuery, accessContext);
        List<String> authorizedProjectIds = scopedQuery.normalizedAuthorizedProjectIds();
        if (authorizedProjectIds != null && authorizedProjectIds.isEmpty()) {
            return buildResponse(
                    normalizedCommandId,
                    toolCode,
                    scopedQuery,
                    appliedLimit,
                    List.of(),
                    List.of(
                            "WORKER_RECEIPT_INDEX_QUERY_SCOPED",
                            "WORKER_RECEIPT_INDEX_PROJECT_SCOPE_EMPTY",
                            "WORKER_RECEIPT_INDEX_MESSAGE_AND_PAYLOAD_NOT_EXPOSED"
                    )
            );
        }

        List<AgentToolActionWorkerReceiptIndexRecord> records = receiptIndexService.queryByCommandId(
                normalizedCommandId,
                trimToNull(toolCode),
                scopedQuery,
                appliedLimit
        );
        List<String> evidenceCodes = new ArrayList<>();
        evidenceCodes.add("WORKER_RECEIPT_INDEX_QUERY_SCOPED");
        evidenceCodes.add("WORKER_RECEIPT_INDEX_LOW_SENSITIVE_FIELDS_ONLY");
        evidenceCodes.add("WORKER_RECEIPT_INDEX_MESSAGE_AND_PAYLOAD_NOT_EXPOSED");
        evidenceCodes.add(records.isEmpty()
                ? "WORKER_RECEIPT_INDEX_RECORDS_NOT_FOUND"
                : "WORKER_RECEIPT_INDEX_RECORDS_FOUND");
        return buildResponse(normalizedCommandId, toolCode, scopedQuery, appliedLimit, records, evidenceCodes);
    }

    private AgentToolActionWorkerReceiptIndexQueryResponse buildResponse(String commandId,
                                                                         String toolCode,
                                                                         AgentRuntimeEventProjectionQuery scopedQuery,
                                                                         int appliedLimit,
                                                                         List<AgentToolActionWorkerReceiptIndexRecord> records,
                                                                         List<String> evidenceCodes) {
        return new AgentToolActionWorkerReceiptIndexQueryResponse(
                appliedLimit,
                records.size(),
                storeMode(),
                QUERY_MODE,
                AgentToolActionWorkerReceiptIndexRecord.PAYLOAD_POLICY,
                receiptIndexService.size(),
                commandId,
                trimToNull(toolCode),
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                scopedQuery.normalizedAuthorizedProjectIds(),
                List.copyOf(evidenceCodes),
                missingCapabilities(),
                records.stream().map(this::toView).toList()
        );
    }

    private AgentToolActionWorkerReceiptIndexView toView(AgentToolActionWorkerReceiptIndexRecord record) {
        return new AgentToolActionWorkerReceiptIndexView(
                hasText(record.eventIdentityKey()),
                fingerprint(record.eventIdentityKey()),
                record.commandId(),
                record.taskId(),
                record.taskRunId(),
                record.executorId(),
                record.auditId(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.runId(),
                record.sessionId(),
                record.toolCode(),
                record.taskStatus(),
                record.outcome(),
                record.preCheckPassed(),
                record.sideEffectExecuted(),
                record.errorCode(),
                record.replaySequence(),
                record.consumedAt(),
                record.indexedAt(),
                AgentToolActionWorkerReceiptIndexRecord.PAYLOAD_POLICY
        );
    }

    private List<String> missingCapabilities() {
        List<String> capabilities = new ArrayList<>();
        if (!"MYSQL".equals(storeMode())) {
            capabilities.add("MYSQL_DURABLE_WORKER_RECEIPT_INDEX");
        }
        capabilities.add("WORKER_RECEIPT_INDEX_TTL_ARCHIVE");
        capabilities.add("WORKER_RECEIPT_INDEX_LOW_CARDINALITY_METRICS");
        capabilities.add("WORKER_RECEIPT_INDEX_AUDIT_EXPORT");
        capabilities.add("REAL_WORKER_SIDE_EFFECT_RECEIPT_MODEL");
        return List.copyOf(capabilities);
    }

    private String storeMode() {
        String mode = properties == null ? null : properties.getWorkerReceiptIndexStore();
        if (!hasText(mode)) {
            return "MEMORY";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }

    private String fingerprint(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 receipt 来源指纹", exception);
        }
    }

    private String requiredCommandId(String commandId) {
        String normalized = trimToNull(commandId);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "查询 worker receipt 低敏索引必须提供 commandId，禁止无界扫描 receipt 索引");
        }
        return normalized;
    }

    private int normalizedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
