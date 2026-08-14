/**
 * @Author : Cui
 * @Date: 2026/08/11 21:35
 * @Description DataSmart Govern Backend - DataSyncAutopilotRecoveryControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryRepairRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryTriggerConsumerResultRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryDeadLetterRequest;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryCaseService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryAutonomousQuarantineService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryDeadLetterService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryRepairCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryRepairReceiptView;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryRepairService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultView;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 面向 Autopilot 固定消费结果回调契约的 HTTP 边界测试。
 */
class DataSyncAutopilotRecoveryControllerTest {

    /** 仅供测试使用的凭据，用于覆盖与真实部署一致的内部令牌校验分支。 */
    private static final String TEST_INTERNAL_TOKEN = "unit-test-internal-token";

    /**
     * 路由必须规范化契约中声明的枚举文本，并且只向服务层传递最小化的结果事实。
     *
     * <p>测试会传入与部署配置相同的内部令牌，因此在本地启用内部接口保护时也能稳定执行。
     * 同时验证响应数据来自持久化服务返回的受限视图，而不是原样回显请求或 outbox 载荷。</p>
     */
    @Test
    void shouldRecordTheFixedTriggerConsumerResultContract() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        SyncAutopilotRecoveryRepairService repairService = mock(SyncAutopilotRecoveryRepairService.class);
        DataSyncAutopilotRecoveryController controller =
                new DataSyncAutopilotRecoveryController(
                        caseService, consumerResultService, quarantineService, repairService,
                        deadLetterService, TEST_INTERNAL_TOKEN);
        SyncAutopilotRecoveryTriggerConsumerResultView expected =
                new SyncAutopilotRecoveryTriggerConsumerResultView(
                        "autopilot-trigger:" + "a".repeat(64),
                        1001L,
                        "RECOVERY_STARTED",
                        "AUTOPILOT_FAILED_OBJECTS_REQUEUED",
                        77L,
                        "b".repeat(64),
                        "SEARCH",
                        "RAG",
                        2,
                        "sha256:" + "c".repeat(64),
                        LocalDateTime.of(2026, 8, 11, 21, 35));
        when(consumerResultService.recordConsumerResult(eq(expected.eventId()), any())).thenReturn(expected);

        var response = controller.recordTriggerConsumerResult(
                expected.eventId(),
                new SyncAutopilotRecoveryTriggerConsumerResultRequest(
                        "recovery-started",
                        "autopilot-failed-objects-requeued",
                        77L,
                        1001L,
                        "search",
                        "rag",
                        2,
                        "sha256:" + "C".repeat(64)),
                TEST_INTERNAL_TOKEN,
                "trace-1");

