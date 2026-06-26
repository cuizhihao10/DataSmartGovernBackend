/**
 * @Author : Cui
 * @Date: 2026/06/26 23:12
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionArtifactObjectStoreProbeService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * artifact 对象存储探针控制器。
 *
 * <p>该 Controller 从原有 `AgentToolActionArtifactAccessController` 中拆出，是为了避免一个 Controller
 * 同时承载 metadata 预授权、body-read grant、final-check 和对象存储适配四类职责。对象存储探针未来会接 MinIO、
 * S3-compatible storage、内部 artifact store 或冷归档服务，单独拆分后更容易替换 adapter，也更容易控制文件行数。</p>
 *
 * <p>当前路由仍不是文件下载 API。它只面向平台内部服务，用来验证对象存储 adapter 是否能在服务端安全读取少量 sample。
 * 响应不会返回 sample 正文、完整 artifact body、stdout/stderr、签名 URL、bucket/key、真实 endpoint、prompt、SQL、
 * 工具参数或模型输出。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-artifacts", "/api/agent/tool-action-artifacts"})
@RequiredArgsConstructor
public class AgentToolActionArtifactObjectStoreProbeController {

    private final AgentToolActionArtifactObjectStoreProbeService objectStoreProbeService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 执行 artifact 对象存储服务端探针。
     *
     * <p>使用 POST 是因为请求中包含 commandId、artifactReference、grant 引用、run/session 等访问上下文。
     * 这些字段虽然属于低敏引用，但不适合进入 URL、代理缓存键或普通访问日志。Controller 只负责把可信 Header
     * 转换为 access context，再把请求交给 Service；真正的 grant 复核、sample 字节上限、adapter 调用和低敏响应组装
     * 都在 Service 中完成。</p>
     *
     * @param request 对象存储探针请求。
     * @param traceId gateway 透传的链路追踪 ID。
     * @param currentTenantId 当前调用方租户 ID。
     * @param currentActorId 当前调用方 actor ID。
     * @param currentActorRole 当前调用方角色。
     * @param dataScopeLevel permission-admin 判定的数据范围。
     * @param authorizedProjectIds PROJECT 范围下的授权项目集合。
     * @return 对象可用性、sample 指纹和低敏审计证据；不包含对象正文或下载凭据。
     */
    @PostMapping("/object-store-probes")
    public PlatformApiResponse<AgentToolActionArtifactObjectStoreProbeResponse> probeObjectStore(
            @RequestBody AgentToolActionArtifactObjectStoreProbeRequest request,
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
        AgentToolActionArtifactObjectStoreProbeResponse response =
                objectStoreProbeService.probe(request, accessContext);
        return PlatformApiResponse.success(response, traceId);
    }
}
