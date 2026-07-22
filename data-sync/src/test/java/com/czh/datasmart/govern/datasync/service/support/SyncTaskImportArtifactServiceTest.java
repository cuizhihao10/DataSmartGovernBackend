/**
 * @Author : Cui
 * @Date: 2026/07/22 19:12
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactDryRunResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportOptions;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportRowResult;
import com.czh.datasmart.govern.datasync.entity.SyncTaskImportArtifact;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskImportArtifactMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the immutable upload and structured dry-run diagnostic boundary. */
class SyncTaskImportArtifactServiceTest {

    @Test
    void uploadShouldCreatePathSafeProjectScopedArtifactWithoutImportingTasks() {
        Fixture fixture = fixture();
        byte[] content = "name,templateId\norders,7\n".getBytes(StandardCharsets.UTF_8);
        when(fixture.codec().resolveFormat("CSV", "tasks.csv")).thenReturn("CSV");
        when(fixture.mapper().insert(any(SyncTaskImportArtifact.class))).thenReturn(1);

        SyncTaskImportArtifactView view = fixture.service().upload(content, "tasks.csv", "CSV", actor());

        ArgumentCaptor<SyncTaskImportArtifact> captor = ArgumentCaptor.forClass(SyncTaskImportArtifact.class);
        verify(fixture.mapper()).insert(captor.capture());
        SyncTaskImportArtifact saved = captor.getValue();
        assertThat(saved.getArtifactRef()).startsWith("sync-import-").doesNotContain(":");
        assertThat(saved.getTenantId()).isEqualTo(10L);
        assertThat(saved.getProjectId()).isEqualTo(101L);
        assertThat(saved.getOwnerId()).isEqualTo(1001L);
        assertThat(saved.getContentBody()).isEqualTo(content);
        assertThat(saved.getArtifactState()).isEqualTo("UPLOADED");
        assertThat(view.artifactRef()).isEqualTo(saved.getArtifactRef());
    }

    @Test
    void dryRunShouldPersistStructuredRepairableDiagnosticAndRagQuery() {
        Fixture fixture = fixture();
        SyncTaskImportArtifact artifact = artifact();
        when(fixture.mapper().selectOne(any())).thenReturn(artifact);
        when(fixture.mapper().updateById(any(SyncTaskImportArtifact.class))).thenReturn(1);
        SyncTaskImportResult importResult = new SyncTaskImportResult();
        importResult.setDryRun(true);
        importResult.setStatus("BLOCKED_BY_CONFLICT");
        importResult.setTotalRows(1);
        importResult.setConflictCount(1);
        importResult.setFailedCount(0);
        importResult.setRows(List.of(new SyncTaskImportRowResult(
                2, null, "orders", "CONFLICT", null, "任务名称与现有任务冲突")));
        when(fixture.dataSyncService().importTasks(
                eq(artifact.getContentBody()), any(SyncTaskImportOptions.class), eq(actor())))
                .thenReturn(importResult);

        SyncTaskImportArtifactDryRunResult result = fixture.service().dryRun(
                artifact.getArtifactRef(), false, actor());

        SyncTaskImportRowResult row = result.importResult().getRows().getFirst();
        assertThat(result.repairRequired()).isTrue();
        assertThat(row.getErrorCode()).isEqualTo("IMPORT_TASK_NAME_CONFLICT");
        assertThat(row.getFieldName()).isEqualTo("name");
        assertThat(row.getRepairable()).isTrue();
        assertThat(result.ragQuery()).contains("IMPORT_TASK_NAME_CONFLICT");
        assertThat(result.confirmationDigest()).hasSize(64);
        assertThat(artifact.getArtifactState()).isEqualTo("DRY_RUN_FAILED");
        verify(fixture.mapper()).updateById(artifact);
    }

    private Fixture fixture() {
        SyncTaskImportArtifactMapper mapper = mock(SyncTaskImportArtifactMapper.class);
        SyncTaskDefinitionExchangeCodecSupport codec = mock(SyncTaskDefinitionExchangeCodecSupport.class);
        DataSyncService dataSyncService = mock(DataSyncService.class);
        return new Fixture(
                new SyncTaskImportArtifactService(mapper, codec, dataSyncService, new ObjectMapper()),
                mapper,
                codec,
                dataSyncService
        );
    }

    private SyncTaskImportArtifact artifact() {
        SyncTaskImportArtifact artifact = new SyncTaskImportArtifact();
        artifact.setId(1L);
        artifact.setArtifactRef("sync-import-001");
        artifact.setTenantId(10L);
        artifact.setProjectId(101L);
        artifact.setOwnerId(1001L);
        artifact.setVersionNumber(1);
        artifact.setFileName("tasks.csv");
        artifact.setFileFormat("CSV");
        artifact.setContentHash("content-hash");
        artifact.setContentBody("name,templateId\norders,7\n".getBytes(StandardCharsets.UTF_8));
        artifact.setContentSizeBytes((long) artifact.getContentBody().length);
        artifact.setArtifactState("UPLOADED");
        artifact.setCreateTime(LocalDateTime.now());
        artifact.setUpdateTime(LocalDateTime.now());
        return artifact;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                10L, 101L, null, 1001L, "PROJECT_OWNER", "trace-import",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false
        );
    }

    private record Fixture(
            SyncTaskImportArtifactService service,
            SyncTaskImportArtifactMapper mapper,
            SyncTaskDefinitionExchangeCodecSupport codec,
            DataSyncService dataSyncService) {
    }
}
