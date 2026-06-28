/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncTemplateValidationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 同步模板连接器能力校验测试。
 *
 * <p>这组测试验证“能力矩阵已经真正进入模板校验链路”，而不是只有一个只读诊断接口：
 * 1. 旧模板不携带 connector type 时仍保持兼容；
 * 2. 新模板携带 connector type 时，会校验源端、目标端和 syncMode 是否匹配；
 * 3. 只传一端 connector type 或选择不兼容模式时必须 fail-closed。</p>
 */
class SyncTemplateValidationSupportTest {

    private final SyncTemplateValidationSupport validationSupport =
            new SyncTemplateValidationSupport(new SyncConnectorCapabilityRegistry());

    /**
     * 旧模板不携带连接器类型时仍应通过基础校验。
     *
     * <p>这是为了支持历史数据和旧前端：在 datasource-management 低敏能力查询契约打通前，
     * 不能要求所有旧调用方立即补 sourceConnectorType/targetConnectorType。</p>
     */
    @Test
    void validateTemplateShouldKeepBackwardCompatibilityWhenConnectorTypesMissing() {
        SyncTemplate template = template("FULL", null, null);

        assertThatCode(() -> validationSupport.validateTemplate(template)).doesNotThrowAnyException();
    }

    /**
     * 关系型数据库全量同步应通过连接器能力预检。
     */
    @Test
    void validateTemplateShouldAcceptCompatibleConnectorMode() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL");

        assertThatCode(() -> validationSupport.validateTemplate(template)).doesNotThrowAnyException();
    }

    /**
     * 只传源端或只传目标端连接器类型应被拒绝。
     *
     * <p>半个连接器事实无法判断数据移动方向，会导致前端或 Agent 误以为模板已经做过完整预检。</p>
     */
    @Test
    void validateTemplateShouldRejectHalfConnectorFacts() {
        SyncTemplate template = template("FULL", "MYSQL", null);

        assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateTemplate(template));
    }

    /**
     * Kafka 不应被当作传统 FULL 表同步源。
     */
    @Test
    void validateTemplateShouldRejectUnsupportedConnectorMode() {
        SyncTemplate template = template("FULL", "KAFKA", "MYSQL");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateTemplate(template));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("SOURCE_MODE_UNSUPPORTED");
    }

    @Test
    void validateTemplateShouldRejectMissingExecutableObjectBinding() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL");
        template.setSourceObjectName(null);

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateTemplate(template));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("源端对象名称不能为空");
    }

    @Test
    void validateTemplateShouldRejectConflictWriteWithoutPrimaryKey() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL");
        template.setWriteStrategy("UPSERT");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateTemplate(template));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("primaryKeyField");
    }

    @Test
    void validateTemplateShouldRejectIncrementalModeWithoutIncrementalField() {
        SyncTemplate template = template("INCREMENTAL_TIME", "MYSQL", "POSTGRESQL");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> validationSupport.validateTemplate(template));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("incrementalField");
    }

    private SyncTemplate template(String syncMode, String sourceConnectorType, String targetConnectorType) {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(20001L);
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("customer");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer");
        template.setSyncMode(syncMode);
        template.setWriteStrategy("APPEND");
        template.setSourceConnectorType(sourceConnectorType);
        template.setTargetConnectorType(targetConnectorType);
        return template;
    }
}
