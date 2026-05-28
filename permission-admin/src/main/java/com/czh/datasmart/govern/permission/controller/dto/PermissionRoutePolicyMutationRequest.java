/**
 * @Author : Cui
 * @Date: 2026/04/26 20:33
 * @Description DataSmart Govern Backend - PermissionRoutePolicyMutationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 路由策略创建或更新请求。
 *
 * <p>路由策略是 permission-admin 里非常关键的安全配置：
 * 它决定 gateway 在请求进入各业务微服务之前，是允许、拒绝，还是未来转入审批/二次确认。
 * 因此这里对字段做显式校验，避免空路径、非法方法、非法效果等数据进入策略表。
 *
 * <p>当前已经从“纯 URL 路由策略”演进到“URL + 业务语义策略”：
 * resourceType 用于说明被保护的业务资源，例如 SYNC_EXECUTION；
 * action 用于说明被保护的业务动作，例如 CALLBACK。
 * 两者都允许为空，表示该策略是旧式路径级兜底策略。
 */
@Data
public class PermissionRoutePolicyMutationRequest {

    /**
     * 策略所属租户。
     *
     * <p>0 表示平台全局默认策略。租户管理员只能管理自己租户的策略，不能写入 0 或其他租户。
     * 平台管理员可以管理全局策略和跨租户策略。
     */
    private Long tenantId = 0L;

    /**
     * 策略名称。
     *
     * <p>建议使用能让管理员读懂的中文或英文短语，例如“租户管理员管理权限”。
     */
    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "长度不能超过 128")
    private String policyName;

    /**
     * 角色编码。
     *
     * <p>例如 ORDINARY_USER、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR。
     * 服务层会继续校验该角色是否存在，避免策略绑定到无效角色。
     */
    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "长度不能超过 64")
    private String roleCode;

    /**
     * HTTP 方法。
     *
     * <p>ANY 表示匹配所有方法。商业系统中，建议优先精确配置 GET/POST/PUT/DELETE，
     * 只有确实要授予整段接口能力时才使用 ANY。
     */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY", message = "必须是 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS 或 ANY")
    private String httpMethod = "ANY";

    /**
     * 路径模式。
     *
     * <p>当前匹配器支持完全匹配和 /** 后缀通配，例如 /api/task/**。
     * 后续如果要支持 /api/task/{id}/retry 这类模板，可演进到 Spring PathPatternParser。
     */
    @NotBlank(message = "不能为空")
    @Size(max = 256, message = "长度不能超过 256")
    private String pathPattern;

    /**
     * 资源类型。
     *
     * <p>可为空。为空时表示该策略不区分资源类型，只按角色、方法和路径匹配。
     * 真实商业配置中，建议对高风险入口显式填写，例如：
     * SYNC_EXECUTION、SYNC_INCIDENT、SYNC_OPERATION、TASK_OPERATION。
     */
    @Size(max = 64, message = "长度不能超过 64")
    private String resourceType;

    /**
     * 业务动作。
     *
     * <p>可为空。为空时表示该策略不区分业务动作。
     * 对执行器回调、人工恢复、事故关闭、审批等动作，建议显式填写 CALLBACK、RECOVER、CLOSE、APPROVE 等。
     */
    @Size(max = 64, message = "长度不能超过 64")
    private String action;

    /**
     * 策略效果。
     *
     * <p>ALLOW 表示允许访问，DENY 表示显式拒绝。
     * 如果 ALLOW 和 DENY 同时命中，权限系统应优先 DENY。
     */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "ALLOW|DENY", message = "必须是 ALLOW 或 DENY")
    private String effect;

    /**
     * 优先级，数值越大越优先。
     *
     * <p>保留较宽范围，便于未来区分平台默认策略、租户覆盖策略、临时封禁策略和应急策略。
     */
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 100000, message = "不能大于 100000")
    private Integer priority = 100;

    /**
     * 是否启用。
     *
     * <p>创建时默认启用；如果需要先配置后审核，可先传 false，未来接入审批流后再由审批结果启用。
     */
    private Boolean enabled = true;

    /**
     * 策略说明。
     *
     * <p>权限策略长期会由管理员维护，说明字段非常重要。
     * 它能帮助后来者理解“为什么这条策略存在”，减少误删或误放权。
     */
    @Size(max = 1000, message = "长度不能超过 1000")
    private String description;
}
