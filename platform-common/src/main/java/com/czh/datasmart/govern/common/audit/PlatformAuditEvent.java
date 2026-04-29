/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformAuditEvent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.audit;

import com.czh.datasmart.govern.common.context.PlatformActorType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台级审计事件。
 *
 * 审计事件是商业化数据治理平台的关键事实之一。
 * 它回答的是“谁在什么时候、以什么身份、对哪个租户的哪个资源、执行了什么动作、结果如何”。
 *
 * 当前类只是统一契约，不强制规定存储位置。
 * 后续可以有多种落地方式：
 * 1. 各领域模块先写自己的审计表；
 * 2. 通过 Kafka 投递到 observability 或 audit-center；
 * 3. 对高风险操作同步写 MySQL，异步写日志和指标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAuditEvent {

    /**
     * 链路追踪 ID，用于把审计事件和请求日志、错误响应、指标样本关联起来。
     */
    private String traceId;

    /**
     * 事件所属租户。
     */
    private Long tenantId;

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     */
    private String actorRole;

    /**
     * 操作者类型。
     */
    private PlatformActorType actorType;

    /**
     * 资源类型，例如 SYNC_TASK、DATASOURCE、QUALITY_RULE、PERMISSION_POLICY、ASSET。
     */
    private String resourceType;

    /**
     * 资源 ID。对于批量操作可以为空，并在 detailJson 中记录批量范围。
     */
    private String resourceId;

    /**
     * 动作名称，例如 CREATE、UPDATE、APPROVE、RETRY、EXPORT、MASK、DISCOVER_METADATA。
     */
    private String action;

    /**
     * 操作结果。
     */
    private PlatformAuditResult result;

    /**
     * 事件摘要，适合列表展示和快速排障。
     */
    private String summary;

    /**
     * 结构化详情 JSON。
     * 当前先用字符串保留通用性，后续如引入统一审计中心，可进一步规范 JSON schema。
     */
    private String detailJson;

    /**
     * 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 构建一个成功审计事件的便捷方法。
     */
    public static PlatformAuditEvent success(String traceId,
                                             Long tenantId,
                                             Long actorId,
                                             String actorRole,
                                             PlatformActorType actorType,
                                             String resourceType,
                                             String resourceId,
                                             String action,
                                             String summary) {
        return new PlatformAuditEvent(traceId, tenantId, actorId, actorRole, actorType, resourceType,
                resourceId, action, PlatformAuditResult.SUCCESS, summary, null, LocalDateTime.now());
    }
}
