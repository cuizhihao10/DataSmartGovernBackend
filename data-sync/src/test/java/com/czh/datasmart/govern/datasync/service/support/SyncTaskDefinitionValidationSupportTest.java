/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncTaskDefinitionValidationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 同步任务定义连接器能力校验测试。
 *
 * <p>这组测试验证“能力矩阵已经真正进入模板校验链路”，而不是只有一个只读诊断接口：
 * 1. 旧模板不携带 connector type 时仍保持兼容；
 * 2. 新模板携带 connector type 时，会校验源端、目标端和 syncMode 是否匹配；
 * 3. 只传一端 connector type 或选择不兼容模式时必须 fail-closed。</p>
 */
class SyncTaskDefinitionValidationSupportTest {

    private final SyncTaskDefinitionValidationSupport validationSupport =
            new SyncTaskDefinitionValidationSupport(new SyncConnectorCapabilityRegistry());

    /**
     * 旧模板不携带连接器类型时仍应通过基础校验。
     *
     * <p>这是为了支持历史数据和旧前端：在 datasource-management 低敏能力查询契约打通前，
     * 不能要求所有旧调用方立即补 sourceConnectorType/targetConnectorType。</p>
     */
    @Test
    void validateDefinitionShouldKeepBackwardCompatibilityWhenConnectorTypesMissing() {
        SyncTaskDefinition definition = definition("FULL", null, null);

        assertThatCode(() -> validationSupport.validateDefinition(definition)).doesNotThrowAnyException();
    }

    /**
     * 关系型数据库全量同步应通过连接器能力预检。
     */
    @Test
    void validateDefinitionShouldAcceptCompatibleConnectorMode() {
        SyncTaskDefinition definition = definition("FULL", "MYSQL", "POSTGRESQL");

        assertThatCode(() -> validationSupport.validateDefinition(definition)).doesNotThrowAnyException();
    }

    /**
     * 只传源端或只传目标端连接器类型应被拒绝。
     *
     * <p>半个连接器事实无法判断数据移动方向，会导致前端或 Agent 误以为模板已经做过完整预检。</p>
     */
    @Test
    void validateDefinitionShouldRejectHalfConnectorFacts() {
        SyncTaskDefinition definition = definition("FULL", "MYSQL", null);

        assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateDefinition(definition));
    }

    /**
     * Kafka 不应被当作传统 FULL 表同步源。
     */
    @Test
    void validateDefinitionShouldRejectUnsupportedConnectorMode() {
        SyncTaskDefinition definition = definition("FULL", "KAFKA", "MYSQL");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateDefinition(definition));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("SOURCE_MODE_UNSUPPORTED");
    }

    @Test
    void validateDefinitionShouldRejectMissingExecutableObjectBinding() {
        SyncTaskDefinition definition = definition("FULL", "MYSQL", "POSTGRESQL");
        definition.setSourceObjectName(null);

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateDefinition(definition));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("源端对象名称不能为空");
    }

    @Test
    void validateDefinitionShouldRejectConflictWriteWithoutPrimaryKey() {
        SyncTaskDefinition definition = definition("FULL", "MYSQL", "POSTGRESQL");
        definition.setWriteStrategy("UPSERT");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateDefinition(definition));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("primaryKeyField");
    }

    @Test
    void validateDefinitionShouldRejectInternalLegacyModeAsUserTransferMode() {
        SyncTaskDefinition definition = definition("INCREMENTAL_TIME", "MYSQL", "POSTGRESQL");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateDefinition(definition));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("不是可新建任务的一级传输模式");
    }

    private SyncTaskDefinition definition(String syncMode, String sourceConnectorType, String targetConnectorType) {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setTenantId(7L);
        definition.setProjectId(101L);
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(20001L);
        definition.setSourceSchemaName("ods");
        definition.setSourceObjectName("customer");
        definition.setTargetSchemaName("dwd");
        definition.setTargetObjectName("customer");
        definition.setSyncMode(syncMode);
        definition.setWriteStrategy("APPEND");
        definition.setSourceConnectorType(sourceConnectorType);
        definition.setTargetConnectorType(targetConnectorType);
        return definition;
    }
}
