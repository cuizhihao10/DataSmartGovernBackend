package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncPermissionNotificationProperties.java
 * @Version:1.0.0
 *
 * 权限治理通知配置。
 *
 * 这组配置控制“权限变更审批链路里的通知对象如何产生、何时投递、何时提醒、何时升级”。
 * 把这些参数做成配置而不是写死在服务里，是为了让不同客户环境可以根据组织习惯调整审批 SLA：
 * 1. 小团队可能希望 10 分钟未审批就提醒；
 * 2. 大企业可能希望 4 小时后提醒、24 小时后升级；
 * 3. 私有化部署可能暂时只使用站内通知和日志；
 * 4. 云化部署则可能接企业微信、飞书、邮件或统一消息中心。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.sync-permission-notification")
public class SyncPermissionNotificationProperties {

    /**
     * 提交权限变更申请后，是否给审批角色生成“待审批”通知。
     * 关闭后，审批单仍会创建，但审批人只能通过列表筛选发现待办，不适合真实商业产品长期使用。
     */
    private Boolean notifyApproverRolesOnSubmit = true;

    /**
     * 审批完成后，是否给申请人生成“审批结果”通知。
     * 这能让申请人不用反复刷新申请列表，也能为后续前端消息中心提供清晰的个人通知来源。
     */
    private Boolean notifyRequesterOnDecision = true;

    /**
     * 创建通知对象后是否立即尝试投递。
     * 当前默认开启，配合 INTERNAL_LOG 通道可以在本地开发阶段直接看到通知闭环。
     */
    private Boolean autoDispatchOnCreate = true;

    /**
     * 默认通知通道。
     * 当前支持 NONE 和 INTERNAL_LOG；未来可以扩展到站内消息、邮件、飞书、企业微信等通道。
     */
    private String defaultChannel = "INTERNAL_LOG";

    /**
     * 是否启用审批超时提醒扫描。
     * 这里控制的是“扫描能力”本身，手动 API 和定时调度都应该尊重这个开关。
     */
    private Boolean approvalReminderEnabled = true;

    /**
     * 待审批单创建多久以后触发普通提醒。
     * 默认 30 分钟，适合开发和演示；生产环境可以根据企业审批 SLA 拉长到数小时。
     */
    private Integer approvalReminderAfterSeconds = 1800;

    /**
     * 待审批单创建多久以后触发升级提醒。
     * 升级提醒的业务含义是“原审批角色迟迟没有处理，需要更高治理角色关注”。
     */
    private Integer approvalEscalationAfterSeconds = 7200;

    /**
     * 单次提醒扫描最多处理多少张待审批申请单。
     * 这个限制可以防止历史积压审批单过多时，一次扫描创建大量通知、拖慢服务或刷屏管理员。
     */
    private Integer approvalReminderScanLimit = 100;

    /**
     * 升级提醒默认发送给哪个角色。
     * 当前用平台管理员承接跨租户治理升级，后续也可以改为租户管理员、值班组或服务账号。
     */
    private String approvalEscalationRecipientRole = "PLATFORM_ADMINISTRATOR";

    /**
     * 是否启用后台定时提醒扫描。
     * 默认关闭，是为了避免本地开发环境没有明确审批数据时持续空转；手动 API 仍然可以按需触发。
     */
    private Boolean reminderSchedulerEnabled = false;

    /**
     * 定时提醒扫描的 fixedDelay 秒数。
     * fixedDelay 表示“上一轮执行完成后再等待固定时间”，比 fixedRate 更适合有数据库写入的巡检任务。
     */
    private Integer reminderSchedulerFixedDelaySeconds = 300;

    /**
     * 定时任务使用的系统操作人 ID。
     * 当前用 0 表示系统内置账号，未来接入统一账号体系后可以替换成 SERVICE_ACCOUNT。
     */
    private Long reminderSchedulerActorId = 0L;

    /**
     * 定时任务使用的系统角色。
     * 默认平台管理员，是因为审批提醒可能跨租户扫描；生产环境也可以替换成专门的服务账号角色。
     */
    private String reminderSchedulerActorRole = "PLATFORM_ADMINISTRATOR";

    /**
     * 定时任务使用的租户上下文。
     * 平台级扫描通常为空；如果希望某个部署只扫描单租户，可以在这里写入租户 ID。
     */
    private Long reminderSchedulerActorTenantId;

    /**
     * 定时任务备注。
     * 备注会进入 SyncActionRequest，方便后续审计日志区分人工触发和系统触发。
     */
    private String reminderSchedulerNote = "SYSTEM_SCHEDULED_PERMISSION_REMINDER_SCAN";

    /**
     * 返回安全的普通提醒阈值。
     * 下限设置为 60 秒，是为了避免配置错误导致调度器每秒重复创建或检查通知。
     */
    public int getSafeApprovalReminderAfterSeconds() {
        return approvalReminderAfterSeconds == null ? 1800 : Math.max(60, approvalReminderAfterSeconds);
    }

    /**
     * 返回安全的升级提醒阈值。
     * 升级阈值应当不小于普通提醒阈值，否则用户会先看到升级再看到普通提醒，业务体验会很混乱。
     */
    public int getSafeApprovalEscalationAfterSeconds() {
        int reminderSeconds = getSafeApprovalReminderAfterSeconds();
        int configuredSeconds = approvalEscalationAfterSeconds == null ? 7200 : Math.max(60, approvalEscalationAfterSeconds);
        return Math.max(reminderSeconds, configuredSeconds);
    }

    /**
     * 返回安全的单轮扫描上限。
     */
    public int getSafeApprovalReminderScanLimit() {
        return approvalReminderScanLimit == null ? 100 : Math.max(1, approvalReminderScanLimit);
    }

    /**
     * 返回 Spring @Scheduled fixedDelayString 可使用的毫秒值。
     */
    public long getReminderSchedulerFixedDelayMillis() {
        int safeSeconds = reminderSchedulerFixedDelaySeconds == null ? 300 : Math.max(30, reminderSchedulerFixedDelaySeconds);
        return safeSeconds * 1000L;
    }
}
