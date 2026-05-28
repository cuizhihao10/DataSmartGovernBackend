/**
 * @Author : Cui
 * @Date: 2026/05/07 21:30
 * @Description DataSmart Govern Backend - SyncTemplateValidationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
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

    /**
     * 创建或运行前校验模板。
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
        resolveMode(template.getSyncMode());
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
}
