/**
 * @Author : Cui
 * @Date: 2026/07/22 19:30
 * @Description DataSmart Govern Backend - SyncRecoveryCasePublishSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishRequest;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 验证恢复案例发布对“复用父 execution 的失败对象重试”仍能保留失败证据。
 */
@ExtendWith(MockitoExtension.class)
class SyncRecoveryCasePublishSupportTest {

    @Mock
    private SyncExecutionMapper executionMapper;
    @Mock
    private SyncAuditRecordMapper auditRecordMapper;
    @Mock
    private SyncIncidentRecordMapper incidentMapper;
    @Mock
    private SyncAuditSupport auditSupport;
    @InjectMocks
    private SyncRecoveryCasePublishSupport support;

    @Test
    void shouldPublishWhenSuccessfulExecutionHasPriorFailedObjectRetryAudit() {
        SyncTask task = task();
        SyncExecution reusedExecution = execution("SUCCEEDED");
        when(executionMapper.selectById(301L)).thenReturn(reusedExecution);
        when(auditRecordMapper.selectCount(any())).thenReturn(1L);
        when(incidentMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SyncIncidentRecord record = invocation.getArgument(0);
            record.setId(901L);
            return 1;
        }).when(incidentMapper).insert(any(SyncIncidentRecord.class));

        var result = support.publish(task, request(), null);

        assertEquals(901L, result.getCaseId());
        assertEquals(301L, result.getDiagnosisExecutionId());
        assertEquals(301L, result.getValidationExecutionId());
    }

    @Test
    void shouldRejectSuccessfulDiagnosisWithoutHistoricalFailureEvidence() {
        when(executionMapper.selectById(301L)).thenReturn(execution("SUCCEEDED"));
        when(auditRecordMapper.selectCount(any())).thenReturn(0L);

        assertThrows(PlatformBusinessException.class, () -> support.publish(task(), request(), null));
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(101L);
        task.setTenantId(10L);
        task.setProjectId(101L);
        return task;
    }

    private SyncExecution execution(String state) {
        SyncExecution execution = new SyncExecution();
        execution.setId(301L);
        execution.setTenantId(10L);
        execution.setProjectId(101L);
        execution.setSyncTaskId(101L);
        execution.setExecutionState(state);
        execution.setFailedRecordCount(0L);
        execution.setRecordsRead(8L);
        execution.setRecordsWritten(8L);
        return execution;
    }

    private SyncRecoveryCasePublishRequest request() {
        SyncRecoveryCasePublishRequest request = new SyncRecoveryCasePublishRequest();
        request.setDiagnosisExecutionId(301L);
        request.setValidationExecutionId(301L);
        request.setRootCauseCodes(List.of("PRIMARY_KEY_CONFLICT"));
        request.setRepairActionCodes(List.of("RETRY_FAILED_OBJECTS_AFTER_ROOT_CAUSE_FIXED"));
        request.setEvidenceReferences(List.of("rag:case-42"));
        return request;
    }
}
