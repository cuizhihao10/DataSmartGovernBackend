/**
 * @Author : Cui
 * @Date: 2026/06/26 21:39
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactBodyReadGrantStoreProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantFactView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRevokeResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * artifact 正文读取 grant fact 的低敏查询与撤销服务。
 *
 * <p>该服务位于 Controller 与 {@link AgentToolActionArtifactBodyReadGrantRecordService} 之间，
 * 负责把外部 HTTP 参数收口成安全的仓储查询条件，并把底层 record 转成可展示给管理台的低敏 DTO。
 * 它不读取 artifact 正文，不生成下载地址，不返回对象存储 bucket/key，也不重新计算授权策略。</p>
 *
 * <p>为什么不让 Controller 直接调用 Store：
 * 1. Controller 只应该处理 HTTP 参数和 Header，不应承载权限范围、脱敏、证据码和生产缺口解释；
 * 2. Store 只负责保存/查询事实，不应该知道哪些字段允许对外展示；
 * 3. 未来新增审计导出、TTL 归档、限流、撤销审批或低基数指标时，可以集中扩展本服务。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactBodyReadGrantQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final String QUERY_MODE = "SELECTOR_REQUIRED_LOW_SENSITIVE_ARTIFACT_BODY_READ_GRANT_FACT";
    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_GRANT_FACT_ONLY_ARTIFACT_BODY_NOT_RETURNED";

    private final AgentToolActionArtifactBodyReadGrantRecordService recordService;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;
    private final AgentArtifactBodyReadGrantStoreProperties properties;

    /**
     * 查询 artifact 正文读取 grant fact。
     *
     * <p>本批次要求调用方至少提供 grantDecisionReference 或 commandId，这是一个有意的产品约束：
     * 真实商业化审计报表可以按租户/时间分页查询，但那需要导出审批、分页游标、频率限制和审计日志。
     * 当前管理接口只服务“我已知某个 grant/command，需要排障或撤销”的场景，避免被误用成全表浏览。</p>
     */
    public AgentToolActionArtifactBodyReadGrantQueryResponse queryGrants(
            String grantDecisionReference,
            String commandId,
            String artifactReference,
            String tenantId,
            String projectId,
            String actorId,
            String runId,
            String sessionId,
            String toolCode,
            String status,
            Integer limit,
            AgentRuntimeEventQueryAccessContext accessContext) {
        int appliedLimit = normalizedLimit(limit);
        AgentToolActionArtifactBodyReadGrantQuery scopedQuery = buildScopedQuery(
                grantDecisionReference,
                commandId,
                artifactReference,
                tenantId,
                projectId,
                actorId,
                runId,
                sessionId,
                toolCode,
                status,
                appliedLimit,
                accessContext
        );
        requireSelector(scopedQuery);
        if (scopedQuery.authorizedProjectIds() != null && scopedQuery.authorizedProjectIds().isEmpty()) {
            return buildResponse(scopedQuery, appliedLimit, List.of(), List.of(
                    "ARTIFACT_BODY_READ_GRANT_QUERY_SCOPED",
                    "ARTIFACT_BODY_READ_GRANT_PROJECT_SCOPE_EMPTY",
                    "ARTIFACT_BODY_AND_STORAGE_LOCATION_NOT_EXPOSED"
            ));
        }

        List<AgentToolActionArtifactBodyReadGrantRecord> records =
                recordService.query(scopedQuery, appliedLimit);
        List<String> evidenceCodes = new ArrayList<>();
        evidenceCodes.add("ARTIFACT_BODY_READ_GRANT_QUERY_SCOPED");
        evidenceCodes.add("ARTIFACT_BODY_READ_GRANT_LOW_SENSITIVE_FIELDS_ONLY");
        evidenceCodes.add("ARTIFACT_BODY_AND_STORAGE_LOCATION_NOT_EXPOSED");
        evidenceCodes.add(records.isEmpty()
                ? "ARTIFACT_BODY_READ_GRANT_FACTS_NOT_FOUND"
                : "ARTIFACT_BODY_READ_GRANT_FACTS_FOUND");
        return buildResponse(scopedQuery, appliedLimit, records, evidenceCodes);
    }

    /**
     * 撤销一条 grant fact。
     *
     * <p>撤销前会先用同一套数据范围收口查询确认“当前调用方能看到这条 grant”。如果不可见或不存在，
     * 统一返回 NOT_FOUND，避免通过撤销接口探测其他租户/项目是否存在某个 grant 引用。</p>
     */
    public AgentToolActionArtifactBodyReadGrantRevokeResponse revoke(
            String grantDecisionReference,
            String reasonCode,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionArtifactBodyReadGrantQuery scopedQuery = buildScopedQuery(
                grantDecisionReference,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                accessContext
        );
        requireSelector(scopedQuery);
        List<AgentToolActionArtifactBodyReadGrantRecord> visibleRecords = recordService.query(scopedQuery, 1);
        if (visibleRecords.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "当前范围内未找到可撤销的 artifact 正文读取 grant fact");
        }

        String operatorId = accessContext == null || accessContext.actorId() == null
                ? "UNKNOWN_OPERATOR"
                : String.valueOf(accessContext.actorId());
        AgentToolActionArtifactBodyReadGrantRecord revoked = recordService.revoke(
                scopedQuery.grantDecisionReference(),
                operatorId,
                normalizeReasonCode(reasonCode)
        ).orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                "artifact 正文读取 grant fact 已不存在，无法撤销"));

        return new AgentToolActionArtifactBodyReadGrantRevokeResponse(
                true,
                "ARTIFACT_BODY_READ_GRANT_REVOKED",
                toView(revoked),
                List.of(
                        "ARTIFACT_BODY_READ_GRANT_VISIBLE_IN_CURRENT_SCOPE",
                        "ARTIFACT_BODY_READ_GRANT_REVOKE_OPERATOR_FROM_TRUSTED_HEADER",
                        "ARTIFACT_BODY_AND_STORAGE_LOCATION_NOT_EXPOSED"
                ),
                List.of(),
                List.of("后续 final-check 和 object-store probe 将因 grant fact 已撤销而 fail-closed。")
        );
    }

    private AgentToolActionArtifactBodyReadGrantQuery buildScopedQuery(
            String grantDecisionReference,
            String commandId,
            String artifactReference,
            String tenantId,
            String projectId,
            String actorId,
            String runId,
            String sessionId,
            String toolCode,
            String status,
            Integer limit,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery originalQuery = new AgentRuntimeEventProjectionQuery(
                trimToNull(tenantId),
                trimToNull(projectId),
                trimToNull(actorId),
                null,
                trimToNull(runId),
                trimToNull(sessionId),
                null,
                null,
                limit
        );
        AgentRuntimeEventProjectionQuery scopedQuery = accessSupport.restrict(originalQuery, accessContext);
        return new AgentToolActionArtifactBodyReadGrantQuery(
                trimToNull(grantDecisionReference),
                trimToNull(commandId),
                trimToNull(artifactReference),
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                trimToNull(toolCode),
                normalizeStatus(status),
                scopedQuery.normalizedAuthorizedProjectIds(),
                limit
        );
    }

    private AgentToolActionArtifactBodyReadGrantQueryResponse buildResponse(
            AgentToolActionArtifactBodyReadGrantQuery query,
            int appliedLimit,
            List<AgentToolActionArtifactBodyReadGrantRecord> records,
            List<String> evidenceCodes) {
        return new AgentToolActionArtifactBodyReadGrantQueryResponse(
                appliedLimit,
                records.size(),
                storeMode(),
                QUERY_MODE,
                PAYLOAD_POLICY,
                recordService.size(),
                query.grantDecisionReference(),
                query.commandId(),
                query.artifactReference(),
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.runId(),
                query.sessionId(),
                query.toolCode(),
                query.status(),
                query.authorizedProjectIds(),
                List.copyOf(evidenceCodes),
                missingCapabilities(),
                records.stream().map(this::toView).toList()
        );
    }

    private AgentToolActionArtifactBodyReadGrantFactView toView(
            AgentToolActionArtifactBodyReadGrantRecord record) {
        return new AgentToolActionArtifactBodyReadGrantFactView(
                record.grantDecisionReference(),
                record.commandId(),
                record.artifactReference(),
                record.artifactReferenceType(),
                record.readPurpose(),
                record.requestedContentMode(),
                record.maxReadableBytes(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.runId(),
                record.sessionId(),
                record.toolCode(),
                record.matchedReceiptFingerprint(),
                record.replaySequence(),
                record.receiptOutcome(),
                record.issuedAtEpochMs(),
                record.expiresAtEpochMs(),
                record.status() == null ? null : record.status().name(),
                record.revokedAtEpochMs(),
                record.revokedBy(),
                record.revokeReasonCode(),
                PAYLOAD_POLICY
        );
    }

    private void requireSelector(AgentToolActionArtifactBodyReadGrantQuery query) {
        if (query == null || !query.hasRequiredSelector()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "查询或撤销 artifact 正文读取 grant fact 必须提供 grantDecisionReference 或 commandId，禁止无界扫描");
        }
    }

    private List<String> missingCapabilities() {
        List<String> capabilities = new ArrayList<>();
        if (!"MYSQL".equals(storeMode())) {
            capabilities.add("MYSQL_DURABLE_ARTIFACT_BODY_READ_GRANT_FACT");
        }
        capabilities.add("ARTIFACT_BODY_READ_GRANT_TTL_ARCHIVE");
        capabilities.add("ARTIFACT_DOWNLOAD_AUDIT");
        capabilities.add("ARTIFACT_DLP_AND_MALWARE_SCAN");
        capabilities.add("ARTIFACT_BODY_READ_RATE_LIMIT");
        return List.copyOf(capabilities);
    }

    private String storeMode() {
        String mode = properties == null ? null : properties.getStore();
        if (mode == null || mode.isBlank()) {
            return "MEMORY";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }

    private int normalizedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalizeStatus(String status) {
        String value = trimToNull(status);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private String normalizeReasonCode(String reasonCode) {
        String value = trimToNull(reasonCode);
        if (value == null) {
            return "MANUAL_REVOKE";
        }
        String normalized = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_:-]", "_");
        if (normalized.length() > 120) {
            return normalized.substring(0, 120);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
