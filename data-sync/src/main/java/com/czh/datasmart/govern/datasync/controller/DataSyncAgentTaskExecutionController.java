/**
 * @Author : Cui
 * @Date: 2026/05/31 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentTaskExecutionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteResponse;
import com.czh.datasmart.govern.datasync.service.DataSyncAgentTaskExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * data-sync 面向 Agent worker 的内部执行入口。
 *
 * <p>该 Controller 只负责 HTTP 契约和 traceId 透传，真正的幂等、模板校验、任务创建、入队和状态语义都在 Service 层完成。
 * 生产环境中 `/internal/**` 应由网关内网路由、mTLS、服务账号令牌或服务网格策略保护，不能直接暴露给外部客户端。</p>
 */
@RestController
@RequestMapping("/internal/data-sync/agent/tasks")
@RequiredArgsConstructor
public class DataSyncAgentTaskExecutionController {

    private final DataSyncAgentTaskExecutionService executionService;

    /**
     * 幂等执行 Agent 数据同步工具。
     *
     * @param request Agent 工具执行参数，必须来自 task-management 白名单适配器。
     * @param traceId 网关或上游服务透传的链路追踪 ID。
     * @return 同步任务和执行记录的受控创建/入队结果。
     */
    @PostMapping("/execute")
    public PlatformApiResponse<AgentSyncTaskExecuteResponse> execute(
            @RequestBody AgentSyncTaskExecuteRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        if (request != null && (request.getTraceId() == null || request.getTraceId().isBlank())) {
            request.setTraceId(traceId);
        }
        AgentSyncTaskExecuteResponse response = executionService.executeAgentSyncTask(request);
        return PlatformApiResponse.success("Agent 数据同步工具已提交 data-sync 幂等执行入口", response, traceId);
    }
}
