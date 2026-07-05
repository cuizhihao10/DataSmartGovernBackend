/**
 * @Author : Cui
 * @Date: 2026/06/29 23:46
 * @Description DataSmart Govern Backend - SyncFieldMappingExecutionContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段映射执行契约解析测试。
 *
 * <p>这组测试验证的是“进入真实 runner 前，data-sync 能否把字段映射 JSON 安全转换成内部执行契约”。
 * 它不验证真实字段是否存在于数据库，也不验证字段类型，因为那些能力属于 datasource-management 元数据校验和后续 connector runtime。</p>
 */
class SyncFieldMappingExecutionContractSupportTest {

    private final SyncFieldMappingExecutionContractSupport support =
            new SyncFieldMappingExecutionContractSupport(new ObjectMapper());

    @Test
    void directSameNameMappingsShouldProduceRunnableContract() {
        String config = """
                [
                  {"sourceField":"id","targetField":"id"},
                  {"source":"name","target":"name"}
                ]
                """;

        SyncFieldMappingExecutionContract contract = support.parse(config, "id");

        assertThat(contract.isParseable()).isTrue();
        assertThat(contract.isHasMappings()).isTrue();
        assertThat(contract.getMappingCount()).isEqualTo(2);
        assertThat(contract.getSelectedColumns()).containsExactly("id", "name");
        assertThat(contract.getWriteColumns()).containsExactly("id", "name");
        assertThat(contract.getPrimaryKeyColumns()).containsExactly("id");
        assertThat(contract.isRequiresFieldRenameTransform()).isFalse();
        assertThat(contract.getIssueCodes()).isEmpty();
        assertThat(contract.directlyRunnableByMinimalBridge()).isTrue();
    }

    @Test
    void wrappedMappingsAndAliasesShouldBeAcceptedButRenameRequiresTransform() {
        String config = """
                {
                  "mappings": [
                    {"from":"customer_id","to":"id"},
                    {"sourceColumn":"phone_masked","targetColumn":"phone_masked"}
                  ]
                }
                """;

        SyncFieldMappingExecutionContract contract = support.parse(config, "id");

        assertThat(contract.isParseable()).isTrue();
        assertThat(contract.getSelectedColumns()).containsExactly("customer_id", "phone_masked");
        assertThat(contract.getWriteColumns()).containsExactly("id", "phone_masked");
        assertThat(contract.getPrimaryKeyColumns()).containsExactly("id");
        assertThat(contract.isRequiresFieldRenameTransform()).isTrue();
        assertThat(contract.getWarnings()).contains("FIELD_RENAME_TRANSFORM_REQUIRED");
        assertThat(contract.directlyRunnableByMinimalBridge()).isTrue();
    }

    @Test
    void invalidJsonShouldReturnIssueCodeWithoutLeakingOriginalPayload() {
        String unsafePayload = "{ \"mappings\": [ { \"sourceField\": \"secret_phone\", ";

        SyncFieldMappingExecutionContract contract = support.parse(unsafePayload, "id");

        assertThat(contract.isParseable()).isFalse();
        assertThat(contract.getIssueCodes()).containsExactly("FIELD_MAPPING_PARSE_FAILED");
        assertThat(contract.getIssueCodes().toString())
                .doesNotContain("secret_phone")
                .doesNotContain("mappings");
    }

    @Test
    void unsafeIdentifierShouldBlockContract() {
        String config = """
                [{"sourceField":"id;drop table user","targetField":"id"}]
                """;

        SyncFieldMappingExecutionContract contract = support.parse(config, "id");

        assertThat(contract.isParseable()).isTrue();
        assertThat(contract.getIssueCodes()).contains("FIELD_MAPPING_IDENTIFIER_UNSAFE", "SELECTED_COLUMNS_NOT_RESOLVED");
        assertThat(contract.directlyRunnableByMinimalBridge()).isFalse();
    }
}
