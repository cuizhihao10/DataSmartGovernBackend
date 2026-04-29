/**
 * @Author : Cui
 * @Date: 2026/04/27 22:00
 * @Description DataSmart Govern Backend - RelationalMetadataValidationOutcome.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 关系型目标远程元数据校验结果。
 *
 * <p>这个结果不是直接返回给前端，而是给扫描策略消费。
 * 扫描策略会把它转成 QualityRuleTargetValidationResult，并写回规则表。
 */
@Data
public class RelationalMetadataValidationOutcome {

    /**
     * 远程元数据校验是否已实际执行。
     */
    private boolean executed;

    /**
     * 远程元数据校验是否通过。
     */
    private boolean valid;

    /**
     * 远程服务不可用时是否按配置放行。
     */
    private boolean failOpen;

    /**
     * 结论说明。
     */
    private String message;

    /**
     * 给调用方或运营人员的建议。
     */
    private List<String> suggestions = new ArrayList<>();

    public static RelationalMetadataValidationOutcome skipped(String message) {
        RelationalMetadataValidationOutcome outcome = new RelationalMetadataValidationOutcome();
        outcome.setExecuted(false);
        outcome.setValid(true);
        outcome.setMessage(message);
        return outcome;
    }

    public static RelationalMetadataValidationOutcome passed(String message) {
        RelationalMetadataValidationOutcome outcome = new RelationalMetadataValidationOutcome();
        outcome.setExecuted(true);
        outcome.setValid(true);
        outcome.setMessage(message);
        return outcome;
    }

    public static RelationalMetadataValidationOutcome failed(String message) {
        RelationalMetadataValidationOutcome outcome = new RelationalMetadataValidationOutcome();
        outcome.setExecuted(true);
        outcome.setValid(false);
        outcome.setMessage(message);
        return outcome;
    }
}
