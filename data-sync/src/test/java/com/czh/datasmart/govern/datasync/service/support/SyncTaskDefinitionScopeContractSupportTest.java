/**
 * @Author : Cui
 * @Date: 2026/07/05 13:45
 * @Description DataSmart Govern Backend - SyncTaskDefinitionScopeContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步范围合同解析测试。
 *
 * <p>这组测试刻意覆盖用户最关心的“源端/目标端选择范围”问题：单表、多表、整 schema、整库和自定义 SQL。
 * data-sync 后续会继续演进真实 runner，但无论 runner 如何扩展，控制面都必须先把“用户到底想同步哪些对象”
 * 解析成稳定、低敏、可审计的合同，否则很容易把配置能力误当成执行能力。</p>
 */
class SyncTaskDefinitionScopeContractSupportTest {

    private final SyncTaskDefinitionScopeContractSupport support =
            new SyncTaskDefinitionScopeContractSupport(new ObjectMapper());

    @Test
    void missingScopeTypeShouldDefaultToSingleObjectForBackwardCompatibility() {
        SyncTaskDefinition definition = baseDefinition();
        definition.setSyncScopeType(null);

        SyncTaskDefinitionScopeContract contract = support.evaluate(definition);

        assertThat(contract.scopeType()).isEqualTo("SINGLE_OBJECT");
        assertThat(contract.singleObjectScope()).isTrue();
        assertThat(contract.executableByMinimalBridge()).isTrue();
        assertThat(contract.blockingIssueCodes()).isEmpty();
    }

    @Test
    void objectListShouldCountMappingsAndRequireDedicatedRunner() {
        SyncTaskDefinition definition = baseDefinition();
        definition.setSyncScopeType("OBJECT_LIST");
        definition.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject":"customer","targetObject":"customer"},
                    {"sourceObject":"orders","targetObject":"orders"}
                  ]
                }
                """);

        SyncTaskDefinitionScopeContract contract = support.evaluate(definition);

        assertThat(contract.multiObjectScope()).isTrue();
        assertThat(contract.selectedObjectCount()).isEqualTo(2);
        assertThat(contract.requiresApproval()).isFalse();
        assertThat(contract.executableByMinimalBridge()).isFalse();
        assertThat(contract.issueCodes()).contains("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
    }

    @Test
    void objectListShouldAcceptCurrentWizardAndAgentObjectNameFields() {
        SyncTaskDefinition definition = baseDefinition();
        definition.setSyncScopeType("OBJECT_LIST");
        definition.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObjectName":"fs_test_customer_source","targetObjectName":"fs_test_customer_source"},
                    {"sourceObjectName":"fs_test_customer_target","targetObjectName":"fs_test_customer_target"}
                  ]
                }
                """);

        SyncTaskDefinitionScopeContract contract = support.evaluate(definition);

        assertThat(contract.selectedObjectCount()).isEqualTo(2);
        assertThat(contract.blockingIssueCodes()).doesNotContain("OBJECT_MAPPING_IDENTIFIER_UNSAFE");
    }

    @Test
    void databaseFullShouldDefaultToControlledDiscoveryPolicy() {
        SyncTaskDefinition definition = baseDefinition();
        definition.setSyncScopeType("DATABASE_FULL");
        definition.setObjectMappingConfig(null);

        SyncTaskDefinitionScopeContract contract = support.evaluate(definition);

        assertThat(contract.blockingIssueCodes()).isEmpty();
        assertThat(contract.warnings()).contains("DATABASE_FULL_DISCOVERY_POLICY_DEFAULTED");
        assertThat(contract.issueCodes()).contains("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        assertThat(contract.recommendedActions()).anySatisfy(action ->
                assertThat(action).contains("受控默认发现策略"));
    }

    @Test
    void customSqlShouldAcceptReadOnlySelectWithTargetAndFieldMapping() {
        SyncTaskDefinition definition = baseDefinition();
        definition.setSyncMode("CUSTOM_SQL_QUERY");
        definition.setSyncScopeType("CUSTOM_SQL_QUERY");
        definition.setCustomSqlConfig("""
                {"sql":"select id, name from customer where status = :status"}
                """);
        definition.setFieldMappingConfig("""
                [{"sourceField":"id","targetField":"id"},{"sourceField":"name","targetField":"name"}]
                """);

        SyncTaskDefinitionScopeContract contract = support.evaluate(definition);

        assertThat(contract.customSqlScope()).isTrue();
        assertThat(contract.customSqlDeclared()).isTrue();
        assertThat(contract.requiresApproval()).isFalse();
        assertThat(contract.blockingIssueCodes()).isEmpty();
        assertThat(contract.issueCodes()).doesNotContain("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        assertThat(contract.executableByMinimalBridge()).isTrue();
    }

    @Test
    void customSqlShouldRejectDmlAndScopeModeMismatch() {
        SyncTaskDefinition definition = baseDefinition();
        definition.setSyncMode("FULL");
        definition.setSyncScopeType("CUSTOM_SQL_QUERY");
        definition.setCustomSqlConfig("""
                {"sql":"update customer set name = 'unsafe'"}
                """);
        definition.setFieldMappingConfig("""
                [{"sourceField":"id","targetField":"id"}]
                """);

        SyncTaskDefinitionScopeContract contract = support.evaluate(definition);

        assertThat(contract.blockingIssueCodes())
                .contains("SYNC_SCOPE_MODE_MISMATCH", "CUSTOM_SQL_RAW_SQL_UNSAFE");
    }

    private SyncTaskDefinition baseDefinition() {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setTenantId(7L);
        definition.setProjectId(101L);
        definition.setWorkspaceId(301L);
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(10002L);
        definition.setSourceSchemaName("ods");
        definition.setSourceObjectName("customer");
        definition.setTargetSchemaName("dwd");
        definition.setTargetObjectName("customer");
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSyncMode("FULL");
        definition.setWriteStrategy("APPEND");
        return definition;
    }
}
