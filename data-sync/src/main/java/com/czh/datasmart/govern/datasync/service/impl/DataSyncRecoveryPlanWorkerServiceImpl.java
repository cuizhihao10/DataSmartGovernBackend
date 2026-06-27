/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - DataSyncRecoveryPlanWorkerServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncRecoveryPlanWorkerService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncRecoveryPlanState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * worker 恢复计划消费服务实现。
 *
 * <p>本类补齐 replay/backfill 的执行面闭环：控制面已经可以创建恢复计划和新的 QUEUED execution，
 * 但如果 worker 没有稳定的读取/消费协议，那么 replay/backfill 仍然只是“管理端记录”，没有真正进入执行链路。
 *
 * <p>设计边界：
 * 1. 本服务只给已经持有 RUNNING execution 租约的 worker 使用；
 * 2. 本服务只返回低敏恢复坐标，不返回 SQL、连接串、凭据、样本数据、完整错误堆栈或任意业务 payload；
 * 3. 本服务只推进恢复计划状态，不执行真实数据迁移；真实数据读取写入仍由 worker connector 和生命周期回调负责；
 * 4. 状态推进采用数据库条件更新，避免 worker 重试或多实例并发造成计划状态倒退。
 */
@Service
@RequiredArgsConstructor
public class DataSyncRecoveryPlanWorkerServiceImpl implements DataSyncRecoveryPlanWorkerService {

    private final SyncExecutionMapper executionMapper;
    private final SyncExecutionRecoveryPlanMapper recoveryPlanMapper;
    private final SyncAuditSupport auditSupport;

    /**
     * worker 读取并认领恢复计划。
     *
     * <p>典型调用顺序：
     * 1. worker 调用 /sync-executions/claim 认领一条 execution；
     * 2. 如果 execution.triggerType 是 REPLAY/BACKFILL，worker 调用本方法读取恢复计划；
     * 3. 服务端把计划从 CREATED 推进到 CLAIMED，并返回低敏恢复契约；
     * 4. worker 根据契约初始化 checkpoint、窗口和分片策略。
     *
     * <p>幂等规则：
     * 1. CREATED -> CLAIMED：首次读取，写审计；
     * 2. CLAIMED -> CLAIMED：重复读取，视为幂等成功；
     * 3. CONSUMED -> CONSUMED：说明 worker 已消费计划，再次 claim 只返回当前状态；
     * 4. CANCELLED 或未知状态：拒绝，避免执行已撤销或不可解释的计划。
     */
    @Override
    @Transactional
    public SyncRecoveryPlanWorkerResult claimPlan(Long executionId,
                                                  SyncRecoveryPlanWorkerRequest request,
                                                  SyncActorContext actorContext) {
        SyncExecution execution = requireRunningOwnedExecution(executionId, request, "读取恢复计划");
        SyncExecutionRecoveryPlan plan = recoveryPlanMapper.selectByExecutionId(executionId);
        if (plan == null) {
            return SyncRecoveryPlanWorkerResult.noPlan(execution,
                    "当前 execution 未绑定 replay/backfill 恢复计划，worker 可按普通同步路径继续执行");
        }
        validatePlanBelongsToExecution(plan, execution);
        SyncExecutionRecoveryPlan latest = claimByCurrentState(plan, request, actorContext);
        return SyncRecoveryPlanWorkerResult.fromPlan(latest,
                "恢复计划已提供给 worker，可据此初始化 replay/backfill 执行上下文");
    }

