/**
 * @Author : Cui
 * @Date: 2026/08/10 10:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseStateMachineTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SyncAutopilotRecoveryCaseStateMachineTest {

    private final SyncAutopilotRecoveryCaseStateMachine stateMachine = new SyncAutopilotRecoveryCaseStateMachine();

    @Test
    void transitionShouldAllowOnlyTheDeclaredRecoveryLifecycle() {
        assertThat(stateMachine.targetState(
                SyncAutopilotRecoveryCaseState.AUTO_APPROVED,
                SyncAutopilotRecoveryReceiptType.RECOVERY_STARTED))
                .isEqualTo(SyncAutopilotRecoveryCaseState.RECOVERY_STARTED);
        assertThat(stateMachine.targetState(
                SyncAutopilotRecoveryCaseState.RECOVERY_STARTED,
                SyncAutopilotRecoveryReceiptType.RECOVERY_SUCCEEDED))
                .isEqualTo(SyncAutopilotRecoveryCaseState.RECOVERED);

        assertThatIllegalArgumentException().isThrownBy(() -> stateMachine.targetState(
                SyncAutopilotRecoveryCaseState.AUTO_APPROVED,
                SyncAutopilotRecoveryReceiptType.RECOVERY_SUCCEEDED));
    }
}
