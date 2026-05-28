/**
 * @Author : Cui
 * @Date: 2026/05/14 19:13
 * @Description DataSmart Govern Backend - DatasourceMetadataReadToolAdapter.java
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
 * `datasource.metadata.read` 工具适配器。
 *
 * <p>该适配器是 Agent Runtime 第一条真实只读工具链路。
 * 它把 Agent 工具计划转换为 datasource-management 的元数据发现接口调用：
 * `POST /datasources/{id}/metadata/discover`。
 *
 * <p>为什么第一条工具选择“元数据读取”：
 * 1. 它是低风险只读工具，适合验证工具执行框架；
 * 2. 元数据是后续质量规则建议、同步模板生成、任务规划和 RAG 上下文的重要输入；
 * 3. 它可以先不接真实模型，也能让 Java 控制面跑通“计划 -> 执行 -> 审计结果”的闭环。
 *
 * <p>边界说明：
 * 本适配器不直接连接业务数据库，也不读取连接密码。
 * 它只调用 datasource-management，由 datasource-management 负责连接信息、项目可见性、性能限制和元数据发现。
 */
@Component
public class DatasourceMetadataReadToolAdapter implements AgentToolAdapter {

    public static final String TOOL_CODE = "datasource.metadata.read";

    private static final String TARGET_SERVICE = "datasource-management";

    private final AgentRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final DatasourceMetadataReadRequestFactory requestFactory;
    private final DatasourceMetadataReadResponseMapper responseMapper;

    public DatasourceMetadataReadToolAdapter(AgentRuntimeProperties properties,
                                             RestClient.Builder restClientBuilder,
                                             DatasourceMetadataReadRequestFactory requestFactory,
                                             DatasourceMetadataReadResponseMapper responseMapper) {
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
        Long datasourceId = resolveDatasourceId(context);
        DatasourceMetadataReadRequest request = requestFactory.build(context);
        try {
            Map<String, Object> response = restClientBuilder
                    .baseUrl(resolveBaseUrl())
                    .build()
                    .post()
                    .uri("/datasources/{id}/metadata/discover", datasourceId)
                    .headers(headers -> applyPlatformHeaders(headers, context))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return responseMapper.toOutcome(datasourceId, response);
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_METADATA_DOWNSTREAM_ERROR",
                    "调用 datasource-management 元数据发现接口失败: " + exception.getMessage());
        }
    }

    /**
     * 解析 datasourceId。
     *
     * <p>优先使用工具绑定时记录的 targetResourceId。
     * 如果绑定时没有指定，则从 Run variables 中读取 datasourceId。
     * 这让工具既支持“绑定时固定资源”，也支持“同一工具按运行变量选择资源”。
     */
    private Long resolveDatasourceId(AgentToolExecutionContext context) {
        if (context.audit().getTargetResourceId() != null) {
            return context.audit().getTargetResourceId();
        }
        Object value = context.variables().get("datasourceId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                "执行 datasource.metadata.read 必须提供 datasourceId");
    }

    /**
     * 透传平台上下文 Header。
     *
     * <p>这里的 Header 是工具执行安全链路的重要组成部分：</p>
     * <p>1. TENANT_ID / ACTOR_ID / ACTOR_ROLE 让下游知道是谁在调用；</p>
     * <p>2. ACTOR_TYPE=SERVICE_ACCOUNT 表示这是 Java 控制面代表 Agent 发出的内部服务调用；</p>
     * <p>3. TRACE_ID 贯穿 gateway、agent-runtime、datasource-management，便于生产排障；</p>
     * <p>4. DATA_SCOPE_LEVEL=PROJECT 与 AUTHORIZED_PROJECT_IDS 把当前会话项目边界传给 datasource-management，
     * 防止 Agent 通过 datasourceId 猜测访问其他项目的数据源。</p>
     */
    private void applyPlatformHeaders(HttpHeaders headers, AgentToolExecutionContext context) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(context.session().getTenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID, context.session().getActorId());
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "AGENT_RUNTIME");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.TRACE_ID, context.traceId() == null ? context.audit().getTraceId() : context.traceId());
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
