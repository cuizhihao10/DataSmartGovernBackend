/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterResponse;
import com.czh.datasmart.govern.permission.service.GraphFactApprovalService;
import com.czh.datasmart.govern.permission.service.support.AgentApprovalFactTrustedRegistrationGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 图事实审批控制器。
 *
 * <p>登记接口只允许受信内部服务调用；评估接口供图摄取 consumer 回查。两条路由都不接收
 * 图实体正文，避免把知识内容误当成权限控制面 payload。</p>
 */
@RestController
@RequestMapping({"/permissions/agent/graph-facts", "/api/permission/agent/graph-facts"})
@RequiredArgsConstructor
public class GraphFactApprovalController {

    private final GraphFactApprovalService graphFactApprovalService;
    private final AgentApprovalFactTrustedRegistrationGuard trustedRegistrationGuard;

    /** 登记图事实候选或审批决定。 */
    @PostMapping("/approvals")
    public PlatformApiResponse<GraphFactApprovalRegisterResponse> register(
            @Valid @RequestBody GraphFactApprovalRegisterRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false) String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        trustedRegistrationGuard.requireTrusted(sourceService, internalToken);
        trustedRegistrationGuard.requireDecisionAuthority(sourceService, request == null ? null : request.getStatus());
        return PlatformApiResponse.success("图事实审批事实已登记",
                graphFactApprovalService.register(request), traceId);
    }

    /** 图摄取 consumer 在真正写 Neo4j 前回查审批事实。 */
    @PostMapping("/evaluate")
    public PlatformApiResponse<AgentToolActionApprovalFactEvaluationView> evaluate(
            @RequestBody GraphFactApprovalEvaluateRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false) String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        // 摄取 worker 的 evaluate 回查同样属于内部控制面，不应允许普通浏览器伪造审批事实绑定。
        // 登记接口负责“谁能写入”，这里负责“谁能读取用于执行前授权”；两边都要求来源服务和共享令牌，
        // 才能保证 Kafka 消息不能被外部客户端直接变成 Neo4j 写权限。
        trustedRegistrationGuard.requireTrusted(sourceService, internalToken);
        return PlatformApiResponse.success("图事实审批评估完成",
                graphFactApprovalService.evaluate(request), traceId);
    }
}
