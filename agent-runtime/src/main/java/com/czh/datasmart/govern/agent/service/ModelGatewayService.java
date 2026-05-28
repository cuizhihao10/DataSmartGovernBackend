/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelGatewayService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.ModelChatRequest;
import com.czh.datasmart.govern.agent.controller.dto.ModelChatResponse;
import com.czh.datasmart.govern.agent.controller.dto.ModelMessage;
import com.czh.datasmart.govern.agent.model.ModelProviderType;
import com.czh.datasmart.govern.agent.model.ModelWorkloadType;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型网关服务。
 *
 * <p>该服务是 Java Agent Runtime 与未来真实模型 Provider 的隔离层。
 * 第一版只提供 DRY_RUN 响应，目的是先固定“模型调用契约 + 模型路由选择 + 可观测字段”，
 * 后续再逐步增加 OpenAI-compatible、vLLM、SGLang、Python Agent Service 等 Provider 实现。
 *
 * <p>为什么先 dry-run？
 * 1. 当前项目还没有 Python AI 服务和模型部署；
 * 2. 贸然在 Java 里写死某个模型 endpoint，会再次把架构锁死；
 * 3. 先把契约打通后，gateway、权限、审计、任务中心可以并行接入，不必等待模型服务真正上线。
 */
@Service
@RequiredArgsConstructor
public class ModelGatewayService {

    private final AgentRuntimeProperties properties;
    private final ModelRouteRegistry routeRegistry;

    /**
     * 执行一次模型聊天/推理请求。
     *
     * <p>当前只实现路由解析和 dry-run 响应。
     * 当 route.providerType 不是 DRY_RUN 时，仍返回明确 warning，避免调用方误以为真实模型已接通。
     */
    public ModelChatResponse chat(ModelChatRequest request) {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }

        ModelWorkloadType workloadType = ModelWorkloadType.fromValue(request.workloadType());
        AgentRuntimeProperties.ModelRouteProperties route = routeRegistry.findRoute(workloadType)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "未配置模型路由，workloadType=" + workloadType.name()));
        if (!Boolean.TRUE.equals(route.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "模型路由未启用，workloadType=" + workloadType.name());
        }

        String promptPreview = summarizeMessages(request.messages());
        String content = "Agent Runtime 模型路由已命中。当前为 DRY_RUN 控制面响应，"
                + "后续会由 Provider 调用真实模型。输入摘要：" + promptPreview;
        String warning = route.getProviderType() == ModelProviderType.DRY_RUN
                ? "当前 Provider 是 DRY_RUN，占位用于验证模型路由和接口契约。"
                : "当前 Provider 类型已配置为 " + route.getProviderType().name() + "，但真实调用适配器尚未实现。";

        return new ModelChatResponse(
                true,
                workloadType.name(),
                route.getProviderName(),
                route.getProviderType().name(),
                route.getModelName(),
                content,
                warning
        );
    }

    /**
     * 生成输入摘要。
     *
     * <p>摘要只用于 dry-run 和调试，不应在真实 Provider 中代替完整上下文。
     * 这里截断长度是为了避免开发期接口回显过长 prompt。
     */
    private String summarizeMessages(List<ModelMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "EMPTY";
        }
        String joined = messages.stream()
                .map(item -> item.role() + ":" + item.content())
                .reduce((left, right) -> left + " | " + right)
                .orElse("EMPTY");
        return joined.length() <= 300 ? joined : joined.substring(0, 300);
    }
}
