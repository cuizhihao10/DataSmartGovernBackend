/**
 * @Author : Cui
 * @Date: 2026/07/08 13:06
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardContractResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步任务创建向导合同测试。
 *
 * <p>这组测试不是为了验证 UI，而是为了保护后端给前端和 Agent 工具输出的“产品合同”。
 * 新建任务页面已经不应该暴露 tenantId/projectId/workspaceId、raw JSON、审批事实、runMode 等内部概念；
 * 同时它又需要知道如何自动拉取元数据、如何生成字段映射建议、SQL 自定义模式有哪些差异。
 * 如果这些规则只写在口头说明或前端代码里，后端后续重构时很容易漏掉，因此这里用单测把合同固定下来。</p>
 */
class SyncTaskCreateWizardContractSupportTest {

    /**
     * 创建向导合同必须显式暴露元数据发现与字段映射建议入口。
     *
     * <p>业务意义：前端进入对象映射步骤后应自动调用这些接口加载 schema/table/field 摘要，
     * 而不是让用户手动点击“获取元数据”或直接填写 objectMappingConfig/fieldMappingConfig JSON。
     * 这也是本项目把 DataX 风格底层执行计划和用户可理解表单解耦的重要边界。</p>
     */
    @Test
    void buildContractShouldExposeCreateWizardMetadataApis() {
        SyncTaskCreateWizardContractSupport support = new SyncTaskCreateWizardContractSupport(
                new SyncDataScopeSupport(),
                new SyncTransferModeCatalogSupport(),
                new SyncTaskScheduleConfigSupport(new ObjectMapper())
        );

        SyncTaskCreateWizardContractResponse contract = support.buildContract(projectActorContext());

        assertThat(contract.contractVersion()).isEqualTo("datasmart.sync-task.create-wizard.v5");
        assertThat(contract.metadataDiscovery()).isNotNull();
        assertThat(contract.metadataDiscovery().objectDiscoveryApi())
                .isEqualTo("POST /sync-tasks/create-wizard/metadata/objects/discover");
        assertThat(contract.metadataDiscovery().fieldMappingSuggestionApi())
                .isEqualTo("POST /sync-tasks/create-wizard/metadata/field-mappings/suggest");
        assertThat(contract.metadataDiscovery().customSqlCheckApi())
                .isEqualTo("POST /sync-tasks/create-wizard/sql/check");
        assertThat(contract.metadataDiscovery().filterModes())
                .containsExactly("TABLE", "SCHEMA", "SCHEMA_AND_TABLE", "CATALOG", "ALL");
        assertThat(contract.metadataDiscovery().customSqlRules())
                .anySatisfy(rule -> assertThat(rule).contains("CUSTOM_SQL_QUERY").contains("目标表"));
        assertThat(contract.datasourceUsage().allowedUsageValues())
                .containsExactly("SOURCE", "TARGET");
        assertThat(contract.datasourceUsage().usageRules())
                .noneSatisfy(rule -> assertThat(rule).contains("可同时出现在源端和目标端候选列表"));
        assertThat(contract.transferModes())
                .extracting(item -> item.mode(), item -> item.displayName())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FULL", "全量传输"),
                        org.assertj.core.groups.Tuple.tuple("SCHEDULED_BATCH", "定期批量"),
                        org.assertj.core.groups.Tuple.tuple("SCHEDULED_FULL", "定期全量"),
                        org.assertj.core.groups.Tuple.tuple("CUSTOM_SQL_QUERY", "SQL语句"),
                        org.assertj.core.groups.Tuple.tuple("CDC_STREAMING", "实时")
                );
        assertThat(contract.hiddenLowLevelFields())
                .contains("rawObjectMappingJsonEditor", "rawFieldMappingJsonEditor");
    }

    private SyncActorContext projectActorContext() {
        return new SyncActorContext(
                10L,
                101L,
                10001L,
                1001L,
                "PROJECT_OWNER",
                "trace-create-wizard-contract",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }
}
