/**
 * @Author : Cui
 * @Date: 2026/05/07 21:30
 * @Description DataSmart Govern Backend - SyncTemplateValidationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 同步模板业务校验支撑组件。
 *
 * <p>这个类负责“模板是否具备合法业务语义”的 fail-fast 校验，不直接读取源库、不连接目标库、
 * 不执行 SQL，也不解析真实字段元数据。它处在模板创建、模板校验、任务创建等控制面入口之前，
 * 用来保证明显危险或明显不完整的配置不会进入后续任务状态机。</p>
 *
 * <p>校验分为三层：</p>
 * <p>1. 基础结构校验：源/目标 datasourceId 必须存在且不能相同，同步模式必须是平台认识的枚举；</p>
 * <p>2. 同步范围校验：单对象、多对象、整 schema、整库、自定义 SQL 各自需要不同配置，统一交给
 * {@link SyncTemplateScopeContractSupport} 解析，避免多个入口各写一套规则；</p>
 * <p>3. 能力矩阵校验：当模板携带源端/目标端 connector type 时，检查当前连接器组合是否支持该同步模式。</p>
 *
 * <p>非常重要的边界：{@code SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE} 不会在模板创建阶段阻断。
 * 原因是产品需要允许用户先配置“多表/全库/自定义 SQL”草稿、走审批、做预检查；真正执行时，
 * worker plan 和 run-once bridge 会继续 fail-closed，直到专用 runner 完成。</p>
 */
@Component
public class SyncTemplateValidationSupport {

    /**
     * 当前阶段采用保守标识符白名单。
     *
     * <p>这不是说所有数据库对象名只能这样命名，而是因为当前最小 runner 还没有完整的方言级 quote/escape
     * 与元数据白名单能力。没有这些保护之前，允许点号、引号、空格或表达式进入对象名字段，会把 SQL 注入风险
     * 推给底层 runner，因此这里先收紧。</p>
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;
    private final SyncTemplateScopeContractSupport scopeContractSupport;

    /**
     * 测试兼容构造器。
     *
     * <p>部分旧单元测试会直接 new 本类，因此保留无参构造器，内部使用默认能力矩阵和默认范围契约解析器。
     * Spring 运行时会优先使用带参数构造器注入容器 Bean。</p>
     */
    public SyncTemplateValidationSupport() {
        this(new SyncConnectorCapabilityRegistry(), new SyncTemplateScopeContractSupport());
    }

    /**
     * 兼容旧测试的构造器。
     *
     * @param connectorCapabilityRegistry 连接器能力注册表。
     */
    public SyncTemplateValidationSupport(SyncConnectorCapabilityRegistry connectorCapabilityRegistry) {
        this(connectorCapabilityRegistry, new SyncTemplateScopeContractSupport());
    }

    /**
     * Spring 注入构造器。
     *
     * @param connectorCapabilityRegistry 连接器能力注册表，判断源端、目标端、同步模式是否兼容。
     * @param scopeContractSupport 同步范围契约解析器，判断单对象、多对象、全库、自定义 SQL 配置是否安全完整。
     */
    @Autowired
    public SyncTemplateValidationSupport(SyncConnectorCapabilityRegistry connectorCapabilityRegistry,
                                         SyncTemplateScopeContractSupport scopeContractSupport) {
        this.connectorCapabilityRegistry = connectorCapabilityRegistry;
        this.scopeContractSupport = scopeContractSupport;
    }

    /**
     * 创建任务或执行前校验同步模板。
     *
     * <p>该方法会直接抛出业务异常，因此适合用于“必须通过才能继续”的入口，例如创建任务、手动校验、
     * 执行前二次确认。面向前端展示的“列出所有问题”场景应使用 preview/precheck，它们会返回 issueCodes。</p>
     *
     * @param template 待校验模板，必须已经过租户/项目可见性检查。
     */
    public void validateTemplate(SyncTemplate template) {
        if (template == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步模板不能为空");
        }
        if (template.getSourceDatasourceId() == null || template.getTargetDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "源数据源和目标数据源不能为空");
        }
        if (template.getSourceDatasourceId().equals(template.getTargetDatasourceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "源数据源和目标数据源不能相同");
        }

