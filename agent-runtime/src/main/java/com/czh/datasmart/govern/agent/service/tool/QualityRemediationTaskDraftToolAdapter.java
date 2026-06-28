/**
 * @Author : Cui
 * @Date: 2026/06/28 13:26
 * @Description DataSmart Govern Backend - QualityRemediationTaskDraftToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadMaterializationService;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadMaterializationService.AgentToolActionPayloadMaterializationRequest;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadRecord;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * `quality.remediation.task.draft` 工具适配器。
 *
 * <p>该工具承接 Python Runtime 生成的质量异常治理任务 ToolPlan，把它送入 Java 控制面的真实工具执行框架。
 * 它调用 data-quality 的 `/quality-rules/remediation-tasks`，但请求体会被工厂强制设置为 `dryRun=true`。
 * 因此当前阶段只生成低敏预览，不会让 data-quality 提交 task-management，也不会触发清洗执行器。</p>
 *
 * <p>为什么不直接复用 `task.create.draft`：</p>
 * <p>1. `task.create.draft` 是泛化任务草稿，输入通常是 objective 和前序建议；</p>
 * <p>2. 质量异常治理任务需要 data-quality 根据 report/rule/severity/anomaly 等低敏范围统计异常数量；</p>
 * <p>3. 由 data-quality 生成 payload preview，可以复用它已有的项目可见性、异常聚合和低敏策略。</p>
 */
@Component
public class QualityRemediationTaskDraftToolAdapter implements AgentToolAdapter {

    public static final String TOOL_CODE = "quality.remediation.task.draft";
    private static final String TARGET_SERVICE = "data-quality";
    private static final String TARGET_ENDPOINT = "/quality-rules/remediation-tasks";

    private final AgentRuntimeProperties properties;
    private final AgentToolServiceAuthorizationProperties serviceAuthorizationProperties;
    private final RestClient.Builder restClientBuilder;
    private final QualityRemediationTaskDraftRequestFactory requestFactory;
    private final QualityRemediationTaskDraftResponseMapper responseMapper;
    private final AgentToolActionPayloadMaterializationService payloadMaterializationService;

