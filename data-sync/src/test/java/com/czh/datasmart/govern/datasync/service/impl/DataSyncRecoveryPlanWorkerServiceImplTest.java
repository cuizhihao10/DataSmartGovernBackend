/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - DataSyncRecoveryPlanWorkerServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncRecoveryPlanState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * worker 恢复计划消费服务测试。
 *
 * <p>这组测试关注的不是 replay/backfill 计划如何创建，而是计划创建后 worker 如何安全读取和消费。
 * 该协议是 data-sync 从“控制面能发起恢复”走向“执行面能真正恢复”的关键闭环。
 *
 * <p>测试覆盖的核心商业规则：
 * 1. 只有持有 execution 租约的 worker 才能读取计划；
 * 2. CREATED 计划被首次读取后推进到 CLAIMED，并写入低敏审计；
 * 3. 普通 execution 没有恢复计划时返回 hasRecoveryPlan=false，便于 worker 统一调用；
 * 4. consume 必须发生在 claim 之后，防止丢失“计划已送达”的审计证据；
 * 5. 自由文本字段即使历史上误写了 SQL/凭据类内容，返回给 worker 前也要兜底脱敏。
 */
class DataSyncRecoveryPlanWorkerServiceImplTest {

    /**
     * CREATED 计划被 worker claim 后应推进为 CLAIMED。
     *
     * <p>这是 replay/backfill 执行面最重要的正向路径：worker 已经认领 execution，
     * 随后读取恢复计划，服务端通过条件更新把计划状态推进到 CLAIMED，并记录一条审计证据。
     */
    @Test
    void claimCreatedPlanShouldMarkClaimedAndReturnPlan() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-1");
        SyncExecutionRecoveryPlan created = recoveryPlan(SyncRecoveryPlanState.CREATED);
        SyncExecutionRecoveryPlan claimed = recoveryPlan(SyncRecoveryPlanState.CLAIMED);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.recoveryPlanMapper().selectByExecutionId(88L)).thenReturn(created, claimed);
        when(fixture.recoveryPlanMapper().markPlanState(88L,
                SyncRecoveryPlanState.CREATED.name(), SyncRecoveryPlanState.CLAIMED.name())).thenReturn(1);

        SyncRecoveryPlanWorkerResult result = fixture.service().claimPlan(88L, request("worker-1"), actor());

        assertThat(result.hasRecoveryPlan()).isTrue();
        assertThat(result.recoveryPlanId()).isEqualTo(9001L);
        assertThat(result.recoveryType()).isEqualTo("REPLAY");
        assertThat(result.planState()).isEqualTo(SyncRecoveryPlanState.CLAIMED.name());
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L),
                eq(SyncAuditActionType.CLAIM_RECOVERY_PLAN), eq(actor()), contains("recoveryPlanId=9001"));
    }

    /**
     * 普通 execution 没有恢复计划时应返回 hasRecoveryPlan=false。
     *
     * <p>真实 worker SDK 可以在每次 claim 后统一调用恢复计划 claim 接口。
     * 如果当前 execution 不是 replay/backfill，服务端不应抛错，而应明确告诉 worker 按普通同步路径继续。
     */
    @Test
    void claimNormalExecutionShouldReturnNoPlan() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-1");
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.recoveryPlanMapper().selectByExecutionId(88L)).thenReturn(null);

        SyncRecoveryPlanWorkerResult result = fixture.service().claimPlan(88L, request("worker-1"), actor());

        assertThat(result.hasRecoveryPlan()).isFalse();
        assertThat(result.executionId()).isEqualTo(88L);
        assertThat(result.syncTaskId()).isEqualTo(1L);
        verify(fixture.recoveryPlanMapper(), never()).markPlanState(eq(88L), eq("CREATED"), eq("CLAIMED"));
        verify(fixture.auditSupport(), never()).saveAudit(eq(7L), eq(1L), eq(88L),
                eq(SyncAuditActionType.CLAIM_RECOVERY_PLAN), eq(actor()), contains("recoveryPlanId"));
    }

    /**
     * CLAIMED 计划被 worker consume 后应推进为 CONSUMED。
     *
     * <p>consume 表示计划已经被 worker 加载为本地执行策略，后续才进入普通 start/checkpoint/complete/fail 链路。
     * 它不是执行成功，因此这里只验证计划状态和审计动作，不验证记录数或 checkpoint。
     */
    @Test
    void consumeClaimedPlanShouldMarkConsumed() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-1");
        SyncExecutionRecoveryPlan claimed = recoveryPlan(SyncRecoveryPlanState.CLAIMED);
        SyncExecutionRecoveryPlan consumed = recoveryPlan(SyncRecoveryPlanState.CONSUMED);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.recoveryPlanMapper().selectByExecutionId(88L)).thenReturn(claimed, consumed);
        when(fixture.recoveryPlanMapper().markPlanState(88L,
                SyncRecoveryPlanState.CLAIMED.name(), SyncRecoveryPlanState.CONSUMED.name())).thenReturn(1);

        SyncRecoveryPlanWorkerResult result = fixture.service().consumePlan(88L, request("worker-1"), actor());

        assertThat(result.planState()).isEqualTo(SyncRecoveryPlanState.CONSUMED.name());
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L),
                eq(SyncAuditActionType.CONSUME_RECOVERY_PLAN), eq(actor()), contains("planState=CONSUMED"));
    }

    /**
     * worker 不能跳过 claim 直接 consume。
     *
     * <p>如果 CREATED 计划可以直接变成 CONSUMED，审计链就无法证明“计划是否已经成功送达 worker”。
     * 因此服务层必须强制先 claim，再 consume。
     */
    @Test
    void consumeCreatedPlanShouldRequireClaimFirst() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-1");
        SyncExecutionRecoveryPlan created = recoveryPlan(SyncRecoveryPlanState.CREATED);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.recoveryPlanMapper().selectByExecutionId(88L)).thenReturn(created);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().consumePlan(88L, request("worker-1"), actor()));

        verify(fixture.recoveryPlanMapper(), never()).markPlanState(eq(88L), eq("CLAIMED"), eq("CONSUMED"));
        verify(fixture.auditSupport(), never()).saveAudit(eq(7L), eq(1L), eq(88L),
                eq(SyncAuditActionType.CONSUME_RECOVERY_PLAN), eq(actor()), contains("recoveryPlanId"));
    }

    /**
     * executorId 不匹配时不能读取恢复计划。
     *
     * <p>HMAC 只能证明调用方属于受信任 worker 集群，不能证明它就是当前 execution 的租约持有人。
     * 因此服务层必须再用 executorId 做二次校验，防止其它 worker 枚举 executionId 读取恢复契约。
     */
    @Test
    void claimShouldRejectExecutorMismatchBeforeReadingPlan() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-owner");
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().claimPlan(88L, request("worker-other"), actor()));

        verify(fixture.recoveryPlanMapper(), never()).selectByExecutionId(88L);
        verify(fixture.auditSupport(), never()).saveAudit(eq(7L), eq(1L), eq(88L),
                eq(SyncAuditActionType.CLAIM_RECOVERY_PLAN), eq(actor()), contains("recoveryPlanId"));
    }

    /**
     * worker 响应应对自由文本做兜底脱敏。
     *
     * <p>恢复计划创建入口会阻止明显敏感内容写入，但商业系统还要考虑历史数据、手工修复和脚本导入。
     * 因此响应 DTO 自身也要做最后一道防线，避免 SQL、凭据、样本等文本继续通过 worker 协议扩散。
     */
    @Test
    void claimShouldRedactSensitiveFreeTextInResponse() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-1");
        SyncExecutionRecoveryPlan claimed = recoveryPlan(SyncRecoveryPlanState.CLAIMED);
        claimed.setReason("select * from customer where password = 'secret'");
        claimed.setShardOrPartition("payload contains sample rows");
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.recoveryPlanMapper().selectByExecutionId(88L)).thenReturn(claimed);

        SyncRecoveryPlanWorkerResult result = fixture.service().claimPlan(88L, request("worker-1"), actor());

        assertThat(result.reason()).contains("脱敏");
        assertThat(result.shardOrPartition()).contains("脱敏");
    }

    /**
     * 脏数据修复重放 selector 应原样提供给 worker。
     *
     * <p>selector 字段天然包含 errorSampleIds/sampleCount，如果复用普通自由文本的 sample 敏感词规则，
     * worker 会拿不到要重放哪批错误样本。这里验证 selector 走专用低敏校验，不因 sample 字段名被误脱敏。</p>
     */
    @Test
    void claimDirtyRecordReplayPlanShouldReturnErrorSampleSelector() {
        Fixture fixture = fixture();
        SyncExecution execution = runningExecution("worker-1");
        SyncExecutionRecoveryPlan claimed = recoveryPlan(SyncRecoveryPlanState.CLAIMED);
        claimed.setErrorSampleSelector("""
                {"selectorMode":"SELECTED_IDS","sourceExecutionId":70,"sampleCount":2,"errorSampleIds":[501,502]}
                """);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.recoveryPlanMapper().selectByExecutionId(88L)).thenReturn(claimed);

        SyncRecoveryPlanWorkerResult result = fixture.service().claimPlan(88L, request("worker-1"), actor());

        assertThat(result.errorSampleSelector()).contains("\"selectorMode\":\"SELECTED_IDS\"");
        assertThat(result.errorSampleSelector()).contains("\"errorSampleIds\":[501,502]");
        assertThat(result.errorSampleSelector()).doesNotContain("脱敏");
    }

    private Fixture fixture() {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionRecoveryPlanMapper recoveryPlanMapper = mock(SyncExecutionRecoveryPlanMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncRecoveryPlanWorkerServiceImpl service = new DataSyncRecoveryPlanWorkerServiceImpl(
                executionMapper,
                recoveryPlanMapper,
                auditSupport);
        return new Fixture(service, executionMapper, recoveryPlanMapper, auditSupport);
    }

    private SyncExecution runningExecution(String executorId) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(1L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setExecutorId(executorId);
        return execution;
    }

    private SyncExecutionRecoveryPlan recoveryPlan(SyncRecoveryPlanState state) {
        SyncExecutionRecoveryPlan plan = new SyncExecutionRecoveryPlan();
        plan.setId(9001L);
        plan.setTenantId(7L);
        plan.setProjectId(101L);
        plan.setWorkspaceId(301L);
        plan.setSyncTaskId(1L);
        plan.setExecutionId(88L);
        plan.setRecoveryType("REPLAY");
        plan.setSourceExecutionId(70L);
        plan.setSourceCheckpointId(700L);
        plan.setWindowStart("2026-06-01T00:00:00");
        plan.setWindowEnd("2026-06-02T00:00:00");
        plan.setShardOrPartition("dt=2026-06-01");
        plan.setReason("事故恢复回放");
        plan.setPlanState(state.name());
        return plan;
    }

    private SyncRecoveryPlanWorkerRequest request(String executorId) {
        SyncRecoveryPlanWorkerRequest request = new SyncRecoveryPlanWorkerRequest();
        request.setExecutorId(executorId);
        request.setIdempotencyKey("recovery-plan-key");
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "SERVICE_ACCOUNT",
                "trace-recovery-plan-worker",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 测试夹具，把服务和 mock 依赖集中放在一起，避免每个用例重复声明样板字段。
     */
    private record Fixture(DataSyncRecoveryPlanWorkerServiceImpl service,
                           SyncExecutionMapper executionMapper,
                           SyncExecutionRecoveryPlanMapper recoveryPlanMapper,
                           SyncAuditSupport auditSupport) {
    }
}
