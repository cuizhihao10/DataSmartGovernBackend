package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalProperties.java
 * @Version:1.0.0
 *
 * 同步权限治理域里的审批规则配置。
 * 这一层的目标不是直接替代未来独立的 permission-admin 工作流模块，
 * 而是先在 datasource-management 内部沉淀出一套“审批人矩阵如何推导”的显式规则。
 *
 * 当前主要回答四类问题：
 * 1. 平台级权限变更默认应该由哪些角色审批；
 * 2. 租户级权限变更默认应该由哪些角色审批；
 * 3. 某些绑定类型是否需要更严格的审批角色；
 * 4. 当申请人本身已经是高权限角色时，是否还需要继续升级给更高层级审批。
 *
 * 这样设计的价值在于：
 * - 提交申请时可以把“当时要求哪些角色审批”固化到申请单快照里；
 * - 审批时可以解释为什么当前审批人能批、为什么另一个角色不能批；
 * - 后面即使迁移到更完整的审批中心，这一层配置语义也能比较平滑地映射过去。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.sync-permission-approval")
public class SyncPermissionApprovalProperties {

    /**
     * 是否允许申请人自己审批自己发起的权限变更申请。
     * 对商用品治理后台来说，默认应关闭，否则“先申请、再审批”会退化成形式流程。
     */
    private boolean allowSelfApproval = false;

    /**
     * 是否启用审批委托机制。
     * 开启后，系统会在直接审批角色不满足时，继续检查是否存在有效的委托规则。
     */
    private boolean delegateApprovalEnabled = true;

    /**
     * 平台全局权限变更的默认审批角色列表。
     * 这里通常会更严格，因为平台全局策略会影响所有租户。
     */
    private List<String> platformGlobalApproverRoles = new ArrayList<>();

    /**
     * 租户级权限变更的默认审批角色列表。
     * 这类变更默认影响单租户，因此可以比平台级更宽松，但仍应保留治理门槛。
     */
    private List<String> tenantScopedApproverRoles = new ArrayList<>();

    /**
     * 按绑定类型覆写的审批角色列表。
     * key 使用 bindingType，例如 MENU、ROUTE、DATA_SCOPE。
     * 如果某个绑定类型配置了专属审批角色，则优先使用这里的规则。
     */
    private Map<String, List<String>> bindingTypeApproverRoles = new LinkedHashMap<>();

    /**
     * 按申请人角色收紧审批角色的配置。
     * 典型场景：
     * - 如果申请人本身是 TENANT_ADMINISTRATOR，则希望强制 PLATFORM_ADMINISTRATOR 审批；
     * - 如果申请人是 OPERATOR，则要求至少由 TENANT_ADMINISTRATOR 审批。
     *
     * 这一层是“升级审批”的含义，不是简单追加。
     * 实际解析时会优先取更严格的交集，没有交集时再退回到该配置本身，避免出现策略空洞。
     */
    private Map<String, List<String>> requesterRoleEscalationApproverRoles = new LinkedHashMap<>();
}