    public QualityRemediationTaskDraftToolAdapter(
            AgentRuntimeProperties properties,
            AgentToolServiceAuthorizationProperties serviceAuthorizationProperties,
            RestClient.Builder restClientBuilder,
            QualityRemediationTaskDraftRequestFactory requestFactory,
            QualityRemediationTaskDraftResponseMapper responseMapper,
            AgentToolActionPayloadMaterializationService payloadMaterializationService) {
        this.properties = properties;
        this.serviceAuthorizationProperties = serviceAuthorizationProperties;
        this.restClientBuilder = restClientBuilder;
        this.requestFactory = requestFactory;
        this.responseMapper = responseMapper;
        this.payloadMaterializationService = payloadMaterializationService;
    }

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equals(toolCode);
    }

    /**
     * 执行质量异常治理任务 dry-run。
     *
     * <p>执行流程是“请求收口 -> 服务账号 Header -> data-quality dry-run -> 低敏响应映射”。如果下游不可用，
     * 返回稳定错误码而不是抛出原始 HTTP 异常；执行框架会把该错误码写入工具审计，方便前端和告警分类。</p>
     */
    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        QualityRemediationTaskDraftRequest request = requestFactory.build(context);
        try {
            Map<String, Object> response = restClientBuilder
                    .baseUrl(resolveBaseUrl())
                    .build()
                    .post()
                    .uri(TARGET_ENDPOINT)
                    .headers(headers -> applyPlatformHeaders(headers, context))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            AgentToolExecutionOutcome outcome = responseMapper.toOutcome(request, response);
            return outcome.success() ? attachPayloadReference(context, outcome) : outcome;
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("QUALITY_REMEDIATION_DRAFT_DOWNSTREAM_ERROR",
                    "调用 data-quality 治理任务 dry-run 接口失败: " + exception.getMessage());
        }
    }

    /**
     * 将低敏治理任务草案正文登记为服务端 payload body，并在工具输出中只保留引用。
     *
     * <p>为什么不直接把 `lowSensitivePayloadPreview` 留在工具输出里：
     * 工具输出会被审计查询、模型二轮反馈、runtime event、前端卡片和后续 outbox 链路多次消费。
     * 即使当前 preview 是低敏的，反复复制大 payload 也会增加泄露面、日志体积和审批页版本漂移风险。
     * 因此这里把草案正文物化到 `agent-payload:` store，输出只保留引用、大小和策略字段。</p>
     */
    private AgentToolExecutionOutcome attachPayloadReference(AgentToolExecutionContext context,
                                                             AgentToolExecutionOutcome outcome) {
        Map<String, Object> output = new LinkedHashMap<>(outcome.output());
        Object rawDraft = output.get("remediationTaskDraft");
        if (!(rawDraft instanceof Map<?, ?> draftMap)) {
            return outcome;
        }
        Map<String, Object> draftForStore = copyStringKeyMap(draftMap);
        Map<String, Object> draftForOutput = new LinkedHashMap<>(draftForStore);
        draftForOutput.remove("lowSensitivePayloadPreview");

        Map<String, Object> payloadBody = new LinkedHashMap<>();
        payloadBody.put("summary", copyIfMap(output.get("summary")));
        payloadBody.put("remediationTaskDraft", draftForStore);
        payloadBody.put("recommendedActions", output.get("recommendedActions"));

        String payloadKey = "quality-remediation-task-draft:" + safeSegment(context.audit().getAuditId());
        Optional<AgentToolActionPayloadRecord> materialized = payloadMaterializationService
                .buildPayloadReference(context.run().getRunId(), payloadKey)
                .flatMap(reference -> payloadMaterializationService.materializePayloadBody(
                        new AgentToolActionPayloadMaterializationRequest(
                                reference,
                                context.run().getRunId(),
                                String.valueOf(context.session().getTenantId()),
                                String.valueOf(context.session().getProjectId()),
                                context.session().getActorId(),
                                context.audit().getToolCode(),
                                "quality-remediation-task-draft-graph",
                                "quality-remediation-task-draft.v1",
                                "LOW_SENSITIVE_DRAFT_BODY",
                                argumentNames(context.audit().getPlanArguments()),
                                sensitiveArgumentNames(context.audit().getGovernanceHints()),
                                payloadBody,
                                Duration.ofHours(2)
                        )));

        if (materialized.isPresent()) {
            AgentToolActionPayloadRecord record = materialized.get();
            draftForOutput.put("payloadReference", record.payloadReference());
            draftForOutput.put("payloadBodyAvailable", record.payloadBodyAvailable());
            draftForOutput.put("payloadSizeBytes", record.payloadSizeBytes());
            draftForOutput.put("payloadPolicy", record.payloadPolicy());
            draftForOutput.put("payloadReferenceTtlPolicy", "TWO_HOURS");
            output.put("summary", summaryWithPayloadReference(output.get("summary"), record));
        } else {
            draftForOutput.put("payloadReferenceStatus", "UNAVAILABLE");
            draftForOutput.put("payloadReferenceIssue", "LOW_SENSITIVE_DRAFT_BODY_NOT_MATERIALIZED");
        }
        output.put("remediationTaskDraft", draftForOutput);
        return AgentToolExecutionOutcome.succeeded(outcome.message(), output);
    }

    /**
     * 设置跨服务调用 Header。
     *
     * <p>这里使用 agent-runtime 的服务账号 actorId，而不是人类会话 actorId。原因是该调用是 Java 控制面代表
     * Agent 执行的服务间协议，且 data-quality 当前 `actorId` Header 使用 Long 解析；如果把 `u-001`
     * 这类前端用户编码透传过去，会造成 Header 类型转换失败。真实用户是谁仍保留在工具审计记录中。</p>
     */
    private void applyPlatformHeaders(HttpHeaders headers, AgentToolExecutionContext context) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(context.session().getTenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID,
                String.valueOf(serviceAuthorizationProperties.getServiceAccountActorId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, serviceAuthorizationProperties.getServiceAccountRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.TRACE_ID,
                context.traceId() == null ? context.audit().getTraceId() : context.traceId());
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        if (context.session().getProjectId() != null) {
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, String.valueOf(context.session().getProjectId()));
        }
    }

    private String resolveBaseUrl() {
        String baseUrl = properties.getToolServiceBaseUrls().get(TARGET_SERVICE);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未配置工具下游服务地址，targetService=" + TARGET_SERVICE);
        }
        return baseUrl;
    }

    private Map<String, Object> summaryWithPayloadReference(Object rawSummary,
                                                            AgentToolActionPayloadRecord record) {
        Map<String, Object> summary = copyIfMap(rawSummary);
        summary.put("payloadReference", record.payloadReference());
        summary.put("payloadBodyAvailable", record.payloadBodyAvailable());
        summary.put("payloadSizeBytes", record.payloadSizeBytes());
        summary.put("payloadPolicy", record.payloadPolicy());
        return summary;
    }

    private Map<String, Object> copyIfMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyStringKeyMap(map);
        }
        return Map.of();
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private List<String> argumentNames(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .sorted()
                .toList();
    }

    private List<String> sensitiveArgumentNames(Map<String, Object> governanceHints) {
        if (governanceHints == null || governanceHints.isEmpty()) {
            return List.of();
        }
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .sorted()
                    .toList();
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(raw));
    }

    private String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
