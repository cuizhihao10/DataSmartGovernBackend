/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 最终态 callback worker 的 Spring 调度适配器。
 *
 * <p>调度器只负责触发，不持有状态机和网络逻辑；真正的去重、visibility lease、重试和补偿均在 worker/job store 中。
 * fixedDelay 语义保证同 JVM 内上一轮结束后才开始下一轮，跨实例冲突仍由数据库条件领取处理。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-final-state-callback-worker",
        name = "enabled",
        havingValue = "true"
)
public class AgentCommandTaskFinalStateCallbackWorkerScheduler {

    private final AgentCommandTaskFinalStateCallbackWorker worker;

    /**
     * 触发一轮后台收敛；worker 自身会处理本 JVM 防重入和跨实例 visibility lease。
     */
    @Scheduled(
            fixedDelayString = "${datasmart.agent-runtime.async-task-final-state-callback-worker.fixed-delay-ms:5000}",
            initialDelayString = "${datasmart.agent-runtime.async-task-final-state-callback-worker.initial-delay-ms:15000}"
    )
    public void runScheduled() {
        worker.runOnce();
    }
}
