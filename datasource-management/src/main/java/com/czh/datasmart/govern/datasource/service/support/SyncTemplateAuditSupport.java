/**
 * @Author : Cui
 * @Date: 2026/05/05 23:25
 * @Description DataSmart Govern Backend - SyncTemplateAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 同步模板审计支持组件。
 *
 * <p>模板审计和任务审计虽然共用 `sync_audit_record` 表，但业务语义并不完全相同：
 * 任务审计关注“任务生命周期如何流转”，模板审计关注“可复用同步配置由谁创建、修改、校验”。
 * 因此这里单独提供模板侧审计入口，避免 `SyncTemplateServiceImpl` 直接感知审计表结构，
 * 也避免把模板创建、模板更新、智能校验等业务动作和 payload 字符串拼装耦合在主服务里。</p>
 *
 * <p>后续如果产品继续演进为商业化审计中心，这个组件可以优先升级为：
 * 结构化审计事件、字段级脱敏、敏感配置变更比对、审批流事件、SIEM 投递或 compliance-center 消费事件。
 * 主服务只要继续调用这里的方法即可，不需要关心审计落库还是事件发布。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplateAuditSupport {

    /**
     * 审计 payload 最大长度。
     *
     * <p>当前数据库字段承载轻量 JSON 字符串，不适合无限写入大段错误堆栈或完整配置。
     * 统一截断可以减少审计表膨胀，也能降低未来审计详情页渲染和日志同步压力。</p>
     */
    private static final int PAYLOAD_MAX_LENGTH = 1000;

    /**
     * 同步审计记录 Mapper。
     *
     * <p>当前模板审计仍写入 datasource-management 本地审计表。
     * 未来如果抽象为独立审计服务，可以在这里切换为消息发送或远程审计客户端。</p>
     */
    private final SyncAuditRecordMapper syncAuditRecordMapper;

    /**
     * 记录模板级审计事件。
     *
     * @param tenantId 模板所属租户。模板是可复用配置资产，租户维度用于隔离审计查询。
     * @param action 模板动作，例如创建、更新、智能校验。
     * @param actorId 操作人 ID，用于回答“谁触发了这次配置变更或校验”。
     * @param actorRole 操作人角色，用于后续排查是否存在越权或异常高危操作。
     * @param payload 轻量审计上下文，建议只放关键字段和统计结果，不放完整连接串、密码或大对象。
     */
    public void recordTemplateAudit(Long tenantId,
                                    SyncAuditAction action,
                                    Long actorId,
                                    String actorRole,
                                    String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(tenantId);
        record.setActionType(action.name());
        record.setActorId(actorId);
        record.setActorRole(actorRole);
        record.setActionPayload(truncate(payload));
        syncAuditRecordMapper.insert(record);
    }

    /**
     * 构造轻量 JSON payload。
     *
     * <p>这里保留简单 key/value 形式，是为了不改变现有数据库结构和审计前端读取方式。
     * 它不是通用 JSON 序列化器，只用于审计摘要；业务配置完整体仍应存放在模板表自己的字段中。</p>
     */
    public String buildPayload(Object... pairs) {
        StringBuilder builder = new StringBuilder("{");
        for (int index = 0; index < pairs.length; index += 2) {
            if (index > 0) {
                builder.append(", ");
            }
            Object key = pairs[index];
            Object value = index + 1 < pairs.length ? pairs[index + 1] : "";
            builder.append("\"").append(key).append("\":\"")
                    .append(escape(String.valueOf(value))).append("\"");
        }
        builder.append("}");
        return truncate(builder.toString());
    }

    /**
     * 截断超长审计文本。
     *
     * <p>真实生产中审计 payload 通常还需要字段级白名单、敏感字段脱敏和 schema 版本号。
     * 当前阶段先用统一长度保护数据库和页面，后续再升级为结构化审计事件。</p>
     */
    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > PAYLOAD_MAX_LENGTH ? value.substring(0, PAYLOAD_MAX_LENGTH) : value;
    }

    /**
     * 转义审计 payload 中的基础 JSON 字符。
     *
     * <p>如果模板名称、对象名或错误消息中包含双引号、反斜杠，不做转义会导致审计详情无法稳定解析。</p>
     */
    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
