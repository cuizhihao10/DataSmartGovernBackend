/**
 * @Author : Cui
 * @Date: 2026/05/24 22:21
 * @Description DataSmart Govern Backend - QualityRuleSuggestToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * `quality.rule.suggest` 工具适配器。
 *
 * <p>该适配器是 Agent 从“读取元数据”走向“生成治理建议”的第二条真实业务工具链路。
 * 它调用 data-quality 的 `/quality-rules/suggestions`，只生成质量规则草案，不直接写入规则表。</p>
 *
 * <p>为什么它被建模为 DRAFT_ONLY 工具：</p>
 * <p>1. 质量规则会影响生产检测、告警和报表，不能由模型直接启用；</p>
 * <p>2. 草案生成可以自动化，但保存、审批、启用仍应由人类或受控流程确认；</p>
 * <p>3. 这能让 Agent 快速产生业务价值，同时避免越过 data-quality 的规则生命周期。</p>
 */
@Component
public class QualityRuleSuggestToolAdapter implements AgentToolAdapter {

    public static final String TOOL_CODE = "quality.rule.suggest";
    private static final String TARGET_SERVICE = "data-quality";

    private final AgentRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final QualityRuleSuggestRequestFactory requestFactory;
    private final QualityRuleSuggestResponseMapper responseMapper;

    public QualityRuleSuggestToolAdapter(AgentRuntimeProperties properties,
                                         RestClient.Builder restClientBuilder,
                                         QualityRuleSuggestRequestFactory requestFactory,
                                         QualityRuleSuggestResponseMapper responseMapper) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
        this.requestFactory = requestFactory;
        this.responseMapper = responseMapper;
    }

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equals(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        QualityRuleSuggestRequest request = requestFactory.build(context);
        try {
            Map<String, Object> response = restClientBuilder
                    .baseUrl(resolveBaseUrl())
                    .build()
                    .post()
                    .uri("/quality-rules/suggestions")
                    .headers(headers -> applyPlatformHeaders(headers, context))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return responseMapper.toOutcome(request.datasourceId(), response);
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("QUALITY_RULE_SUGGEST_DOWNSTREAM_ERROR",
                    "调用 data-quality 规则草案建议接口失败: " + exception.getMessage());
        }
    }

    /**
     * 透传平台上下文 Header。
     *
     * <p>草案生成虽然不写数据库，但仍会暴露数据结构和治理目标，因此必须带上 PROJECT 数据范围。
     * data-quality 会用 `AUTHORIZED_PROJECT_IDS` 校验请求体 projectId，防止 Agent 为未授权项目生成规则建议。</p>
     */
    private void applyPlatformHeaders(HttpHeaders headers, AgentToolExecutionContext context) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(context.session().getTenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID, context.session().getActorId());
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "AGENT_RUNTIME");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.TRACE_ID,
                context.traceId() == null ? context.audit().getTraceId() : context.traceId());
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, String.valueOf(context.session().getProjectId()));
    }

    private String resolveBaseUrl() {
        String baseUrl = properties.getToolServiceBaseUrls().get(TARGET_SERVICE);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未配置工具下游服务地址，targetService=" + TARGET_SERVICE);
        }
        return baseUrl;
    }
}
