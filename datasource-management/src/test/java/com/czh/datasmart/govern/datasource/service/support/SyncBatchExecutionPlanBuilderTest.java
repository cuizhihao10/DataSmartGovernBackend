/**
 * @Author : Cui
 * @Date: 2026/06/20 02:42
 * @Description DataSmart Govern Backend - SyncBatchExecutionPlanBuilderTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步批处理执行计划构建器测试。
 *
 * <p>这些测试不访问真实数据库，也不启动 Spring 容器。
 * 它们验证的是控制面如何把模板和任务上下文翻译成 worker 契约：
 * 1. 增量同步应生成水位型 checkpoint；
 * 2. UPSERT 应要求幂等冲突写入能力；
 * 3. 全量 APPEND 不应强制 checkpoint 恢复；
 * 4. 计划序列化后不能泄露 JDBC URL、密码或原始 SQL。</p>
 */
class SyncBatchExecutionPlanBuilderTest {

    private final SyncExecutorProperties properties = new SyncExecutorProperties();
    private final SyncBatchExecutionPlanBuilder builder = new SyncBatchExecutionPlanBuilder(properties);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void buildPlanShouldDescribeIncrementalUpsertWithCheckpointResume() {
        properties.setRecommendedJdbcFetchSize(512);
        properties.setRecommendedJdbcWriteBatchSize(256);
        properties.setRecommendedJdbcCommitIntervalRecords(256);
        properties.setCheckpointPersistEveryRecords(2048);

        SyncBatchExecutionPlan plan = builder.buildPlan(
                task(),
                incrementalTemplate(),
                datasource(10L, "MYSQL"),
                datasource(20L, "POSTGRESQL"),
                execution(),
                "worker-a",
                LocalDateTime.of(2026, 6, 20, 3, 0)
        );

        assertEquals("datasmart.datasource.sync-batch-plan.v1", plan.getPlanVersion());
        assertEquals("INCREMENTAL_TIME_WINDOW", plan.getReadPlan().getReadStrategy());
        assertEquals("TIME_WATERMARK", plan.getCheckpointPlan().getCheckpointType());
        assertTrue(plan.getCheckpointPlan().getResumeRequired());
        assertEquals("UPSERT_BY_PRIMARY_OR_UNIQUE_KEY", plan.getWritePlan().getConflictPolicy());
        assertTrue(plan.getWritePlan().getRequiredWorkerCapabilities().contains("IDEMPOTENT_CONFLICT_WRITE"));
        assertEquals(512, plan.getReadPlan().getRecommendedFetchSize());
        assertEquals(2048, plan.getCheckpointPlan().getPersistEveryRecords());
    }

    @Test
    void buildPlanShouldDescribeFullAppendWithoutResumeRequirement() {
        SyncTemplate template = incrementalTemplate();
        template.setSyncMode("FULL");
        template.setWriteStrategy("APPEND");
        template.setIncrementalField(null);
        template.setPrimaryKeyField(null);

        SyncBatchExecutionPlan plan = builder.buildPlan(
                task(),
                template,
                datasource(10L, "MYSQL"),
                datasource(20L, "MYSQL"),
                execution(),
                "worker-b",
                LocalDateTime.of(2026, 6, 20, 3, 5)
        );

        assertEquals("FULL_OBJECT_SCAN", plan.getReadPlan().getReadStrategy());
        assertEquals("FULL_SCAN_COMPLETION_MARKER", plan.getCheckpointPlan().getCheckpointType());
        assertFalse(plan.getCheckpointPlan().getResumeRequired());
        assertEquals("APPEND_ONLY_NO_CONFLICT_CHECK", plan.getWritePlan().getConflictPolicy());
        assertFalse(plan.getWritePlan().getPrimaryKeyRequired());
    }

    @Test
    void serializedPlanShouldNotExposeJdbcUrlPasswordOrRawSql() throws JsonProcessingException {
        SyncBatchExecutionPlan plan = builder.buildPlan(
                task(),
                incrementalTemplate(),
                datasource(10L, "MYSQL"),
                datasource(20L, "POSTGRESQL"),
                execution(),
                "worker-secure",
                LocalDateTime.of(2026, 6, 20, 3, 10)
        );

        String json = objectMapper.writeValueAsString(plan).toLowerCase();

        assertFalse(json.contains("jdbc:mysql"));
        assertFalse(json.contains("secret-password"));
        assertFalse(json.contains("select "));
        assertFalse(json.contains("insert "));
        assertFalse(json.contains("update "));
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(100L);
        task.setLastExecutionId(900L);
        task.setTimeoutSeconds(600);
        task.setMaxRetryCount(3);
        return task;
    }

    private SyncTemplate incrementalTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setId(200L);
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("orders");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("orders_clean");
        template.setSyncMode("INCREMENTAL_TIME");
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setPartitionConfig("{\"field\":\"id\"}");
        return template;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(900L);
        return execution;
    }

    private DataSourceConfig datasource(Long id, String type) {
        DataSourceConfig datasource = new DataSourceConfig();
        datasource.setId(id);
        datasource.setType(type);
        datasource.setJdbcUrl("jdbc:mysql://internal-host:3306/secret");
        datasource.setUsername("internal-user");
        datasource.setPassword("secret-password");
        return datasource;
    }
}