    /**
     * worker 确认已经消费恢复计划。
     *
     * <p>consume 不是“执行完成”，而是“计划已经被 worker 用作本地执行输入”。
     * 之后 worker 仍要通过 start、checkpoint、complete、fail 等已有回调汇报真实执行过程。
     *
     * <p>为什么要求先 claim 再 consume：
     * 1. claim 记录“worker 已看到计划”，consume 记录“worker 已应用计划”，两个证据粒度不同；
     * 2. 事故复盘时可以区分计划发送失败、worker 加载失败、真实数据执行失败；
     * 3. 未来接入 worker SDK 后，可以在 claim 与 consume 之间增加本地预检、容量估算或 connector capability 检查。
     */
    @Override
    @Transactional
    public SyncRecoveryPlanWorkerResult consumePlan(Long executionId,
                                                    SyncRecoveryPlanWorkerRequest request,
                                                    SyncActorContext actorContext) {
        SyncExecution execution = requireRunningOwnedExecution(executionId, request, "消费恢复计划");
        SyncExecutionRecoveryPlan plan = recoveryPlanMapper.selectByExecutionId(executionId);
        if (plan == null) {
            return SyncRecoveryPlanWorkerResult.noPlan(execution,
                    "当前 execution 未绑定恢复计划，无需执行 replay/backfill 消费确认");
        }
        validatePlanBelongsToExecution(plan, execution);
        SyncExecutionRecoveryPlan latest = consumeByCurrentState(plan, request, actorContext);
        return SyncRecoveryPlanWorkerResult.fromPlan(latest,
                "恢复计划已被 worker 消费，后续可进入普通 checkpoint、complete 或 fail 回调链路");
    }

