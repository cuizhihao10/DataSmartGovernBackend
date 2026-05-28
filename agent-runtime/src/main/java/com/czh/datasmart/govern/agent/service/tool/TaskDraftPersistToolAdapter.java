/**
 * @Author : Cui
 * @Date: 2026/05/24 23:59
 * @Description DataSmart Govern Backend - TaskDraftPersistToolAdapter.java
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
 * `task.draft.persist` 工具适配器。
 *
 * <p>该工具是 Agent Runtime 与 task-management 草稿生命周期的第一条受控写入链路。
 * 它不会创建真实 `task`，只调用 `/task-drafts` 保存草稿，因此不会让任务进入执行器队列。</p>
 *
 * <p>商业化安全边界：</p>
 * <p>1. `task.create.draft` 保持无副作用，只生成本地结构化草稿；</p>
 * <p>2. `task.draft.persist` 是写操作，工具目录应配置为 HIGH 风险并要求审批或明确确认；</p>
 * <p>3. 本工具只保存 DRAFT，不自动 submit/approve/convert；</p>
 * <p>4. 真正执行前仍必须经过 task-management 的审批状态机和 permission-admin 的动作级权限。</p>
 */
@Component
public class TaskDraftPersistToolAdapter implements AgentToolAdapter {

    public static final String TOOL_CODE = "task.draft.persist";
    private static final String TARGET_SERVICE = "task-management";

    private final AgentRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final TaskDraftPersistRequestFactory requestFactory;
    private final TaskDraftPersistResponseMapper responseMapper;

    public TaskDraftPersistToolAdapter(AgentRuntimeProperties properties,
                                       RestClient.Builder restClientBuilder,
                                       TaskDraftPersistRequestFactory requestFactory,
                                       TaskDraftPersistResponseMapper responseMapper) {
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
        TaskDraftPersistRequest request = requestFactory.build(context);
        try {
            Map<String, Object> response = restClientBuilder
                    .baseUrl(resolveBaseUrl())
                    .build()
                    .post()
                    .uri("/task-drafts")
                    .headers(headers -> applyPlatformHeaders(headers, context))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return responseMapper.toOutcome(request, response);
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("TASK_DRAFT_PERSIST_DOWNSTREAM_ERROR",
                    "调用 task-management 保存任务草稿失败: " + exception.getMessage());
        }
    }

    /**
     * 透传平台上下文 Header。
     *
     * <p>task-management 不直接读取 Agent Session，它只信任 gateway/上游服务透传的平台 Header。
     * 因此保存草稿时必须携带租户、项目数据范围、来源服务和 traceId。</p>
     *
     * <p>这里把 ACTOR_ROLE 设置为 SERVICE_ACCOUNT，而不是普通用户角色。
     * 这样 task-management 可以识别这是 Agent Runtime 代表用户发起的内部受控调用；
     * 真实用户身份仍通过草稿 sourceRef、工具审计和 traceId 追溯。后续接入完整审批策略后，
     * 可以再把“发起人”和“服务账号”拆成两个独立 Header。</p>
     */
    private void applyPlatformHeaders(HttpHeaders headers, AgentToolExecutionContext context) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(context.session().getTenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.TRACE_ID,
                context.traceId() == null ? context.audit().getTraceId() : context.traceId());
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, String.valueOf(context.session().getProjectId()));

        String actorId = numericActorId(context.session().getActorId());
        if (actorId != null) {
            headers.set(PlatformContextHeaders.ACTOR_ID, actorId);
        }
    }

    /**
     * 将会话 actorId 转换为 task-management 可解析的数字 Header。
     *
     * <p>当前 agent-runtime 的会话 actorId 是字符串，测试和未来网关可能传 `u-001` 这类外部用户标识。
     * 但 task-management 现阶段的 `TaskActorContextResolver` 将 Header 解析为 Long。
     * 为避免写入链路因为格式差异失败，这里只在 actorId 本身是数字时透传；否则省略 Header，
     * 让下游按服务账号和请求体 ownerId 规则处理。该兼容点后续应通过统一身份模型收口。</p>
     */
    private String numericActorId(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return null;
        }
        String text = actorId.trim();
        try {
            Long.parseLong(text);
            return text;
        } catch (NumberFormatException ignored) {
            return null;
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
}
