/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - FileQualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import org.springframework.stereotype.Component;

/**
 * 文件对象质量扫描策略。
 *
 * <p>文件质量规则适用于 CSV、Excel、JSON、Parquet、对象存储路径等场景。
 * 与数据库表不同，文件扫描需要额外考虑格式识别、编码、表头、文件大小、分片读取和解析失败恢复。
 *
 * <p>当前阶段只要求 targetObject 能定位文件或对象路径，例如 minio://bucket/path/file.csv。
 */
@Component
public class FileQualityScanStrategy extends AbstractQualityScanStrategy {

    @Override
    public String strategyCode() {
        return "FILE_OBJECT_PROFILE_SCAN";
    }

    @Override
    public boolean supports(QualityRuleTargetType targetType) {
        return QualityRuleTargetType.FILE_OBJECT.equals(targetType);
    }

    @Override
    public QualityRuleTargetValidationResult validate(QualityRule rule) {
        if (!hasText(rule.getTargetObject())) {
            return invalidResult(rule, "文件质量规则缺少文件路径或对象路径",
                    "请在 targetObject 中填写文件路径，例如 minio://quality/input/customer.csv。");
        }
        QualityRuleTargetValidationResult result = validResult(rule, "文件目标结构校验通过，可作为后续文件采样和格式检测目标");
        result.getSuggestions().add("后续需要补充文件格式、字符集、表头行、最大读取行数、文件大小上限和脱敏策略。");
        return result;
    }
}
