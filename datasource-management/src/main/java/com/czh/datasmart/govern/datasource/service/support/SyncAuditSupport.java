/**
 * @Author : Cui
 * @Date: 2026/05/05 18:58
 * @Description DataSmart Govern Backend - SyncAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 同步任务审计支持组件。
 *
 * <p>该组件集中处理同步控制面的审计记录写入、轻量 JSON payload 构造和运营文本截断。
 * 这些逻辑原本位于 `SyncTaskServiceImpl`，会导致主服务在每个状态动作中同时关注：
 * 1. 任务状态怎么变；
 * 2. 执行记录怎么变；
 * 3. 审计 JSON 怎么拼；
 * 4. 文本字段怎么截断。
 *
 * <p>对于真实商业化产品，审计是一条独立能力线：
 * 1. 需要可追溯，能回答谁在什么时候做了什么；
 * 2. 需要可扩展，未来可能同步到统一审计中心、合规中心、SIEM 或数据安全平台；
 * 3. 需要可治理，payload 里不能无限写入大文本、敏感信息或不可解析格式。
 *
 * <p>因此这里先把审计写入和 payload 构造独立出来。
 * 当前仍然采用轻量字符串 JSON，是为了不改变数据库结构和现有接口语义；
 * 后续可以进一步升级为 Jackson 序列化、结构化审计 DTO、字段级脱敏和统一审计事件。
 */
@Component
@RequiredArgsConstructor
public class SyncAuditSupport {

    /**
     * 审计 payload 和运营说明的最大长度。
     *
     * <p>当前多个实体字段用于列表展示和人工排查，不适合存储无限长的异常堆栈或备注。
     * 统一截断可以降低数据库行膨胀、页面渲染压力和日志外泄风险。
     */
    private static final int TEXT_MAX_LENGTH = 1000;

    /**
     * 同步任务审计 Mapper。
     *
     * <p>当前审计表仍属于 datasource-management 模块本地表。
     * 后续如果拆出统一 audit/compliance 模块，可以先让这里发布事件，再由审计模块消费落库。
     */
    private final SyncAuditRecordMapper syncAuditRecordMapper;

    /**
     * 写入同步任务审计记录。
     *
     * <p>调用方只需要传入任务、执行记录、动作、操作者和 payload，
     * 本方法负责把任务维度的 tenantId/taskId/actionType 等固定字段补齐。
     */
    public void recordAudit(SyncTask task,
                            Long executionId,
                            SyncAuditAction action,
                            Long actorId,
                            String actorRole,
                            String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(task.getTenantId());
        record.setSyncTaskId(task.getId());
        record.setExecutionId(executionId);
        record.setActionType(action.name());
        record.setActorId(actorId);
        record.setActorRole(actorRole);
        record.setActionPayload(payload);
        syncAuditRecordMapper.insert(record);
    }

    /**
     * 查询某个同步任务的审计记录。
     *
     * <p>这里放在审计组件中，是为了让任务服务不直接感知审计表查询细节。
     * 后续如果审计记录迁移到统一审计中心，任务服务仍然只需要调用同一个组件方法。
     */
    public List<SyncAuditRecord> listAuditRecords(Long taskId) {
        return syncAuditRecordMapper.selectList(new LambdaQueryWrapper<SyncAuditRecord>()
                .eq(SyncAuditRecord::getSyncTaskId, taskId)
                .orderByDesc(SyncAuditRecord::getCreateTime));
    }

    /**
     * 构造审计 payload。
     *
     * <p>当前为了保持轻量，不引入额外 DTO 或 JSON 序列化依赖，
     * 采用 key/value 变长参数构造一个简单 JSON 字符串。
     * 注意：这里不是通用 JSON 工具，而是同步任务审计的过渡性 payload 构造器。
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
     * 截断进入数据库和审计 payload 的长文本。
     *
     * <p>该方法会被任务备注、异常摘要、触发原因、检查点字段等复用。
     * 它属于当前阶段的安全兜底，未来可以升级为按字段配置不同长度和脱敏规则。
     */
    public String truncate(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > TEXT_MAX_LENGTH ? value.substring(0, TEXT_MAX_LENGTH) : value;
    }

    /**
     * 转义 JSON 字符串中的基础特殊字符。
     *
     * <p>当前 payload 是轻量 JSON 字符串，如果不处理反斜杠和双引号，
     * 审计详情在前端或日志系统中可能无法被稳定解析。
     */
    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
