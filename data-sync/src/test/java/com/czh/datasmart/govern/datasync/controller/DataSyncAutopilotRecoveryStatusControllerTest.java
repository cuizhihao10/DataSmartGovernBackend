/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - DataSyncAutopilotRecoveryStatusControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryStatusQueryService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryStatusView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that the public status route reuses normal task visibility before reading recovery facts. */
class DataSyncAutopilotRecoveryStatusControllerTest {

    /** The controller must authorize the task first and pass that exact visible aggregate to the query service. */
    @Test
    void shouldQueryStatusOnlyAfterTaskVisibilityCheck() {
        DataSyncService dataSyncService = mock(DataSyncService.class);
        SyncAutopilotRecoveryStatusQueryService queryService =
                mock(SyncAutopilotRecoveryStatusQueryService.class);
        DataSyncAutopilotRecoveryStatusController controller =
                new DataSyncAutopilotRecoveryStatusController(dataSyncService, queryService);
        SyncTask visibleTask = new SyncTask();
        visibleTask.setId(31L);
        visibleTask.setTenantId(11L);
        visibleTask.setProjectId(13L);
        SyncAutopilotRecoveryStatusView expected = SyncAutopilotRecoveryStatusView.unavailable(
                31L, 41L, "FAILED", null);
        when(dataSyncService.getTask(org.mockito.ArgumentMatchers.eq(31L),
                org.mockito.ArgumentMatchers.any(SyncActorContext.class))).thenReturn(visibleTask);
        when(queryService.query(visibleTask, 41L)).thenReturn(expected);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-DataSmart-Project-Id", "13");
        var response = controller.getAutopilotRecoveryStatus(
                31L, 41L, 11L, 7L, "ORDINARY_USER", "trace-status-1", headers);

        ArgumentCaptor<SyncActorContext> actorContext = ArgumentCaptor.forClass(SyncActorContext.class);
        verify(dataSyncService).getTask(org.mockito.ArgumentMatchers.eq(31L), actorContext.capture());
        verify(queryService).query(visibleTask, 41L);
        assertThat(actorContext.getValue().tenantId()).isEqualTo(11L);
        assertThat(actorContext.getValue().projectId()).isEqualTo(13L);
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-status-1");
    }
}
