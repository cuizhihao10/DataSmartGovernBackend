/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelGatewayController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.ModelChatRequest;
import com.czh.datasmart.govern.agent.controller.dto.ModelChatResponse;
import com.czh.datasmart.govern.agent.controller.dto.ModelRouteView;
import com.czh.datasmart.govern.agent.service.ModelGatewayService;
import com.czh.datasmart.govern.agent.service.ModelRouteRegistry;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型网关控制器。
 *
 * <p>该控制器是类 OpenClaw 智能网关的第一块砖：
 * 它不直接管理业务数据，也不直接执行 Java 任务，而是提供“模型路由与调用契约”的统一入口。
 * 后续 Agent 编排器会在这里之上继续扩展会话、工作区、工具调用、任务回调和流式输出。
 */
@RestController
@RequestMapping({"/agent-runtime/models", "/api/agent/models"})
@RequiredArgsConstructor
public class ModelGatewayController {

    private final ModelRouteRegistry routeRegistry;
    private final ModelGatewayService modelGatewayService;

    /**
     * 查看当前模型路由表。
     *
     * <p>该接口用于管理后台和排障。
     * 当某个 Agent 回答质量异常时，运维人员可以先查看该工作负载实际命中了哪个模型路由。
     */
    @GetMapping("/routes")
    public PlatformApiResponse<List<ModelRouteView>> listRoutes(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(routeRegistry.listRoutes(), traceId);
    }

    /**
     * 发起一次模型聊天/推理请求。
     *
     * <p>当前版本返回 dry-run 响应，主要用于验证调用契约、路由配置和 gateway 转发。
     * 等 Python AI 服务或 vLLM/SGLang 服务接入后，该接口会保持外部契约稳定，内部替换 Provider 实现。
     */
    @PostMapping("/chat")
    public PlatformApiResponse<ModelChatResponse> chat(
            @Valid @RequestBody ModelChatRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("模型路由 dry-run 执行完成", modelGatewayService.chat(request), traceId);
    }
}
