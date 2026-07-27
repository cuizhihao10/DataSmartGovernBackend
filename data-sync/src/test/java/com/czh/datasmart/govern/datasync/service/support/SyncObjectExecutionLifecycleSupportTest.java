/**
 * @Author : Cui
 * @Date: 2026/07/27 21:17
 * @Description DataSmart Govern Backend - SyncObjectExecutionLifecycleSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncObjectExecutionLifecycleSupportTest {

    @Test
    void adaptiveSingleShardShouldBeRecordedAsObjectLedger() {
        List<SyncObjectExecution> inserted = new ArrayList<>();
        SyncObjectExecutionLifecycleSupport lifecycle = lifecycle(inserted);
        SyncPartitionShardExecutionContract contract = autoContract(6L, 1L, 6L);

        List<SyncObjectExecution> rows = lifecycle.initializePartitionShardExecutions(
                task(), execution(), template(), contract);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getWorkUnitType())
                .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_OBJECT);
        assertThat(rows.getFirst().getShardOrPartition()).isNull();
        assertThat(rows.getFirst().getPartitionField()).isNull();
    }

    @Test
    void adaptiveMultipleShardsShouldBeRecordedAsPartitionShardLedger() {
        List<SyncObjectExecution> inserted = new ArrayList<>();
        SyncObjectExecutionLifecycleSupport lifecycle = lifecycle(inserted);
        SyncPartitionShardExecutionContract contract = autoContract(500_000L, 1L, 500_000L);

        List<SyncObjectExecution> rows = lifecycle.initializePartitionShardExecutions(
                task(), execution(), template(), contract);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getWorkUnitType())
                    .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_SHARD);
            assertThat(row.getShardOrPartition()).isNotBlank();
            assertThat(row.getPartitionField()).isEqualTo("id");
        });
    }

    private SyncPartitionShardExecutionContract autoContract(long rowCount, long min, long max) {
        SyncTemplate template = template();
        template.setPartitionConfig("{\"strategy\":\"AUTO_SPLIT_PK\",\"splitPk\":\"id\"}");
        SyncPartitionShardExecutionContractSupport support =
                new SyncPartitionShardExecutionContractSupport(new ObjectMapper());
        DatasourcePartitionRangeProbeResponse probe = new DatasourcePartitionRangeProbeResponse();
        probe.setProbeStatus("RANGE_PROBED");
        probe.setNumericRange(true);
        probe.setMinValue(min);
        probe.setMaxValue(max);
        probe.setRowCount(rowCount);
        probe.setWarnings(List.of());
        return support.buildAutoRangeContract(
                support.parse(template),
                probe,
                SyncEffectiveExecutionPolicy.defaults(10L, 101L, 34L));
    }

    private SyncObjectExecutionLifecycleSupport lifecycle(List<SyncObjectExecution> inserted) {
        SyncObjectExecutionMapper mapper = mock(SyncObjectExecutionMapper.class);
        when(mapper.selectByExecutionId(1078L)).thenReturn(List.of());
        when(mapper.insert(any(SyncObjectExecution.class))).thenAnswer(invocation -> {
            SyncObjectExecution row = invocation.getArgument(0);
            row.setId((long) inserted.size() + 1L);
            inserted.add(row);
            return 1;
        });
        return new SyncObjectExecutionLifecycleSupport(mapper);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(34L);
        return task;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(1078L);
        execution.setTenantId(10L);
        execution.setProjectId(101L);
        execution.setSyncTaskId(34L);
        return execution;
    }

    private SyncTemplate template() {
        SyncTemplate template = new SyncTemplate();
        template.setId(35L);
        template.setSourceSchemaName(null);
        template.setSourceObjectName("fs_test_customer_source");
        template.setTargetSchemaName("public");
        template.setTargetObjectName("fs_test_customer_target");
        return template;
    }
}
