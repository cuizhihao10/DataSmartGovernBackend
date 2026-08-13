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
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncObjectExecutionLifecycleSupportTest {

    /** 仅传输层的范围探测失败必须生成一个供 Autopilot 使用的持久失败工作单元。 */
    @Test
    void rangeProbeTransportFailureShouldPersistIdempotentRetryableWorkUnit() {
        List<SyncObjectExecution> rows = new ArrayList<>();
        SyncObjectExecutionMapper mapper = statefulMapper(rows);
        SyncObjectExecutionLifecycleSupport lifecycle = new SyncObjectExecutionLifecycleSupport(mapper);

        SyncObjectExecution first = lifecycle.recordPartitionRangeProbeTransportFailure(
                task(), execution(), definition(), 3,
                "DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");
        SyncObjectExecution replay = lifecycle.recordPartitionRangeProbeTransportFailure(
                task(), execution(), definition(), 3,
                "DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");

        assertThat(rows).hasSize(1);
        assertThat(replay).isSameAs(first);
        assertThat(first.getWorkUnitType())
                .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_RANGE_PROBE);
        assertThat(first.getObjectState()).isEqualTo("FAILED");
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getMaxAttemptCount()).isEqualTo(3);
        assertThat(first.getFailedRecordCount()).isEqualTo(1L);
        assertThat(first.getLastErrorType()).isEqualTo("CONNECTOR_TRANSPORT_UNAVAILABLE");
        assertThat(first.getLastErrorCode())
                .isEqualTo("DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");
    }

    /** 成功重试应在真实分片账本物化前移除合成的范围探测标记。 */
    @Test
    void successfulProbeShouldReconcileMarkerIntoRealShardRowsWithoutDuplicates() {
        List<SyncObjectExecution> rows = new ArrayList<>();
        SyncObjectExecutionMapper mapper = statefulMapper(rows);
        SyncObjectExecutionLifecycleSupport lifecycle = new SyncObjectExecutionLifecycleSupport(mapper);
        lifecycle.recordPartitionRangeProbeTransportFailure(
                task(), execution(), definition(), 3,
                "DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");

        List<SyncObjectExecution> initialized = lifecycle.initializePartitionShardExecutions(
                task(), execution(), definition(), autoContract(500_000L, 1L, 500_000L));
        List<SyncObjectExecution> replay = lifecycle.initializePartitionShardExecutions(
                task(), execution(), definition(), autoContract(500_000L, 1L, 500_000L));

        assertThat(initialized).hasSize(3);
        assertThat(replay).hasSize(3);
        assertThat(rows).hasSize(3);
        assertThat(rows).noneMatch(row -> SyncObjectExecutionLifecycleSupport
                .WORK_UNIT_TYPE_PARTITION_RANGE_PROBE.equals(row.getWorkUnitType()));
        assertThat(rows).extracting(SyncObjectExecution::getObjectOrdinal)
                .containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void adaptiveSingleShardShouldBeRecordedAsObjectLedger() {
        List<SyncObjectExecution> inserted = new ArrayList<>();
        SyncObjectExecutionLifecycleSupport lifecycle = lifecycle(inserted);
        SyncPartitionShardExecutionContract contract = autoContract(6L, 1L, 6L);

        List<SyncObjectExecution> rows = lifecycle.initializePartitionShardExecutions(
                task(), execution(), definition(), contract);

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
                task(), execution(), definition(), contract);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getWorkUnitType())
                    .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_SHARD);
            assertThat(row.getShardOrPartition()).isNotBlank();
            assertThat(row.getPartitionField()).isEqualTo("id");
        });
    }

    private SyncPartitionShardExecutionContract autoContract(long rowCount, long min, long max) {
        SyncTaskDefinition definition = definition();
        definition.setPartitionConfig("{\"strategy\":\"AUTO_SPLIT_PK\",\"splitPk\":\"id\"}");
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
                support.parse(definition),
                probe,
                SyncEffectiveExecutionPolicy.defaults(10L, 101L, 34L));
    }

    private SyncObjectExecutionLifecycleSupport lifecycle(List<SyncObjectExecution> inserted) {
        SyncObjectExecutionMapper mapper = statefulMapper(inserted);
        return new SyncObjectExecutionLifecycleSupport(mapper);
    }

    /** 构建一个极小的内存 mapper，使测试可以观测插入/删除协调。 */
    private SyncObjectExecutionMapper statefulMapper(List<SyncObjectExecution> inserted) {
        SyncObjectExecutionMapper mapper = mock(SyncObjectExecutionMapper.class);
        when(mapper.selectByExecutionId(1078L)).thenAnswer(invocation -> new ArrayList<>(inserted));
        when(mapper.insert(any(SyncObjectExecution.class))).thenAnswer(invocation -> {
            SyncObjectExecution row = invocation.getArgument(0);
            long nextId = inserted.stream().map(SyncObjectExecution::getId)
                    .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L) + 1L;
            row.setId(nextId);
            inserted.add(row);
            return 1;
        });
        when(mapper.deleteById(any(Long.class))).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return inserted.removeIf(row -> id.equals(row.getId())) ? 1 : 0;
        });
        return mapper;
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

    private SyncTaskDefinition definition() {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(35L);
        definition.setSourceSchemaName(null);
        definition.setSourceObjectName("fs_test_customer_source");
        definition.setTargetSchemaName("public");
        definition.setTargetObjectName("fs_test_customer_target");
        return definition;
    }
}
