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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 同步模板校验支撑组件。
 *
 * <p>模板校验不只是 Bean Validation。
 * Bean Validation 能判断“字段有没有传”，但产品校验还要判断“这种同步模式是否需要 field mapping、是否允许源和目标相同、
 * 是否需要 checkpoint 配置、是否需要审批”等更靠近业务的规则。
 *
 * <p>当前先落基础规则，后续可以逐步接入连接器能力矩阵和 datasource-management 的元数据发现结果。
 */
@Component
public class SyncTemplateValidationSupport {

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
     * <p>为什么连接器字段暂时可选：
     * 当前 data-sync 与 datasource-management 的 datasourceId -> connectorType 低敏查询契约还没有打通。
     * 为了保持旧模板和旧接口可用，缺省 connector type 时只执行基础校验；一旦调用方提供任意一个 connector type，
     * 就必须同时提供源端和目标端，并通过能力矩阵预检。</p>
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
}
