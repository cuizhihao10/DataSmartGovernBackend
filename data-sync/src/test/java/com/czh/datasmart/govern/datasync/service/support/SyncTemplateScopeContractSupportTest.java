/**
 * @Author : Cui
 * @Date: 2026/07/05 13:45
 * @Description DataSmart Govern Backend - SyncTemplateScopeContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
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
class SyncTemplateScopeContractSupportTest {

    private final SyncTemplateScopeContractSupport support =
            new SyncTemplateScopeContractSupport(new ObjectMapper());

    @Test
    void missingScopeTypeShouldDefaultToSingleObjectForBackwardCompatibility() {
        SyncTemplate template = baseTemplate();
        template.setSyncScopeType(null);

        SyncTemplateScopeContract contract = support.evaluate(template);

        assertThat(contract.scopeType()).isEqualTo("SINGLE_OBJECT");
        assertThat(contract.singleObjectScope()).isTrue();
        assertThat(contract.executableByMinimalBridge()).isTrue();
        assertThat(contract.blockingIssueCodes()).isEmpty();
    }

    @Test
    void objectListShouldCountMappingsAndRequireDedicatedRunner() {
        SyncTemplate template = baseTemplate();
        template.setSyncScopeType("OBJECT_LIST");
        template.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject":"customer","targetObject":"customer"},
                    {"sourceObject":"orders","targetObject":"orders"}
                  ]
                }
                """);

        SyncTemplateScopeContract contract = support.evaluate(template);

        assertThat(contract.multiObjectScope()).isTrue();
        assertThat(contract.selectedObjectCount()).isEqualTo(2);
        assertThat(contract.requiresApproval()).isFalse();
        assertThat(contract.executableByMinimalBridge()).isFalse();
        assertThat(contract.issueCodes()).contains("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
    }

    @Test
    void objectListShouldAcceptCurrentWizardAndAgentObjectNameFields() {
        SyncTemplate template = baseTemplate();
        template.setSyncScopeType("OBJECT_LIST");
        template.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObjectName":"fs_test_customer_source","targetObjectName":"fs_test_customer_source"},
                    {"sourceObjectName":"fs_test_customer_target","targetObjectName":"fs_test_customer_target"}
                  ]
                }
                """);

        SyncTemplateScopeContract contract = support.evaluate(template);

        assertThat(contract.selectedObjectCount()).isEqualTo(2);
        assertThat(contract.blockingIssueCodes()).doesNotContain("OBJECT_MAPPING_IDENTIFIER_UNSAFE");
    }

    @Test
    void databaseFullShouldDefaultToControlledDiscoveryPolicy() {
        SyncTemplate template = baseTemplate();
        template.setSyncScopeType("DATABASE_FULL");
        template.setObjectMappingConfig(null);

        SyncTemplateScopeContract contract = support.evaluate(template);

        assertThat(contract.blockingIssueCodes()).isEmpty();
        assertThat(contract.warnings()).contains("DATABASE_FULL_DISCOVERY_POLICY_DEFAULTED");
        assertThat(contract.issueCodes()).contains("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        assertThat(contract.recommendedActions()).anySatisfy(action ->
                assertThat(action).contains("受控默认发现策略"));
    }

    @Test
    void customSqlShouldAcceptReadOnlySelectWithTargetAndFieldMapping() {
        SyncTemplate template = baseTemplate();
        template.setSyncMode("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setCustomSqlConfig("""
                {"sql":"select id, name from customer where status = :status"}
                """);
        template.setFieldMappingConfig("""
                [{"sourceField":"id","targetField":"id"},{"sourceField":"name","targetField":"name"}]
                """);

        SyncTemplateScopeContract contract = support.evaluate(template);

        assertThat(contract.customSqlScope()).isTrue();
        assertThat(contract.customSqlDeclared()).isTrue();
        assertThat(contract.requiresApproval()).isFalse();
        assertThat(contract.blockingIssueCodes()).isEmpty();
        assertThat(contract.issueCodes()).doesNotContain("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        assertThat(contract.executableByMinimalBridge()).isTrue();
    }

    @Test
    void customSqlShouldRejectDmlAndScopeModeMismatch() {
        SyncTemplate template = baseTemplate();
        template.setSyncMode("FULL");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setCustomSqlConfig("""
                {"sql":"update customer set name = 'unsafe'"}
                """);
        template.setFieldMappingConfig("""
                [{"sourceField":"id","targetField":"id"}]
                """);

        SyncTemplateScopeContract contract = support.evaluate(template);

        assertThat(contract.blockingIssueCodes())
                .contains("SYNC_SCOPE_MODE_MISMATCH", "CUSTOM_SQL_RAW_SQL_UNSAFE");
    }

    private SyncTemplate baseTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("customer");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer");
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode("FULL");
        template.setWriteStrategy("APPEND");
        return template;
    }
}
