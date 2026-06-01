/**
 * @Author : Cui
 * @Date: 2026/06/01 22:46
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxCapacityGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandPlanItemView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 异步命令 outbox 入箱前容量保护。
 *
 * <p>该组件解决的是“确认动作过快，执行系统跟不上”的商业化风险。
 * 4.72 已经让 selected-node confirmation 可审计，但可审计不等于可无限制执行：
 * 如果某个租户的 outbox 已经堆积大量待投递 command，继续允许 Agent 批量确认更多节点，
 * 会把压力传导到 dispatcher、Kafka、task-management、data-sync、data-quality 等下游模块。</p>
 *
 * <p>本组件只做第一阶段的本地保护，故意不引入 Redis 分布式令牌桶或复杂 quota-center：
 * 1. 单次请求限制 {@code maxCommandsPerEnqueue}，防止一次确认推进过多后台任务；
 * 2. 单 run 活跃积压限制 {@code maxActiveCommandsPerRun}，防止某个 Agent Run 无限循环入箱；
 * 3. 单租户活跃积压限制 {@code maxActiveCommandsPerTenant}，防止一个租户占满平台执行能力。</p>
 *
 * <p>活跃积压只统计 PENDING、PUBLISHING、FAILED。PUBLISHED 已经交给下游，BLOCKED/IGNORED 已进入人工治理，
 * 不再代表自动投递压力。后续如果进入多网关、多 agent-runtime 实例部署，应把这套语义升级到 Redis/DB 原子计数、
 * 租户套餐配额、工具级并发池和 worker 侧租约控制。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandOutboxCapacityGuard {

    private static final Set<AgentAsyncTaskCommandOutboxStatus> ACTIVE_BACKLOG_STATUSES = EnumSet.of(
            AgentAsyncTaskCommandOutboxStatus.PENDING,
            AgentAsyncTaskCommandOutboxStatus.PUBLISHING,
            AgentAsyncTaskCommandOutboxStatus.FAILED
    );

    private final AgentAsyncTaskCommandOutboxProperties properties;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;

    /**
     * 在写入 outbox 之前检查容量与积压。
     *
     * <p>该方法必须在 append 之前调用，因为 outbox append 一旦成功，就已经形成“待投递命令”事实。
     * 如果容量保护放在 append 之后，只能做补偿或阻断，已经无法避免命令进入待处理队列。</p>
     *
     * @param runId 当前 Agent Run ID，用于 run 级 backlog 统计
     * @param candidateItems 本次准备入箱的 command plan 候选，只统计 dispatchable=true 的项
     */
    public void assertCanEnqueue(String runId, List<AgentAsyncTaskCommandPlanItemView> candidateItems) {
        if (!properties.isCapacityProtectionEnabled()) {
            return;
        }
        List<AgentAsyncTaskCommandPlanItemView> dispatchableItems = candidateItems == null
                ? List.of()
                : candidateItems.stream()
                .filter(item -> Boolean.TRUE.equals(item.dispatchable()))
                .toList();
        if (dispatchableItems.isEmpty()) {
            return;
        }
        int incomingCommands = dispatchableItems.size();
        Long tenantId = resolveSingleTenantId(dispatchableItems);
        assertSingleRequestLimit(incomingCommands);
        assertRunBacklogLimit(runId, incomingCommands);
        assertTenantBacklogLimit(tenantId, incomingCommands);
    }

    private void assertSingleRequestLimit(int incomingCommands) {
        int maxCommandsPerEnqueue = Math.max(1, properties.getMaxCommandsPerEnqueue());
        if (incomingCommands > maxCommandsPerEnqueue) {
            throw new PlatformBusinessException(PlatformErrorCode.RATE_LIMITED,
                    "本次 Agent 异步命令入箱数量超过单次上限，incoming=" + incomingCommands
                            + ", maxCommandsPerEnqueue=" + maxCommandsPerEnqueue
                            + "；请缩小 selected-node 批次或等待后台任务消化后再确认");
        }
    }

    private void assertRunBacklogLimit(String runId, int incomingCommands) {
        int maxActiveCommandsPerRun = Math.max(1, properties.getMaxActiveCommandsPerRun());
        long activeBacklog = outboxStore.countByRunAndStatuses(runId, ACTIVE_BACKLOG_STATUSES);
        if (activeBacklog + incomingCommands > maxActiveCommandsPerRun) {
            throw new PlatformBusinessException(PlatformErrorCode.RATE_LIMITED,
                    "当前 Agent Run 的异步命令积压已接近上限，activeBacklog=" + activeBacklog
                            + ", incoming=" + incomingCommands
                            + ", maxActiveCommandsPerRun=" + maxActiveCommandsPerRun
                            + "；请先等待 dispatcher/task-management 消化积压");
        }
    }

    private void assertTenantBacklogLimit(Long tenantId, int incomingCommands) {
        if (tenantId == null) {
            /*
             * 旧数据或本地测试可能缺少 tenantId。这里不直接阻断，是为了兼容早期计划数据；
             * 但生产工具计划应始终带 tenantId，否则无法做真正的多租户公平调度。
             */
            return;
        }
        int maxActiveCommandsPerTenant = Math.max(1, properties.getMaxActiveCommandsPerTenant());
        long activeBacklog = outboxStore.countByTenantAndStatuses(tenantId, ACTIVE_BACKLOG_STATUSES);
        if (activeBacklog + incomingCommands > maxActiveCommandsPerTenant) {
            throw new PlatformBusinessException(PlatformErrorCode.RATE_LIMITED,
                    "当前租户的 Agent 异步命令积压已接近上限，tenantId=" + tenantId
                            + ", activeBacklog=" + activeBacklog
                            + ", incoming=" + incomingCommands
                            + ", maxActiveCommandsPerTenant=" + maxActiveCommandsPerTenant
                            + "；请等待后台 worker 消化，或由运营人员扩容/调整配额");
        }
    }

    private Long resolveSingleTenantId(List<AgentAsyncTaskCommandPlanItemView> dispatchableItems) {
        List<Long> tenantIds = dispatchableItems.stream()
                .map(AgentAsyncTaskCommandPlanItemView::tenantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (tenantIds.size() <= 1) {
            return tenantIds.isEmpty() ? null : tenantIds.getFirst();
        }
        throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                "一次 Agent 异步命令入箱不能跨多个租户，tenantIds=" + tenantIds);
    }
}
