/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionResumeFactBundleService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具动作恢复事实包控制器。
 *
 * <p>本控制器是 Python AI Runtime resume-preview 走向商业化控制面的关键入口。
 * 调用方通过 POST `/bundles/query` 提交 checkpoint/thread/run/command 等低敏定位符，
 * agent-runtime 返回服务端采信的事实类型集合，例如审批事实、outbox 写入事实、worker receipt 投影事实。</p>
 *
 * <p>路由为什么放在独立 Controller：
 * 1. 它不是普通 runtime event 查询，也不是 command outbox writer；
 * 2. 它聚合多个事实源，后续还会接 durable checkpoint store、clarification store 和审计事件；
 * 3. 独立控制器能避免通用投影 Controller 行数继续膨胀，也让权限、注释和测试边界更清楚。</p>
 *
 * <p>安全约束：
 * 该接口只做 preview/query，不执行工具、不写 outbox、不派发 worker、不修改 checkpoint。
 * 响应不会回显 approvalFactId、payloadJson、payloadReference、targetEndpoint、lastError、prompt、SQL 或工具参数。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-resume-facts", "/api/agent/tool-action-resume-facts"})
@RequiredArgsConstructor
public class AgentToolActionResumeFactBundleController {

    private final AgentToolActionResumeFactBundleService bundleService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询工具动作恢复事实包。
     *
     * <p>POST 而不是 GET 的原因：
     * 1. 请求条件包含多个定位字段，GET query string 容易变长且不便表达 requiredFactTypes；
     * 2. approvalFactId 虽然不回显，但仍不适合放在 URL 中，避免被代理、浏览器历史或访问日志记录；
     * 3. 该接口语义是“执行一次控制面事实评估”，而不是简单读取一个静态资源。</p>
     *
     * <p>Header 数据范围会被解析为 {@link AgentRuntimeEventQueryAccessContext}，
     * 服务层再把 body 中的 tenant/project/actor 与 Header 范围求交集。
     * 调用方不能通过 body 参数扩大自己可见的事实范围。</p>
     */
    @PostMapping("/bundles/query")
    public PlatformApiResponse<AgentToolActionResumeFactBundleResponse> query(
            @RequestBody(required = false) AgentToolActionResumeFactBundleQueryRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(bundleService.query(request, accessContext), traceId);
    }
}
