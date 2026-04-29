package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncPermissionReminderScanResult.java
 * @Version:1.0.0
 *
 * 权限审批提醒扫描结果。
 *
 * 这个 DTO 面向运营人员、租户管理员和后续的定时调度器，用来回答一次“审批单超时提醒巡检”到底做了什么。
 * 真实商业产品里，审批提醒不能只靠日志，因为日志无法直接支撑前端工作台、审计复盘、告警联动和 SLA 统计。
 * 因此这里把候选数量、创建数量、跳过数量、涉及申请单和通知 ID 都显式返回，方便后续扩展成：
 * 1. 前端按钮触发后的执行反馈；
 * 2. 定时任务执行日志；
 * 3. 运维报表中的审批 SLA 指标；
 * 4. 与统一通知中心或 observability 模块对接的数据契约。
 */
@Data
public class SyncPermissionReminderScanResult {

    /**
     * 本轮扫描到的待审批申请单数量。
     * 注意它不是“创建通知数量”，因为同一个申请单可能因为已经创建过提醒或升级通知而被跳过。
     */
    private Integer candidateCount;

    /**
     * 本轮新创建的普通提醒通知数量。
     * 普通提醒通常发给原审批角色，语义是“这张单已经等了一段时间，请尽快处理”。
     */
    private Integer reminderCreatedCount;

    /**
     * 本轮新创建的升级通知数量。
     * 升级通知通常发给更高治理角色，例如平台管理员，语义是“这张单已经明显超时，需要管理侧介入”。
     */
    private Integer escalationCreatedCount;

    /**
     * 因为同类型、同申请单、同接收角色的通知已经存在而跳过的数量。
     * 这个字段能避免用户误以为扫描没有工作，也能解释为什么候选单数量大于新建通知数量。
     */
    private Integer skippedDuplicateCount;

    /**
     * 本轮扫描到的权限变更申请单 ID。
     * 保留这组 ID 有助于排查某次调度是否覆盖到了预期的审批单。
     */
    private List<Long> scannedChangeRequestIds;

    /**
     * 本轮创建的普通提醒通知 ID。
     */
    private List<Long> reminderNotificationIds;

    /**
     * 本轮创建的升级提醒通知 ID。
     */
    private List<Long> escalationNotificationIds;

    /**
     * 本轮扫描生成结果的时间。
     * 后续如果接入定时任务执行记录或 observability 指标，可以直接用它做时间轴锚点。
     */
    private LocalDateTime generatedAt;
}
