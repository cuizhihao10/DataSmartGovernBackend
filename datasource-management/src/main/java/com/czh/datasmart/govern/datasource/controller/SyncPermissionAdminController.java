package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.controller.dto.ApproveSyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncPermissionApprovalDelegateRuleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionApprovalDelegateRuleView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionGovernanceNotificationView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionPolicyChangeRequestView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionPolicySnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionReminderScanResult;
import com.czh.datasmart.govern.datasource.service.SyncPermissionApprovalDelegateRuleService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionBindingService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionNotificationService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionPolicyChangeRequestService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionAdminController.java
 * @Version:1.0.0
 *
 * 本地同步权限治理控制器。
 * 这一层承载的是 datasource-management 在当前阶段的“权限治理控制面”，
 * 不是未来完整 permission-admin 模块的最终形态，但已经开始具备比较真实的企业后台语义。
 *
 * 当前分成四组入口：
 * 1. 权限快照：告诉前端当前角色能看到什么、能做什么；
 * 2. 权限绑定：查看或替换数据库里的角色绑定；
 * 3. 权限变更申请：把高风险变更从“直接改”升级成“先申请、再审批、再执行”；
 * 4. 审批委托规则：解决审批人值班轮转、请假代班、组织调整时的代批问题。
 *
 * 这样拆分的价值在于，用户、运营、审计、管理员都能围绕同一套治理对象协同，
 * 而不是把权限逻辑散落在多个业务接口里各自硬编码。
 */
@RestController
@RequestMapping("/sync/admin/permissions")
@RequiredArgsConstructor
public class SyncPermissionAdminController {

    private final SyncPermissionPolicyService syncPermissionPolicyService;
    private final SyncPermissionBindingService syncPermissionBindingService;
    private final SyncPermissionPolicyChangeRequestService syncPermissionPolicyChangeRequestService;
    private final SyncPermissionApprovalDelegateRuleService syncPermissionApprovalDelegateRuleService;
    private final SyncPermissionNotificationService syncPermissionNotificationService;

