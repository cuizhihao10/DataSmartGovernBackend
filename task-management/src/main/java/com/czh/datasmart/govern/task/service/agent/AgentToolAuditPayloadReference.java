/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentToolAuditPayloadReference.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * Agent 工具审计载荷引用。
 *
 * <p>引用格式固定为：</p>
 * <p>`agent-tool-audit://{sessionId}/{runId}/{auditId}/plan-arguments`</p>
 *
 * <p>为什么不用普通 URL 或直接 targetEndpoint：</p>
 * <p>1. payloadReference 表达的是“从 Agent 审计快照回读参数”，不是任意网络地址；</p>
 * <p>2. task-management 只允许解析白名单协议，避免 worker 被诱导访问外部 URL 或内部敏感接口；</p>
 * <p>3. sessionId/runId/auditId 同时出现，可以在执行侧校验 command 摘要和真实审计快照是否一致；</p>
 * <p>4. payloadKind 固定为 plan-arguments，后续扩展新载荷类型必须显式改协议和 resolver。</p>
 */
public record AgentToolAuditPayloadReference(String sessionId,
                                             String runId,
                                             String auditId,
                                             String payloadKind) {

    public static final String PREFIX = "agent-tool-audit://";
    public static final String PLAN_ARGUMENTS = "plan-arguments";

    /**
     * 解析并校验 payloadReference。
     *
     * @param raw task.params 或 command payload 中保存的引用字符串。
     * @return 结构化引用对象。
     */
    public static AgentToolAuditPayloadReference parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("payloadReference 不能为空");
        }
        String value = raw.trim();
        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("payloadReference 必须使用 agent-tool-audit:// 协议");
        }
        String body = value.substring(PREFIX.length());
        String[] parts = body.split("/", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("payloadReference 必须包含 sessionId/runId/auditId/payloadKind 四段");
        }
        AgentToolAuditPayloadReference reference = new AgentToolAuditPayloadReference(
                requireSegment(parts[0], "sessionId"),
                requireSegment(parts[1], "runId"),
                requireSegment(parts[2], "auditId"),
                requireSegment(parts[3], "payloadKind")
        );
        if (!PLAN_ARGUMENTS.equals(reference.payloadKind())) {
            throw new IllegalArgumentException("当前仅支持 plan-arguments 载荷引用，payloadKind=" + reference.payloadKind());
        }
        return reference;
    }

    /**
     * 生成规范化字符串。
     *
     * <p>resolver 会用它和 Agent Runtime 返回的 payloadReference 对比，确保双方理解的是同一个参数快照。</p>
     */
    public String toCanonicalString() {
        return PREFIX + sessionId + "/" + runId + "/" + auditId + "/" + payloadKind;
    }

    private static String requireSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("payloadReference 缺少 " + name);
        }
        return value.trim();
    }
}
