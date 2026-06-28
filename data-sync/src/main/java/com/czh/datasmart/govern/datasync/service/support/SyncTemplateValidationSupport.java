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
 * 同步模板校验支撑组件。
 *
 * <p>模板校验不只是 Bean Validation。
 * Bean Validation 能判断“字段有没有传”，但产品校验还要判断“这种同步模式是否需要 field mapping、是否允许源和目标相同、
 * 是否需要 checkpoint 配置、是否需要审批”等更靠近业务的规则。
 *
 * <p>当前已经接入连接器能力矩阵；模板创建链路会先通过 {@link SyncTemplateConnectorFactResolver}
 * 尝试从 datasource-management 低敏能力快照补全 sourceConnectorType/targetConnectorType，再进入本校验组件。
 * 后续仍可继续接入字段映射、checkpoint 策略、写入策略和元数据发现结果。
 */
@Component
public class SyncTemplateValidationSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;

    /**
     * 默认构造器主要服务于现有单元测试和极简本地启动。
     *
     * <p>Spring 正常运行时会优先使用带 {@link Autowired} 的构造器注入同一个
     * SyncConnectorCapabilityRegistry Bean；这里保留无参构造，是为了不让大量已有 service 测试因为新增能力矩阵而被迫重写夹具。
     * 这也是一种渐进式收敛策略：先把产品规则接进校验链路，同时不破坏既有测试和调用方。</p>
     */
    public SyncTemplateValidationSupport() {
        this(new SyncConnectorCapabilityRegistry());
    }

    /**
     * Spring 注入构造器。
     *
     * @param connectorCapabilityRegistry 连接器能力注册表，负责判断源端、目标端和同步模式是否兼容。
     */
    @Autowired
    public SyncTemplateValidationSupport(SyncConnectorCapabilityRegistry connectorCapabilityRegistry) {
        this.connectorCapabilityRegistry = connectorCapabilityRegistry;
    }

    /**
     * 创建或运行前校验模板。
     *
     * <p>当前校验分两层：</p>
     * <ul>
     *     <li>基础结构校验：源/目标 datasourceId、源目标不能相同、syncMode 必须是平台认识的枚举；</li>
     *     <li>连接器能力校验：当模板携带 sourceConnectorType/targetConnectorType 时，进一步检查该连接器组合是否支持 syncMode。</li>
     * </ul>
     *
     * <p>为什么连接器字段仍然保持兼容可选：
     * 创建模板时如果启用了 datasource-management 能力快照，服务端会按 datasourceId 自动补全 connector type；
     * 如果本地开发或历史调用方临时关闭了快照补全，两端都缺省时仍只执行基础校验，避免旧模板立即不可用。
     * 但只要进入本方法时只剩一端 connector type，就说明能力事实不完整，必须 fail-closed 拒绝；
     * 两端都有 connector type 时则必须通过能力矩阵预检。</p>
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
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template.getWriteStrategy());
        validateExecutableObjectBinding(template);
        validateCheckpointAndWriteStrategy(template, mode, writeStrategy);
        validateConnectorCompatibility(template, mode);
    }

    /**
     * 解析同步模式。
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
     * 解析并归一化写入策略。
     *
     * <p>写入策略会影响目标端冲突处理、幂等、回放和补数行为，因此不能让执行器在运行时自行猜测。
     * 这里允许空值回落到 APPEND，是为了兼容历史模板；但如果传入了未知策略，则必须 fail-fast，避免后续 runner
     * 因为不认识策略而走到错误的写入语义。</p>
     */
    public SyncWriteStrategy resolveWriteStrategy(String writeStrategy) {
        try {
            return SyncWriteStrategy.fromValue(writeStrategy);
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, exception.getMessage());
        }
    }

    /**
     * 校验执行必需的对象定位字段。
     *
     * <p>data-sync 过去只校验 datasourceId 和 syncMode，这只能证明“源端/目标端数据源存在引用”，不能证明真实 worker
     * 知道读哪个对象、写哪个对象。商业化同步平台必须在进入任务创建或执行前明确对象定位，否则 worker 只能从 JSON 配置、
     * 前端约定或人工说明里猜测，最终会导致不可审计、不可复现和难以排障。</p>
     *
     * <p>这里采用保守的安全标识符规则：只允许字母、数字和下划线，并要求以字母或下划线开头。原因不是所有数据库都只能这样命名，
     * 而是当前阶段还没有引入统一的 identifier quote/escape 层；在没有安全转义抽象前，宁可限制输入，也不能把复杂对象名直接交给
     * 后续 SQL 生成器。</p>
     */
    private void validateExecutableObjectBinding(SyncTemplate template) {
        requireObjectName("源端对象名称", template.getSourceObjectName());
        requireObjectName("目标端对象名称", template.getTargetObjectName());
        validateOptionalIdentifier("源端 schema 名称", template.getSourceSchemaName());
        validateOptionalIdentifier("目标端 schema 名称", template.getTargetSchemaName());
    }

    /**
     * 校验 checkpoint 与写入策略之间的必备字段。
     *
     * <p>增量同步没有 incrementalField 就无法推进 checkpoint；UPSERT/INSERT_IGNORE/REPLACE 没有 primaryKeyField
     * 就无法做冲突判断。与其让 worker 在执行中失败，不如在模板校验阶段就返回明确的业务错误。</p>
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
     * 校验连接器能力与同步模式是否匹配。
     *
     * <p>这个方法只消费 connector type 和 syncMode，不读取 datasource 实例、不读取连接串、不做真实连接测试。
     * 它的定位是“产品能力预检”：例如 Kafka 不适合传统 FULL 表同步、文件目标不适合 CDC_STREAMING。
     * 真正执行前还需要 datasource-management 连接测试、permission-admin 权限、字段映射、任务状态机和 worker lease。</p>
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

    private void requireObjectName(String fieldName, String value) {
        if (!hasText(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    fieldName + "不能为空，真实执行器必须明确源端和目标端对象定位");
        }
        validateOptionalIdentifier(fieldName, value);
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