    /**
     * 查看本地权限策略快照。
     * 如果传入 targetTenantId，则按“查看该租户视角的权限快照”解释；
     * 但只有平台管理员允许真正跨租户切换视角。
     */
    @GetMapping("/snapshot")
    public ResponseEntity<ApiResponse<SyncPermissionPolicySnapshot>> getPermissionSnapshot(
            @RequestParam(required = false) Long actorId,
            @RequestParam String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) Long targetTenantId) {
        return ResponseEntity.ok(ApiResponse.success("本地权限策略快照查询成功",
                syncPermissionPolicyService.buildSnapshot(actorId, actorRole, actorTenantId, targetTenantId)));
    }

    /**
     * 查看数据库里的权限绑定记录。
     * 这组接口用于解释“为什么当前角色会看到这些菜单、路由和数据范围”。
     */
    @GetMapping("/bindings")
    public ResponseEntity<ApiResponse<List<SyncPermissionBindingView>>> listBindings(
            @RequestParam(required = false) Long actorId,
            @RequestParam String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) Long targetTenantId,
            @RequestParam String targetRole,
            @RequestParam(required = false) String bindingType,
            @RequestParam(defaultValue = "false") Boolean includeDisabled) {
        return ResponseEntity.ok(ApiResponse.success("权限绑定记录查询成功",
                syncPermissionBindingService.listBindings(actorId, actorRole, actorTenantId,
                        targetTenantId, targetRole, bindingType, includeDisabled)));
    }

    /**
     * 按“角色 + 绑定类型”整体替换一组数据库绑定。
     * 这更适合矩阵式权限后台，而不是零散逐条编辑。
     */
    @PostMapping("/bindings/replace")
    public ResponseEntity<ApiResponse<SyncPermissionBindingReplaceResult>> replaceBindings(
            @Valid @RequestBody SyncPermissionBindingReplaceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限绑定替换成功",
                syncPermissionBindingService.replaceBindings(request)));
    }

    /**
     * 提交权限绑定变更申请。
     * 当某次策略调整属于高风险治理动作时，应先进入申请单，再由审批链路决定是否落库。
     */
    @PostMapping("/binding-change-requests")
    public ResponseEntity<ApiResponse<SyncPermissionPolicyChangeRequestView>> submitBindingChangeRequest(
            @Valid @RequestBody CreateSyncPermissionPolicyChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限绑定变更申请提交成功",
                syncPermissionPolicyChangeRequestService.submitChangeRequest(request)));
    }

    /**
     * 查询权限绑定变更申请列表。
     * 支持按租户、目标角色、绑定类型和申请状态过滤。
     */
    @GetMapping("/binding-change-requests")
    public ResponseEntity<ApiResponse<List<SyncPermissionPolicyChangeRequestView>>> listBindingChangeRequests(
            @RequestParam(required = false) Long actorId,
            @RequestParam String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) Long targetTenantId,
            @RequestParam(required = false) String targetRole,
            @RequestParam(required = false) String bindingType,
            @RequestParam(required = false) String requestStatus) {
        return ResponseEntity.ok(ApiResponse.success("权限绑定变更申请查询成功",
                syncPermissionPolicyChangeRequestService.listChangeRequests(
                        actorId, actorRole, actorTenantId, targetTenantId, targetRole, bindingType, requestStatus)));
    }

    /**
     * 审批权限绑定变更申请。
     * 当前版本在审批通过后会立即执行绑定替换，形成“审批即执行”的最小闭环。
     */
    @PostMapping("/binding-change-requests/{id}/approve")
    public ResponseEntity<ApiResponse<SyncPermissionPolicyChangeRequestView>> approveBindingChangeRequest(
            @PathVariable Long id,
            @Valid @RequestBody ApproveSyncPermissionPolicyChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限绑定变更申请审批完成",
                syncPermissionPolicyChangeRequestService.approveChangeRequest(id, request)));
    }

    /**
     * 创建权限审批委托规则。
     * 用于显式声明“某个审批人可以在某段时间内把自己的审批资格委托给谁”。
     */
    @PostMapping("/approval-delegate-rules")
    public ResponseEntity<ApiResponse<SyncPermissionApprovalDelegateRuleView>> createApprovalDelegateRule(
            @Valid @RequestBody CreateSyncPermissionApprovalDelegateRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限审批委托规则创建成功",
                syncPermissionApprovalDelegateRuleService.createDelegateRule(request)));
    }

    /**
     * 查询权限审批委托规则。
     * 这组数据通常会出现在治理后台的“审批代理/代班配置”界面。
     */
    @GetMapping("/approval-delegate-rules")
    public ResponseEntity<ApiResponse<List<SyncPermissionApprovalDelegateRuleView>>> listApprovalDelegateRules(
            @RequestParam(required = false) Long actorId,
            @RequestParam String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) Long targetTenantId,
            @RequestParam(required = false) Long delegatorId,
            @RequestParam(required = false) Long delegateId,
            @RequestParam(defaultValue = "false") Boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success("权限审批委托规则查询成功",
                syncPermissionApprovalDelegateRuleService.listDelegateRules(
                        actorId, actorRole, actorTenantId, targetTenantId, delegatorId, delegateId, activeOnly)));
    }

    /**
     * 禁用一条权限审批委托规则。
     * 这里选择“禁用而不是物理删除”，是为了保留完整治理轨迹。
     */
    @PostMapping("/approval-delegate-rules/{id}/disable")
    public ResponseEntity<ApiResponse<SyncPermissionApprovalDelegateRuleView>> disableApprovalDelegateRule(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限审批委托规则禁用成功",
                syncPermissionApprovalDelegateRuleService.disableDelegateRule(id, request)));
    }

    /**
     * 查询权限治理通知。
     * 当前会同时返回“发给当前角色的待审批通知”和“明确发给当前人的结果通知”。
     */
    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<IPage<SyncPermissionGovernanceNotificationView>>> listGovernanceNotifications(
            @RequestParam Long actorId,
            @RequestParam String actorRole,
            @RequestParam Long actorTenantId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long targetTenantId,
            @RequestParam(required = false) String notificationType,
            @RequestParam(required = false) String notificationStatus,
            @RequestParam(defaultValue = "false") Boolean unreadOnly) {
        return ResponseEntity.ok(ApiResponse.success("权限治理通知查询成功",
                syncPermissionNotificationService.pageNotifications(
                        new Page<>(current, size),
                        actorId,
                        actorRole,
                        actorTenantId,
                        targetTenantId,
                        notificationType,
                        notificationStatus,
                        unreadOnly)));
    }

    /**
     * 将一条权限治理通知标记为已读。
     * 这一步让角色待办和个人结果通知都具备最小可用的闭环能力。
     */
    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<ApiResponse<SyncPermissionGovernanceNotificationView>> markGovernanceNotificationRead(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限治理通知已标记为已读",
                syncPermissionNotificationService.markAsRead(id, request)));
    }

    /**
     * 扫描超时待审批申请单并生成提醒通知。
     *
     * 这个接口面向两类场景：
     * 1. 管理员在后台手动点击“扫描审批超时”，立即推动遗漏审批回到待办视野；
     * 2. 后台调度器复用同一服务方法，周期性生成普通提醒和升级提醒。
     *
     * 它不会直接批准、驳回或修改权限绑定，只会创建治理通知，因此属于“审批运营治理”能力。
     */
    @PostMapping("/notifications/scan-reminders")
    public ResponseEntity<ApiResponse<SyncPermissionReminderScanResult>> scanGovernanceNotificationReminders(
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("权限审批超时提醒扫描完成",
                syncPermissionNotificationService.scanApprovalReminders(request)));
    }
}