        ArgumentCaptor<SyncAutopilotRecoveryTriggerConsumerResultCommand> command =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerConsumerResultCommand.class);
        verify(consumerResultService).recordConsumerResult(eq(expected.eventId()), command.capture());
        assertThat(command.getValue().status())
                .isEqualTo(SyncAutopilotRecoveryConsumerResultStatus.RECOVERY_STARTED);
        assertThat(command.getValue().reasonCode()).isEqualTo("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        assertThat(command.getValue().caseId()).isEqualTo(77L);
        assertThat(command.getValue().currentExecutionId()).isEqualTo(1001L);
        assertThat(command.getValue().retrievalDecision()).isEqualTo("SEARCH");
        assertThat(command.getValue().retrievalStrategy()).isEqualTo("RAG");
        assertThat(command.getValue().retrievalEvidenceCount()).isEqualTo(2);
        assertThat(command.getValue().retrievalEvidenceDigest()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-1");
    }

    /**
     * 任意自由文本原因必须在 HTTP 边界被拒绝，不能进入负责持久化的服务层。
     */
    @Test
    void shouldRejectFreeFormConsumerReasonText() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        SyncAutopilotRecoveryRepairService repairService = mock(SyncAutopilotRecoveryRepairService.class);
        DataSyncAutopilotRecoveryController controller =
                new DataSyncAutopilotRecoveryController(
                        caseService, consumerResultService, quarantineService, repairService,
                        deadLetterService, TEST_INTERNAL_TOKEN);

        assertThatThrownBy(() -> controller.recordTriggerConsumerResult(
                "autopilot-trigger:" + "a".repeat(64),
                new SyncAutopilotRecoveryTriggerConsumerResultRequest(
                        "RECOVERY_STARTED",
                        "the model said retry this database because timeout",
                        77L,
                        1001L,
                        null,
                        null,
                        null,
                        null),
                TEST_INTERNAL_TOKEN,
                "trace-2"))
                .isInstanceOf(PlatformBusinessException.class);
        verifyNoInteractions(consumerResultService);
    }

    /**
     * DLT 路由只能把事件与执行身份传给负责持久化收敛的服务，避免扩大内部接口能力。
     */
    @Test
    void shouldRecordTheFixedDeadLetterContract() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        SyncAutopilotRecoveryRepairService repairService = mock(SyncAutopilotRecoveryRepairService.class);
        DataSyncAutopilotRecoveryController controller =
                new DataSyncAutopilotRecoveryController(
                        caseService, consumerResultService, quarantineService, repairService,
                        deadLetterService, TEST_INTERNAL_TOKEN);
        SyncAutopilotRecoveryTriggerConsumerResultView expected =
                new SyncAutopilotRecoveryTriggerConsumerResultView(
                        "event-4", 1004L, "ATTENTION_REQUIRED",
                        "AUTOPILOT_TRIGGER_DEAD_LETTERED", 84L, "d".repeat(64),
                        null, null, null, null, LocalDateTime.of(2026, 8, 12, 12, 0));
        when(deadLetterService.recordDeadLettered("event-4", 1004L)).thenReturn(expected);

        var response = controller.recordTriggerDeadLetter(
                " event-4 ",
                new SyncAutopilotRecoveryDeadLetterRequest(1004L),
                TEST_INTERNAL_TOKEN,
                "trace-dlt");

        verify(deadLetterService).recordDeadLettered("event-4", 1004L);
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-dlt");
    }

    /**
     * 缺少部署凭据时必须在任何恢复服务修改状态前停止请求。
     */
    @Test
    void shouldRejectAnInternalRouteWhenServiceTokenIsNotConfigured() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        SyncAutopilotRecoveryRepairService repairService = mock(SyncAutopilotRecoveryRepairService.class);
        DataSyncAutopilotRecoveryController controller = new DataSyncAutopilotRecoveryController(
                caseService, consumerResultService, quarantineService, repairService, deadLetterService, " ");

        assertThatThrownBy(() -> controller.recordTriggerDeadLetter(
                "event-4", new SyncAutopilotRecoveryDeadLetterRequest(1004L), null, "trace-missing-token"))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("authentication is not configured");
        verifyNoInteractions(caseService, consumerResultService, quarantineService, repairService, deadLetterService);
    }

    /**
     * 受治理修复路由必须保留动作参数和双主体，且不能把请求当作已经获得执行许可。
     */
    @Test
    void shouldDelegateGovernedRepairWithDualPrincipalFacts() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryRepairService repairService = mock(SyncAutopilotRecoveryRepairService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        DataSyncAutopilotRecoveryController controller = new DataSyncAutopilotRecoveryController(
                caseService, consumerResultService, quarantineService, repairService,
                deadLetterService, TEST_INTERNAL_TOKEN);
        SyncAutopilotRecoveryRepairReceiptView expected = new SyncAutopilotRecoveryRepairReceiptView(
                "event-5:repair-apply", 91L, 41L, 1001L, 1001L,
                "REPAIR_FIELD_MAPPING", true, 1, "RETRYING", "RETRYING",
                "AUTOPILOT_FIELD_MAPPING_REPAIRED", List.of(), "f".repeat(64),
                "AUTO_APPROVED", false, null, null);
        when(repairService.apply(any(), any(), any())).thenReturn(expected);
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.PROJECT_ID, "30");
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "30");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, "30:OWNER");

        var response = controller.applyGovernedRepair(
                91L,
                new SyncAutopilotRecoveryRepairRequest(
                        0L, 10L, 30L, 41L, 1001L, 1,
                        "a".repeat(64), "b".repeat(64), "repair-field-mapping",
                        "f".repeat(64), "event-5:repair-apply",
                        Map.of("repairMode", "METADATA_PROVEN_SAFE")),
                TEST_INTERNAL_TOKEN, "501", "OWNER", "RECOVERY_AGENT", "delegation-1", "trace-repair",
                headers);

        ArgumentCaptor<SyncAutopilotRecoveryRepairCommand> command =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryRepairCommand.class);
        ArgumentCaptor<SyncActorContext> actor = ArgumentCaptor.forClass(SyncActorContext.class);
        verify(repairService).apply(command.capture(), any(), actor.capture());
        assertThat(command.getValue().action().name()).isEqualTo("REPAIR_FIELD_MAPPING");
        assertThat(command.getValue().repairParameters())
                .containsEntry("repairMode", "METADATA_PROVEN_SAFE");
        assertThat(actor.getValue().projectId()).isEqualTo(30L);
        assertThat(actor.getValue().authorizedProjectRoles())
                .extracting(item -> item.projectRole())
                .containsExactly("OWNER");
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-repair");
    }

}