    /**
     * 加载并校验 execution 仍由当前 worker 持有。
     *
     * <p>恢复计划接口不能只校验 HMAC 服务账号，因为同一个服务账号可能代表一个 worker 集群。
     * 只有 request.executorId 与 execution.executorId 匹配时，才能证明“当前调用者就是认领该 execution 的执行器实例”。
     */
    private SyncExecution requireRunningOwnedExecution(Long executionId,
                                                       SyncRecoveryPlanWorkerRequest request,
                                                       String operationName) {
        if (executionId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "executionId 不能为空");
        }
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步执行记录不存在: " + executionId);
        }
        if (!SyncExecutionState.RUNNING.name().equals(execution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 RUNNING 状态的 execution 才能" + operationName + "，当前状态=" + execution.getExecutionState());
        }
        String executorId = request == null ? null : request.getExecutorId();
        if (executorId == null || executorId.isBlank() || !executorId.trim().equals(execution.getExecutorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能" + operationName + "，请确认 worker 是否仍持有当前 execution 租约");
        }
        return execution;
    }

    /**
     * 校验恢复计划与 execution 的租户、项目、空间和任务范围一致。
     *
     * <p>按 executionId 查询理论上应该唯一命中当前计划，但商业化系统不能只相信单一外键。
     * 如果由于历史数据、人工修复或脚本错误导致计划与 execution 范围不一致，这里必须 fail-closed，
     * 否则 worker 可能在错误租户或错误项目上下文中执行回放/补数。
     */
    private void validatePlanBelongsToExecution(SyncExecutionRecoveryPlan plan, SyncExecution execution) {
        boolean matched = Objects.equals(plan.getExecutionId(), execution.getId())
                && Objects.equals(plan.getSyncTaskId(), execution.getSyncTaskId())
                && Objects.equals(plan.getTenantId(), execution.getTenantId())
                && Objects.equals(plan.getProjectId(), execution.getProjectId())
                && Objects.equals(plan.getWorkspaceId(), execution.getWorkspaceId());
        if (!matched) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "恢复计划与当前 execution 的租户、项目、空间或任务范围不一致，已拒绝 worker 消费");
        }
    }

    private SyncExecutionRecoveryPlan claimByCurrentState(SyncExecutionRecoveryPlan plan,
                                                          SyncRecoveryPlanWorkerRequest request,
                                                          SyncActorContext actorContext) {
        SyncRecoveryPlanState state = parseState(plan.getPlanState());
        if (state == SyncRecoveryPlanState.CREATED) {
            return transitionPlanState(
                    plan,
                    SyncRecoveryPlanState.CREATED,
                    SyncRecoveryPlanState.CLAIMED,
                    request,
                    actorContext,
                    SyncAuditActionType.CLAIM_RECOVERY_PLAN,
                    "恢复计划已被 worker 认领");
        }
        if (state == SyncRecoveryPlanState.CLAIMED || state == SyncRecoveryPlanState.CONSUMED) {
            return plan;
        }
        throw illegalState(plan, "当前恢复计划已取消或处于未知状态，不能继续认领");
    }

    private SyncExecutionRecoveryPlan consumeByCurrentState(SyncExecutionRecoveryPlan plan,
                                                            SyncRecoveryPlanWorkerRequest request,
                                                            SyncActorContext actorContext) {
        SyncRecoveryPlanState state = parseState(plan.getPlanState());
        if (state == SyncRecoveryPlanState.CREATED) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "恢复计划必须先由 worker claim，再执行 consume，避免跳过计划已送达审计证据");
        }
        if (state == SyncRecoveryPlanState.CLAIMED) {
            return transitionPlanState(
                    plan,
                    SyncRecoveryPlanState.CLAIMED,
                    SyncRecoveryPlanState.CONSUMED,
                    request,
                    actorContext,
                    SyncAuditActionType.CONSUME_RECOVERY_PLAN,
                    "恢复计划已被 worker 消费");
        }
        if (state == SyncRecoveryPlanState.CONSUMED) {
            return plan;
        }
        throw illegalState(plan, "当前恢复计划已取消或处于未知状态，不能继续消费");
    }

    /**
     * 使用 expectedState 条件推进恢复计划状态。
     *
     * <p>如果数据库更新返回 0，不立刻失败，而是重新读取计划状态：
     * 1. 如果状态已经等于 targetState，说明其它并发请求先完成了推进，本次按幂等成功处理；
     * 2. 如果状态已经进一步变成 CONSUMED，claim 请求也可以按幂等成功处理；
     * 3. 如果状态停留在其它不可接受状态，则说明发生了真实状态冲突，需要拒绝。
     */
    private SyncExecutionRecoveryPlan transitionPlanState(SyncExecutionRecoveryPlan plan,
                                                          SyncRecoveryPlanState expectedState,
                                                          SyncRecoveryPlanState targetState,
                                                          SyncRecoveryPlanWorkerRequest request,
                                                          SyncActorContext actorContext,
                                                          SyncAuditActionType auditActionType,
                                                          String auditMessage) {
        int updated = recoveryPlanMapper.markPlanState(plan.getExecutionId(), expectedState.name(), targetState.name());
        SyncExecutionRecoveryPlan latest = requirePlan(plan.getExecutionId());
        SyncRecoveryPlanState latestState = parseState(latest.getPlanState());
        if (updated == 1) {
            auditTransition(latest, request, actorContext, auditActionType, auditMessage);
            return latest;
        }
        if (latestState == targetState || latestState == SyncRecoveryPlanState.CONSUMED) {
            return latest;
        }
        throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                "恢复计划状态已被其它流程变更，期望=" + expectedState + "，目标=" + targetState + "，当前=" + latestState);
    }

    private SyncExecutionRecoveryPlan requirePlan(Long executionId) {
        SyncExecutionRecoveryPlan latest = recoveryPlanMapper.selectByExecutionId(executionId);
        if (latest == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "恢复计划在状态推进后无法重新读取，executionId=" + executionId);
        }
        return latest;
    }

    private SyncRecoveryPlanState parseState(String planState) {
        try {
            return SyncRecoveryPlanState.valueOf(planState);
        } catch (Exception exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未知恢复计划状态: " + planState);
        }
    }

    private PlatformBusinessException illegalState(SyncExecutionRecoveryPlan plan, String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                message + "，recoveryPlanId=" + plan.getId() + "，planState=" + plan.getPlanState());
    }

    /**
     * 写入恢复计划状态推进审计。
     *
     * <p>审计 payload 只记录低敏元信息：planId、recoveryType、executionId、executorId、planState。
     * 不记录 reason 原文、windowStart/windowEnd/shardOrPartition、checkpoint 内容、SQL、连接配置或样本数据。
     */
    private void auditTransition(SyncExecutionRecoveryPlan plan,
                                 SyncRecoveryPlanWorkerRequest request,
                                 SyncActorContext actorContext,
                                 SyncAuditActionType auditActionType,
                                 String auditMessage) {
        String executorId = request == null ? null : request.getExecutorId();
        auditSupport.saveAudit(plan.getTenantId(), plan.getSyncTaskId(), plan.getExecutionId(), auditActionType,
                actorContext, "recoveryPlanId=" + plan.getId()
                        + ",recoveryType=" + plan.getRecoveryType()
                        + ",executionId=" + plan.getExecutionId()
                        + ",executorId=" + executorId
                        + ",planState=" + plan.getPlanState()
                        + ",message=" + auditMessage);
    }
}
