/**
 * @Author : Cui
 * @Date: 2026/07/02 19:20
 * @Description DataSmartGovernBackend - DataQualityPostgreSqlMigrationIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * data-quality PostgreSQL 真实集成测试。
 *
 * <p>本测试不使用 H2 或纯 Mock，原因是本轮要验收的核心风险都属于真实数据库方言：
 * PostgreSQL schema search_path、identity 主键回填、MyBatis-Plus PostgreSQL 分页、
 * 动态 GROUP BY、LIMIT 参数绑定以及 Flyway 历史登记。内存数据库即使测试通过，
 * 也无法证明这些生产路径在 PostgreSQL 上成立。</p>
 *
 * <p>安全与数据边界：
 * 只有显式设置 {@code DATASMART_POSTGRES_INTEGRATION_ENABLED=true} 才会运行；
 * 测试不会创建、删除或 clean 数据库，业务样本使用随机规则名，并在 finally 中按本次 ruleId 逆序删除。
 * 这里不依赖测试事务自动回滚，因为真实应用可能装配多个事务管理器或让某些 Mapper 脱离测试事务；
 * 显式清理更容易观察，也能避免“注解看起来存在，但测试数据已经提交”的假安全。
 * 测试样本使用随机低敏名称，不包含真实租户、业务数据、SQL、凭据或异常原文。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "datasmart.quality.task-management.executor-coordinator-enabled=false",
        "datasmart.quality.task-management.executor-scheduler-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class DataQualityPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final QualityRuleMapper qualityRuleMapper;
    private final QualityCheckExecutionMapper executionMapper;
    private final QualityCheckReportMapper reportMapper;
    private final QualityAnomalyDetailMapper anomalyMapper;

    @Autowired
    DataQualityPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            QualityRuleMapper qualityRuleMapper,
            QualityCheckExecutionMapper executionMapper,
            QualityCheckReportMapper reportMapper,
            QualityAnomalyDetailMapper anomalyMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.qualityRuleMapper = qualityRuleMapper;
        this.executionMapper = executionMapper;
        this.reportMapper = reportMapper;
        this.anomalyMapper = anomalyMapper;
    }

    /**
     * 验证 V1 schema 和四张核心业务表。
     *
     * <p>Flyway 版本成功并不等于 DDL 完整，因此还要从 PostgreSQL catalog 核对四张表。
     * 该断言可以及时发现迁移脚本被移动、location 配错或只创建部分表的情况。</p>
     */
    @Test
    void shouldApplyDataQualitySchemaAndPersistCoreFactsThroughMyBatis() {
        assertPostgreSqlSchemaBaseline();

        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        QualityRule rule = null;
        try {
            rule = insertRule(uniqueSuffix);
            QualityCheckExecution execution = insertExecution(rule);
            QualityCheckReport report = insertReport(rule, execution);
            insertAnomaly(report, rule, "email", "NULL_VALUE");
            insertAnomaly(report, rule, "email", "FORMAT_INVALID");

            assertThat(rule.getId()).isPositive();
            assertThat(execution.getId()).isPositive();
            assertThat(report.getId()).isPositive();
            assertThat(executionMapper.selectMaxExecutionNo(rule.getId())).isEqualTo(1L);

            // selectPage 会经过 PaginationInnerInterceptor，可直接证明 PostgreSQL 分页方言已生效。
            Page<QualityRule> rulePage = qualityRuleMapper.selectPage(
                    new Page<>(1, 10),
                    new LambdaQueryWrapper<QualityRule>()
                            .eq(QualityRule::getTenantId, 900001L)
                            .eq(QualityRule::getProjectId, 900101L)
                            .eq(QualityRule::getName, rule.getName())
            );
            assertThat(rulePage.getTotal()).isEqualTo(1);
            assertThat(rulePage.getRecords()).extracting(QualityRule::getId).containsExactly(rule.getId());

            List<QualityAnomalyAggregationItem> aggregations = anomalyMapper.aggregateAnomalies(
                    "field_name",
                    900001L,
                    report.getId(),
                    rule.getId(),
                    null,
                    "mail",
                    null,
                    rule.getTargetObject(),
                    null,
                    null,
                    10,
                    900101L,
                    900201L,
                    List.of(),
                    false
            );
            assertThat(aggregations).hasSize(1);
            assertThat(aggregations.getFirst().getAggregateKey()).isEqualTo("email");
            assertThat(aggregations.getFirst().getAnomalyCount()).isEqualTo(2L);
            assertThat(aggregations.getFirst().getLatestCreateTime()).isNotNull();
        } finally {
            deleteIntegrationFacts(rule);
        }
    }

    /**
     * 校验连接确实进入 data_quality schema，且 V1 已由 Flyway 成功登记。
     */
    private void assertPostgreSqlSchemaBaseline() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        Integer flywaySuccessCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'data_quality'
                  AND table_name IN (
                      'quality_rule',
                      'quality_check_execution',
                      'quality_check_report',
                      'quality_anomaly_detail'
                  )
                """, Integer.class);

        assertThat(currentSchema).isEqualTo("data_quality");
        assertThat(flywaySuccessCount).isEqualTo(1);
        assertThat(tableCount).isEqualTo(4);
    }

    /**
     * 创建规则事实，覆盖 NUMERIC、默认版本和 identity 主键回填。
     */
    private QualityRule insertRule(String uniqueSuffix) {
        QualityRule rule = new QualityRule();
        rule.setTenantId(900001L);
        rule.setProjectId(900101L);
        rule.setWorkspaceId(900201L);
        rule.setName("pg-integration-" + uniqueSuffix);
        rule.setRuleType("COMPLETENESS");
        rule.setTargetObject("integration.customer.email");
        rule.setTargetType("RELATIONAL_FIELD");
        rule.setDataSourceId(900301L);
        rule.setDatabaseName("integration");
        rule.setSchemaName("public");
        rule.setTableName("customer");
        rule.setFieldName("email");
        rule.setScanStrategy("COLUMN_NULL_RATE");
        rule.setTargetValidationStatus("VALIDATED");
        rule.setComparisonOperator("GTE");
        rule.setExpectedValue(new BigDecimal("0.9900"));
        rule.setSeverity("HIGH");
        rule.setDescription("PostgreSQL 迁移集成测试规则");
        rule.setStatus("ACTIVE");
        rule.setRuleVersion(1);
        qualityRuleMapper.insert(rule);
        return rule;
    }

    /**
     * 创建执行事实，验证同一规则 execution_no 唯一约束和 MAX 聚合查询。
     */
    private QualityCheckExecution insertExecution(QualityRule rule) {
        QualityCheckExecution execution = new QualityCheckExecution();
        execution.setTenantId(rule.getTenantId());
        execution.setProjectId(rule.getProjectId());
        execution.setWorkspaceId(rule.getWorkspaceId());
        execution.setRuleId(rule.getId());
        execution.setExecutionNo(1L);
        execution.setTriggerType("INTEGRATION_TEST");
        execution.setExecutionState("SUCCESS");
        execution.setOperator("integration-test");
        execution.setExecutorId("postgresql-test-runner");
        execution.setStartedAt(LocalDateTime.now().minusSeconds(1));
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationMs(1000L);
        execution.setScanPlanSnapshot("{\"mode\":\"schema-compatibility\"}");
        execution.setMessage("PostgreSQL Mapper 集成验证");
        executionMapper.insert(execution);
        return execution;
    }

    /**
     * 创建报告快照，验证规则快照字段和 NUMERIC 比例值映射。
     */
    private QualityCheckReport insertReport(QualityRule rule, QualityCheckExecution execution) {
        QualityCheckReport report = new QualityCheckReport();
        report.setTenantId(rule.getTenantId());
        report.setProjectId(rule.getProjectId());
        report.setWorkspaceId(rule.getWorkspaceId());
        report.setRuleId(rule.getId());
        report.setExecutionId(execution.getId());
        report.setRuleVersion(rule.getRuleVersion());
        report.setRuleName(rule.getName());
        report.setRuleType(rule.getRuleType());
        report.setTargetObject(rule.getTargetObject());
        report.setSeverity(rule.getSeverity());
        report.setMeasuredValue(new BigDecimal("0.9800"));
        report.setExpectedValue(rule.getExpectedValue());
        report.setComparisonOperator(rule.getComparisonOperator());
        report.setCheckStatus("FAILED");
        report.setSampleSize(100);
        report.setExceptionCount(2);
        report.setPassRate(new BigDecimal("0.9800"));
        report.setTriggerType(execution.getTriggerType());
        report.setSummary("PostgreSQL 迁移集成测试报告");
        reportMapper.insert(report);
        return report;
    }

    /**
     * 创建低敏异常样本，用于验证动态聚合 SQL；测试不保存真实记录定位信息或原始载荷。
     */
    private void insertAnomaly(
            QualityCheckReport report,
            QualityRule rule,
            String fieldName,
            String anomalyType) {
        QualityAnomalyDetail anomaly = new QualityAnomalyDetail();
        anomaly.setTenantId(rule.getTenantId());
        anomaly.setProjectId(rule.getProjectId());
        anomaly.setWorkspaceId(rule.getWorkspaceId());
        anomaly.setReportId(report.getId());
        anomaly.setRuleId(rule.getId());
        anomaly.setTargetObject(rule.getTargetObject());
        anomaly.setAnomalyType(anomalyType);
        anomaly.setFieldName(fieldName);
        anomaly.setSeverity(rule.getSeverity());
        anomaly.setRecommendation("人工复核");
        anomalyMapper.insert(anomaly);
        assertThat(anomaly.getId()).isPositive();
    }

    /**
     * 按依赖逆序删除本次测试事实。
     *
     * <p>生产 DDL 故意没有级联外键，因此测试也不依赖级联删除。先删异常，再删报告、执行和规则，
     * 可以忠实模拟未来数据保留/清理任务需要遵循的事实顺序。删除条件只使用本次 identity ruleId，
     * 不按租户做宽范围清理，避免并行测试或共享开发库中误删其他数据。</p>
     */
    private void deleteIntegrationFacts(QualityRule rule) {
        if (rule == null || rule.getId() == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM quality_anomaly_detail WHERE rule_id = ?", rule.getId());
        jdbcTemplate.update("DELETE FROM quality_check_report WHERE rule_id = ?", rule.getId());
        jdbcTemplate.update("DELETE FROM quality_check_execution WHERE rule_id = ?", rule.getId());
        jdbcTemplate.update("DELETE FROM quality_rule WHERE id = ?", rule.getId());
    }
}
