/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - AgentToolActionPayloadRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工具动作受控载荷登记记录。
 *
 * <p>这个对象是 `agent-payload:` 链路的服务端事实，而不是对外展示 DTO。它解决的是一个商业化 Agent Host
 * 必须面对的问题：模型、MCP tools/call、A2A action 或前端确认页只能提交 payloadReference，不能把原始
 * arguments、SQL、prompt、样本数据、凭证和内部 endpoint 直接塞进 outbox。writer 在写入 durable command 前，
 * 必须能确认“这个引用确实由服务端登记过、属于当前 tenant/project/actor/run/tool，并且后续执行器可以按策略读取”。</p>
 *
 * <p>当前 5.57 只先登记“载荷信封元数据”，即 payload body 还没有真正物化；这是为了让系统先具备可恢复命令的
 * 作用域校验能力，后续再把真实参数保存到 MySQL/Redis/对象存储或加密 vault。字段上刻意区分
 * {@code payloadBodyAvailable} 与 {@code payloadBody}，避免维护者误以为所有 `agent-payload:` 都已经可以执行。</p>
 *
 * @param payloadReference 服务端受控引用，格式为 `agent-payload:{runId}/{payloadKey}`。
 * @param runId Agent run ID，用于防止跨 run 复用载荷。
 * @param payloadKey run 内部的载荷键，通常对应工具动作或参数快照用途。
 * @param tenantId 租户 ID 字符串，来源于 proposal，用于多租户隔离。
 * @param projectId 项目 ID 字符串，来源于 proposal，用于项目级数据范围隔离。
 * @param actorId 操作者或服务账号 ID，来源于 proposal，用于 SELF 范围和审计追踪。
 * @param toolName 工具名，不包含工具参数。
 * @param graphId 来源执行图 ID，用于把载荷和执行图版本绑定。
 * @param contractId durable action contract ID，用于把载荷和出箱契约绑定。
 * @param payloadPolicy 载荷策略，例如 REFERENCE_ONLY，后续可扩展为加密、脱敏、只读等策略。
 * @param argumentNames 低敏参数名列表，可用于执行器检查必填参数是否存在；当前未物化时为空。
 * @param sensitiveArgumentNames 已识别的敏感参数名列表；该列表只包含字段名，不包含字段值。
 * @param payloadBodyAvailable 真实 payload body 是否已物化到服务端存储。
 * @param payloadSizeBytes 真实 payload body 的字节数；未物化时为 0。
 * @param metadataDigest 元数据摘要，用于排障时确认“登记事实没有被篡改”，不是 payload 正文摘要。
 * @param createdAt 登记时间。
 * @param expiresAt 过期时间；执行器读取时必须拒绝过期载荷。
 * @param payloadBody 真实 payload body。当前第一阶段通常为空，未来执行器只应在服务端内部读取。
 */
public record AgentToolActionPayloadRecord(
        String payloadReference,
        String runId,
        String payloadKey,
        String tenantId,
        String projectId,
        String actorId,
        String toolName,
        String graphId,
        String contractId,
        String payloadPolicy,
        List<String> argumentNames,
        List<String> sensitiveArgumentNames,
        Boolean payloadBodyAvailable,
        Integer payloadSizeBytes,
        String metadataDigest,
        Instant createdAt,
        Instant expiresAt,
        Map<String, Object> payloadBody
) {

    public AgentToolActionPayloadRecord {
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        sensitiveArgumentNames = sensitiveArgumentNames == null ? List.of() : List.copyOf(sensitiveArgumentNames);
        payloadBody = payloadBody == null ? Map.of() : Map.copyOf(payloadBody);
        payloadBodyAvailable = Boolean.TRUE.equals(payloadBodyAvailable);
        payloadSizeBytes = Math.max(0, payloadSizeBytes == null ? 0 : payloadSizeBytes);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * 判断载荷登记是否已经过期。
     *
     * <p>过期策略是执行安全的一部分：即使 payloadReference 字符串仍然合法，也不能让旧的工具参数在很久之后
     * 被 Agent loop、网关重试或人工误操作重新执行。当前内存 store 会保留记录，读取方通过该方法做 fail-closed。</p>
     */
    public boolean expired(Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return expiresAt != null && expiresAt.isBefore(referenceTime);
    }
}
