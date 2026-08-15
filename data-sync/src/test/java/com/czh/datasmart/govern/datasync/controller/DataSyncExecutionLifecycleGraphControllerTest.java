/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - DataSyncExecutionLifecycleGraphControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleGraphService;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleGraphView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证统一生命周期图接口沿用同步任务的数据范围校验，而不是仅凭 executionId 读取跨服务事实。
 */
class DataSyncExecutionLifecycleGraphControllerTest {

    /**
     * 控制器必须先取得当前用户可见的任务聚合，并把同一个对象和可信请求上下文交给图服务。
     * 这条回归测试用于防止以后重构时绕过租户、项目或应用边界，导致其他项目的审计链路被拼入响应。
     */
    @Test
    void shouldQueryLifecycleGraphOnlyAfterTaskVisibilityCheck() {
        DataSyncService dataSyncService = mock(DataSyncService.class);
        SyncExecutionLifecycleGraphService lifecycleGraphService = mock(SyncExecutionLifecycleGraphService.class);
        DataSyncExecutionLifecycleGraphController controller =
                new DataSyncExecutionLifecycleGraphController(dataSyncService, lifecycleGraphService);
        SyncTask visibleTask = new SyncTask();
        visibleTask.setId(31L);
        visibleTask.setTenantId(11L);
        visibleTask.setProjectId(13L);
        SyncExecutionLifecycleGraphView expected = new SyncExecutionLifecycleGraphView(
                "1.0",
                "SYNC_EXECUTION_LIFECYCLE",
                true,
                31L,
                41L,
                42L,
                "VERIFIED",
                "COMPLETE",
                null,
                List.of(),
                List.of(),
                List.of(),
                LocalDateTime.of(2026, 8, 15, 10, 30));
        when(dataSyncService.getTask(eq(31L), any(SyncActorContext.class))).thenReturn(visibleTask);
        when(lifecycleGraphService.query(eq(visibleTask), eq(41L), any(SyncActorContext.class)))
                .thenReturn(expected);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-DataSmart-Project-Id", "13");
        var response = controller.getLifecycleGraph(
                31L, 41L, 11L, 7L, "ORDINARY_USER", "trace-lifecycle-1", headers);

        ArgumentCaptor<SyncActorContext> actorContext = ArgumentCaptor.forClass(SyncActorContext.class);
        verify(dataSyncService).getTask(eq(31L), actorContext.capture());
        verify(lifecycleGraphService).query(visibleTask, 41L, actorContext.getValue());
        assertThat(actorContext.getValue().tenantId()).isEqualTo(11L);
        assertThat(actorContext.getValue().projectId()).isEqualTo(13L);
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-lifecycle-1");
    }
}
