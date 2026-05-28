/**
 * @Author : Cui
 * @Date: 2026/05/07 21:18
 * @Description DataSmart Govern Backend - PermissionOutboxOperationAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 权限 outbox 人工操作审计支撑组件。
 *
 * <p>permission-admin 不只是“权限配置后台”，它还承担权限策略变更后的可靠投递与可追溯能力。
 * 当管理员人工重试或忽略 outbox 事件时，这类动作本身不会直接出现在业务权限表里；
 * 如果没有审计记录，后续排查 gateway 缓存未刷新、租户权限不一致、Kafka 故障补偿等问题时，
 * 很难回答“谁在什么时候对哪条事件做了什么操作”。
 *
 * <p>因此这里把审计记录创建逻辑从 PermissionOperationsServiceImpl 中拆出：
 * 1. Service Impl 只关注权限校验、状态变更和返回结果；
 * 2. 本组件专注把 before/after 快照落到审计表；
 * 3. 后续如果审计要同时写入 Kafka、OpenSearch 或归档库，也可以在这个支撑类内扩展，
 *    不需要让核心运维服务继续膨胀。
 */
@Component
@RequiredArgsConstructor
public class PermissionOutboxOperationAuditSupport {

    /**
     * 平台全局租户 ID。
     *
     * <p>outbox 事件理论上都应该带租户；这里保留兜底值，是为了避免历史数据或异常数据导致审计记录无法落库。
     */
    private static final long PLATFORM_TENANT_ID = 0L;

    /**
     * 审计资源类型：权限 outbox 事件。
     *
     * <p>审计表是通用表，resourceType 用来区分“菜单、角色、策略、outbox 事件”等不同治理对象。
     */
    private static final String RESOURCE_TYPE_OUTBOX = "PERMISSION_OUTBOX_EVENT";

    /**
     * 审计动作：人工重试 outbox 事件。
     */
    private static final String ACTION_RETRY_OUTBOX = "RETRY_PERMISSION_OUTBOX_EVENT";

    /**
     * 审计动作：人工忽略 outbox 事件。
     */
    private static final String ACTION_IGNORE_OUTBOX = "IGNORE_PERMISSION_OUTBOX_EVENT";

    /**
     * 审计结果：动作成功。
     *
     * <p>失败动作通常会通过异常返回给调用方；只有数据库状态已经变更成功后，才会调用本组件写成功审计。
     */
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";

    /**
     * 审计记录 Mapper。
     *
     * <p>本组件直接写审计表，不再经过 PermissionOperationsServiceImpl 中转，避免服务实现类承担过多细节。
     */
    private final PermissionAuditRecordMapper auditRecordMapper;

    /**
     * 记录 outbox 人工重试审计。
     *
     * @param actorContext 当前操作者上下文，包含租户、角色、用户和 traceId
     * @param before       重试前的 outbox 事件快照
     * @param after        重试后的 outbox 事件快照
     * @param reason       管理员填写或系统补齐的重试原因
     */
    public void saveRetryAudit(PermissionActorContext actorContext,
                               PermissionEventOutbox before,
                               PermissionEventOutbox after,
                               String reason) {
        saveOutboxOperationAudit(actorContext, ACTION_RETRY_OUTBOX, before, after, reason);
    }

    /**
     * 记录 outbox 人工忽略审计。
     *
     * @param actorContext 当前操作者上下文
     * @param before       忽略前的 outbox 事件快照
     * @param after        忽略后的 outbox 事件快照
     * @param reason       忽略原因，商业化产品中通常要求必填或进入审批流
     */
    public void saveIgnoreAudit(PermissionActorContext actorContext,
                                PermissionEventOutbox before,
                                PermissionEventOutbox after,
                                String reason) {
        saveOutboxOperationAudit(actorContext, ACTION_IGNORE_OUTBOX, before, after, reason);
    }

    /**
     * 记录 outbox 人工操作审计。
     *
     * <p>这里把 before/after 状态写入 detailJson。
     * 这样当未来有人问“为什么这条事件没有继续自动重试”或“谁把 DEAD 事件重新发送了”时，
     * 审计中心可以直接回答，而不需要翻数据库 binlog 或应用日志。
     */
    private void saveOutboxOperationAudit(PermissionActorContext actorContext,
                                          String action,
                                          PermissionEventOutbox before,
                                          PermissionEventOutbox after,
                                          String reason) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(actorContext == null ? null : actorContext.traceId());
        auditRecord.setTenantId(after == null ? PLATFORM_TENANT_ID : normalizeTenantId(after.getTenantId()));
        auditRecord.setActorId(actorContext == null ? null : actorContext.actorId());
        auditRecord.setActorRole(actorContext == null ? null : normalizeCode(actorContext.actorRole()));
        auditRecord.setResourceType(RESOURCE_TYPE_OUTBOX);
        auditRecord.setResourceId(after == null ? null : "permission_event_outbox:" + after.getId());
        auditRecord.setAction(action);
        auditRecord.setResult(AUDIT_RESULT_SUCCESS);
        auditRecord.setSummary(reason);
        auditRecord.setDetailJson(outboxOperationDetail(before, after));
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    /**
     * 构造 outbox 操作审计详情。
     *
     * <p>审计详情采用轻量 JSON 字符串，便于管理后台展示 before/after 对比。
     * 未来如果审计字段继续增多，可以升级为 Jackson 序列化 DTO，避免手写字符串。
     */
    private String outboxOperationDetail(PermissionEventOutbox before, PermissionEventOutbox after) {
        return "{"
                + "\"before\":" + outboxSnapshot(before) + ","
                + "\"after\":" + outboxSnapshot(after)
                + "}";
    }

    /**
     * 构造 outbox 关键字段快照。
     *
     * <p>这里只记录排障最关键的字段：事件 ID、事件类型、租户、状态、尝试次数和最后错误。
     * 不直接写 payload，是因为 payload 可能较大，也可能包含权限策略明细，后续需要结合脱敏策略再决定是否展示。
     */
    private String outboxSnapshot(PermissionEventOutbox event) {
        if (event == null) {
            return "null";
        }
        return "{"
                + "\"id\":\"" + nullSafe(event.getId()) + "\","
                + "\"eventId\":\"" + jsonEscape(event.getEventId()) + "\","
                + "\"eventType\":\"" + jsonEscape(event.getEventType()) + "\","
                + "\"tenantId\":\"" + nullSafe(event.getTenantId()) + "\","
                + "\"status\":\"" + jsonEscape(event.getStatus()) + "\","
                + "\"attemptCount\":\"" + nullSafe(event.getAttemptCount()) + "\","
                + "\"lastError\":\"" + jsonEscape(event.getLastError()) + "\""
                + "}";
    }

    /**
     * 编码规范化。
     */
    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 租户 ID 归一化。
     */
    private Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    /**
     * JSON 字符串转义。
     */
    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 空值安全字符串。
     */
    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
