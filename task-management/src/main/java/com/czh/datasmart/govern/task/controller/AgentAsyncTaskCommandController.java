/**
 * @Author : Cui
 * @Date: 2026/05/31 16:45
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandConsumeResponse;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandRequest;
import com.czh.datasmart.govern.task.service.AgentAsyncTaskCommandConsumerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 异步工具命令内部联调控制器。
 *
 * <p>该接口不是面向浏览器或普通用户的公开 API，而是消费侧契约的内部适配器。
 * 当前先提供 HTTP 入口，便于 agent-runtime dispatcher 尚未接 Kafka 时进行联调和回归测试；
 * 后续新增 Kafka listener 时，listener 应复用 AgentAsyncTaskCommandConsumerService，不复制业务逻辑。</p>
 *
 * <p>生产部署要求：</p>
 * <p>1. gateway 不应把 `/internal/**` 暴露给普通用户；</p>
 * <p>2. 服务间调用应增加 mTLS、内部网络、服务账号令牌或签名校验；</p>
 * <p>3. 真正的自动化主路径应切换到 Kafka + Inbox，而不是长期依赖同步 HTTP。</p>
 */
@RestController
@RequestMapping("/internal/agent-async-task-commands")
@RequiredArgsConstructor
public class AgentAsyncTaskCommandController {

    private final AgentAsyncTaskCommandConsumerService consumerService;

    /**
     * 消费一条 Agent 异步工具命令。
     *
     * <p>重复 command 会返回 duplicate=true 和首次 taskId，不会重复创建任务。</p>
     */
    @PostMapping("/consume")
    public ResponseEntity<ApiResponse<AgentAsyncTaskCommandConsumeResponse>> consume(
            @Valid @RequestBody AgentAsyncTaskCommandRequest request) {
        AgentAsyncTaskCommandConsumeResponse response = consumerService.consume(request);
        return ResponseEntity.ok(ApiResponse.success("Agent 异步工具命令消费完成", response));
    }
}
