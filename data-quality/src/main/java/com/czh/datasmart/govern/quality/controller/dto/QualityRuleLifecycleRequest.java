/**
 * @Author : Cui
 * @Date: 2026/04/27 21:30
 * @Description DataSmart Govern Backend - QualityRuleLifecycleRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 质量规则生命周期动作请求。
 *
 * <p>启用、停用、归档、恢复都属于规则治理动作。
 * 原因字段不是技术必需，但对商业系统非常重要：当质量规则突然不再执行或被重新启用时，
 * 运营人员和审计人员需要知道背后的业务解释。
 */
@Data
public class QualityRuleLifecycleRequest {

    /**
     * 操作原因。
     */
    @Size(max = 500, message = "操作原因不能超过 500 个字符")
    private String reason;
}
