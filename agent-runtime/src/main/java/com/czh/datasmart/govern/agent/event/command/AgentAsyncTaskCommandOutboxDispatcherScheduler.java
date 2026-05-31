/**
 * @Author : Cui
 * @Date: 2026/05/31 17:14
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDispatcherScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 异步命令 outbox dispatcher 后台调度适配器。
 *
 * <p>它与真正的 dispatcher 分开，是为了保留手动 dispatch 能力：
 * - dispatcher bean 始终可以被 Controller 或测试调用；
 * - scheduler 只有 dispatcher-enabled=true 时才启动后台轮询；
 * - 即使项目其他模块启用了 {@code @EnableScheduling}，也不会误触发异步命令投递。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-commands.outbox",
        name = "dispatcher-enabled",
        havingValue = "true"
)
public class AgentAsyncTaskCommandOutboxDispatcherScheduler {

    private final AgentAsyncTaskCommandOutboxDispatcher dispatcher;

    @Scheduled(
            fixedDelayString = "${datasmart.agent-runtime.async-task-commands.outbox.dispatcher-fixed-delay-ms:5000}",
            initialDelayString = "${datasmart.agent-runtime.async-task-commands.outbox.dispatcher-initial-delay-ms:10000}"
    )
    public void dispatchScheduled() {
        dispatcher.dispatchOnce();
    }
}