        SyncMode mode = resolveMode(template.getSyncMode());
        if (!mode.isUserSelectableTransferMode()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "syncMode 不是可新建任务的一级传输模式: " + mode.name()
                            + "；当前仅支持 FULL、SCHEDULED_BATCH、SCHEDULED_FULL、CUSTOM_SQL_QUERY、CDC_STREAMING。"
                            + " 失败回放、历史补数、离线导入和离线导出应走任务详情、执行历史、错误样本或制品流程的专用入口。");
        }
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template.getWriteStrategy());
        validateScopeAndObjectBinding(template);
        validateCheckpointAndWriteStrategy(template, mode, writeStrategy);
        validateConnectorCompatibility(template, mode);
    }

    /**
     * 解析同步模式。
     *
     * <p>同步模式决定“怎么搬数据”：全量、增量、CDC、回放、补数、自定义 SQL 等。它与
     * {@code syncScopeType} 不同，后者决定“搬哪些对象”。两者都必须明确，避免后续 runner 猜测。</p>
     */
    public SyncMode resolveMode(String syncMode) {
        if (syncMode == null || syncMode.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步模式不能为空");
        }
        try {
            return SyncMode.valueOf(syncMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的同步模式: " + syncMode);
        }
    }

    /**
     * 解析写入策略。
     *
     * <p>写入策略影响目标端冲突处理、幂等性、回放和补数语义。空值会按历史兼容回落到 APPEND，
     * 但未知值必须 fail-fast，不能交给 worker 猜测。</p>
     */
    public SyncWriteStrategy resolveWriteStrategy(String writeStrategy) {
        try {
            return SyncWriteStrategy.fromValue(writeStrategy);
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, exception.getMessage());
        }
    }

    /**
     * 校验同步范围和对象定位字段。
     *
     * <p>单对象同步必须声明 sourceObjectName/targetObjectName；多对象和全库同步则依赖 objectMappingConfig；
     * 自定义 SQL 同步依赖 customSqlConfig 和 targetObjectName。这里不再“一刀切”要求 sourceObjectName，
     * 否则会把合法的多表/SQL 配置错误地当成单表模板处理。</p>
     */
    private void validateScopeAndObjectBinding(SyncTemplate template) {
        SyncTemplateScopeContract scopeContract = scopeContractSupport.evaluate(template);
        if (scopeContract.hasBlockingIssues()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步范围配置未通过安全校验，issueCodes=" + scopeContract.blockingIssueCodes()
                            + "，recommendedActions=" + scopeContract.recommendedActions());
        }
        validateOptionalIdentifier("源端对象名称", template.getSourceObjectName());
        validateOptionalIdentifier("目标端对象名称", template.getTargetObjectName());
        validateOptionalIdentifier("源端 schema 名称", template.getSourceSchemaName());
        validateOptionalIdentifier("目标端 schema 名称", template.getTargetSchemaName());
    }

    /**
     * 校验 checkpoint 与写入策略所需字段。
     *
     * <p>增量同步必须有 incrementalField，否则无法推进 checkpoint；UPSERT/INSERT_IGNORE/REPLACE 等
     * 冲突感知写入必须有 primaryKeyField，否则无法判断目标端幂等写入边界。</p>
     */
    private void validateCheckpointAndWriteStrategy(SyncTemplate template,
                                                    SyncMode mode,
                                                    SyncWriteStrategy writeStrategy) {
        if ((mode == SyncMode.INCREMENTAL_TIME || mode == SyncMode.INCREMENTAL_ID)
                && !hasText(template.getIncrementalField())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "增量同步必须声明 incrementalField，用于 checkpoint 推进和断点续行");
        }
        validateOptionalIdentifier("增量字段", template.getIncrementalField());
        if (writeStrategy.requiresConflictKey() && !hasText(template.getPrimaryKeyField())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    writeStrategy.name() + " 写入策略必须声明 primaryKeyField，用于目标端冲突判断和幂等写入");
        }
        validateOptionalIdentifier("主键或冲突字段", template.getPrimaryKeyField());
    }

    /**
     * 校验连接器能力矩阵。
     *
     * <p>能力矩阵只消费 connector type 和 syncMode，不读取真实 datasource 凭据。它用于回答：
     * “这类源端、这类目标端、这种同步模式，在产品能力上是否允许”。真实连接测试、权限、元数据比对和行数估算
     * 属于后续 precheck/runner 的职责。</p>
     */
    private void validateConnectorCompatibility(SyncTemplate template, SyncMode mode) {
        String sourceConnectorType = normalizeOptionalCode(template.getSourceConnectorType());
        String targetConnectorType = normalizeOptionalCode(template.getTargetConnectorType());
        boolean sourcePresent = sourceConnectorType != null;
        boolean targetPresent = targetConnectorType != null;
        if (!sourcePresent && !targetPresent) {
            return;
        }
        if (sourcePresent != targetPresent) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "源端连接器类型和目标端连接器类型必须同时提供，避免同步模板只携带半个能力事实");
        }
        SyncConnectorCompatibilityView compatibility =
                connectorCapabilityRegistry.checkCompatibility(sourceConnectorType, targetConnectorType, mode.name());
        if (!compatibility.supported()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步模板连接器能力预检未通过，issueCodes=" + compatibility.issueCodes()
                            + "，recommendedActions=" + compatibility.recommendedActions());
        }
    }

    private String normalizeOptionalCode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private void validateOptionalIdentifier(String fieldName, String value) {
        if (!hasText(value)) {
            return;
        }
        if (!SAFE_IDENTIFIER.matcher(value.trim()).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    fieldName + "只能包含字母、数字和下划线，并且必须以字母或下划线开头。当前阶段暂不接受带空格、引号、点号或 SQL 片段的对象标识");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
