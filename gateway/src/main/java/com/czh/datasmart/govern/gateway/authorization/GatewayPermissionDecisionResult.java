/**
 * @Author : Cui
 * @Date: 2026/04/25 23:20
 * @Description DataSmart Govern Backend - GatewayPermissionDecisionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import lombok.Data;

import java.util.List;

/**
 * 网关侧权限判定结果。
 *
 * <p>字段与 permission-admin 的响应 data 保持同名，方便 Jackson 反序列化。
 * 只保留 gateway 当前需要的字段，避免把权限中心内部模型完整泄露到网关模块。
 */
@Data
public class GatewayPermissionDecisionResult {

    /**
     * 是否允许访问。
     */
    private Boolean allowed;

    /**
     * 判定原因。
     */
    private String reason;

    /**
     * 命中的路由策略 ID。
     */
    private Long matchedRoutePolicyId;

    /**
     * 命中的路由策略效果。
     */
    private String routeEffect;

    /**
     * 数据范围级别。
     */
    private String dataScopeLevel;

    /**
     * 数据范围表达式。
     *
     * <p>该字段来自 permission-admin 的数据范围策略，例如 `owner_id = ${actorId}` 或 `tenant_id = ${tenantId}`。
     * gateway 不解析表达式，只负责可信透传；真正把表达式落到 SQL 条件里的责任在业务服务。
     */
    private String dataScopeExpression;

    /**
     * permission-admin 已经计算出的项目授权快照。
     *
     * <p>网关不理解项目成员表，也不解析 `${actorProjectIds}` 占位符。
     * 它只把权限中心返回的项目 ID 集合转换为统一 Header，交给 data-sync 等业务模块按自身字段落地。
     */
    private List<Long> authorizedProjectIds;

    /**
     * permission-admin 已经计算出的项目角色授权快照。
     *
     * <p>该字段用于补齐项目级动作权限：项目 ID 只解决“能不能看见”，项目角色解决“能不能写入、授权、执行”。
     * gateway 不解释角色强度，只把它转换成可信 Header 交给业务服务；业务服务根据自身资源动作判断
     * READER、MANAGER、OWNER、SERVICE 分别能做什么。</p>
     */
    private List<PlatformAuthorizedProjectRole> authorizedProjectRoles;

    /**
     * 是否需要审批。
     */
    private Boolean approvalRequired;
}
