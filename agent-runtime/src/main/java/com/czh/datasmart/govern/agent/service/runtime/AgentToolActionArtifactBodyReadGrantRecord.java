/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;

import java.util.Objects;

/**
 * 服务端保存的 artifact 正文读取授权事实。
 *
 * <p>该记录是 grantDecisionReference 背后的“可回查事实”，用于把正文读取链路从
 * “只检查引用字符串长得像不像”升级为“服务端确认该 grant 曾经签发、仍未过期、未撤销、且上下文未漂移”。</p>
 *
 * <p>安全边界非常重要：本记录只保存低敏控制面字段，例如 commandId、artifactReference、tenant/project/actor、
 * run/session、读取目的、读取形态、最大字节数和 receipt 指纹。它绝不保存 artifact 正文、sample bytes、
 * stdout/stderr、bucket/key、签名 URL、bearer token、prompt、SQL、工具参数、模型输出或内部 endpoint。
 * 后续切换 MySQL store 时也必须保持同样的数据契约。</p>
 */
public record AgentToolActionArtifactBodyReadGrantRecord(
        /**
         * body-read-grants 接口返回给调用方的低敏决策引用。
         *
         * <p>该值用于串联审计和回查事实，不具备 bearer token 语义；任何后续读取都必须通过
         * store.findByReference 回查该记录，而不能只因为字符串格式匹配就放行。</p>
         */
        String grantDecisionReference,
        /**
         * 产生 artifact 的命令编号。
         *
         * <p>该字段用于确认“本次读取请求”确实指向同一条命令输出，防止调用方把 A 命令签发的 grant
         * 挪用到 B 命令的 artifact 上。它是控制面关联键，不是命令正文。</p>
         */
        String commandId,
        /**
         * 低敏 artifact 引用。
         *
         * <p>例如 agent-artifact:xxx 或 command-output:xxx 这类逻辑引用。记录中只保存引用，
         * 不保存对象存储 bucket/key、真实路径、下载地址或 stdout/stderr 原文。</p>
         */
        String artifactReference,
        /**
         * artifact 引用类型。
         *
         * <p>用于区分命令输出、Agent 生成物、任务附件等来源。后续做下载审计、DLP 扫描或保留期策略时，
         * 可以按引用类型选择不同的处理策略。</p>
         */
        String artifactReferenceType,
        /**
         * 申请读取正文的业务目的。
         *
         * <p>例如 safe-preview、operator-review、diagnostic 等。读取目的会影响可读字节数、是否需要人工审批、
         * 是否允许进入对象存储探针，以及审计报表如何归类。</p>
         */
        String readPurpose,
        /**
         * 调用方请求的正文模式。
         *
         * <p>当前链路只允许安全预览、受限探针等低敏模式；未来如果增加下载模式，也必须先在这里留下
         * 明确模式，再通过策略和审批决定是否放行。</p>
         */
        String requestedContentMode,
        /**
         * 本次 grant 允许读取的最大字节数。
         *
         * <p>它保护 artifact 正文不会因为一次预览或探针请求被大量泄露。object-store probe 和 final-check
         * 都必须继续施加自身的 sample/preview 上限，不能只依赖该值。</p>
         */
        Integer maxReadableBytes,
        /**
         * 租户边界。
         *
         * <p>多租户场景下，同一个 commandId 或 artifactReference 理论上可能在不同租户中出现。
         * grant 回查必须同时比较 tenantId，避免跨租户复用授权。</p>
         */
        String tenantId,
        /**
         * 项目或 workspace 边界。
         *
         * <p>项目是 Agent 工具执行和 artifact 访问的主要隔离域。即使 tenant 相同，也不能把一个项目的
         * grant 用到另一个项目的 artifact 上。</p>
         */
        String projectId,
        /**
         * 发起读取授权的操作者。
         *
         * <p>可以是普通用户、服务账号或 Agent 代表身份。后续接入 permission-admin 时，
         * actorId 会参与撤销、审计和责任归属。</p>
         */
        String actorId,
        /**
         * Agent run 编号。
         *
         * <p>runId 用来把 grant 与一次 Agent 执行过程串起来，便于 runtime event replay、任务复盘和
         * “哪个 run 触发了正文读取”这类审计查询。</p>
         */
        String runId,
        /**
         * Agent 会话编号。
         *
         * <p>sessionId 用于把多轮交互、resume gate 和 artifact 读取记录关联起来。它不是权限凭据，
         * 只作为低敏控制面关联键保存。</p>
         */
        String sessionId,
        /**
         * 触发 artifact 读取链路的工具编码。
         *
         * <p>后续可以按工具维度统计高风险读取、限流、审批命中率和失败原因；这里不保存工具参数值。</p>
         */
        String toolCode,
        /**
         * 与该 artifact 匹配的 worker receipt 指纹。
         *
         * <p>receipt 指纹用于证明 artifact 来源于已登记的 worker 副作用回执，而不是调用方临时拼出来的
         * artifactReference。它是摘要，不包含原始输出或 token 明文。</p>
         */
        String matchedReceiptFingerprint,
        /**
         * runtime event 或 worker receipt 的回放序号。
         *
         * <p>用于在审计和排障时确认 grant 对应哪一次副作用证据。该字段也可以帮助未来做幂等补物化和
         * 增量同步。</p>
         */
        Long replaySequence,
        /**
         * worker receipt 的低敏结果状态。
         *
         * <p>例如 SUCCEEDED、FAILED_WITH_ARTIFACT 等机器态。它用于解释为什么允许或拒绝读取，
         * 但不能包含 stdout/stderr、错误堆栈正文或业务数据样本。</p>
         */
        String receiptOutcome,
        /**
         * 服务端签发 grant 的毫秒时间戳。
         *
         * <p>该时间由 Java Host 生成，不信任调用方提交值。它用于审计、TTL 判断和后续归档。</p>
         */
        long issuedAtEpochMs,
        /**
         * grant 过期的毫秒时间戳。
         *
         * <p>expiresAt 为空时按过期处理，因为正文读取属于高风险边界；缺少有效期的历史数据不能被乐观放行。</p>
         */
        Long expiresAtEpochMs,
        /**
         * 服务端事实状态。
         *
         * <p>ACTIVE 才能继续进入 final-check 或 object-store probe；EXPIRED/REVOKED 都必须 fail-closed。</p>
         */
        AgentToolActionArtifactBodyReadGrantStatus status,
        /**
         * 撤销发生时间。
         *
         * <p>为空表示未撤销；不为空时，即使 expiresAt 尚未到达，后续读取也必须拒绝。</p>
         */
        Long revokedAtEpochMs,
        /**
         * 执行撤销的低敏操作者标识。
         *
         * <p>这里只保存 operatorId，不保存人工备注、审批意见正文或敏感上下文，避免撤销记录本身变成敏感数据源。</p>
         */
        String revokedBy,
        /**
         * 撤销原因码。
         *
         * <p>原因码应使用机器可读枚举，例如 RISK_POLICY_CHANGED、ARTIFACT_QUARANTINED、PERMISSION_REVOKED。
         * 审计界面可以用原因码映射说明文案，但 store 中不直接保存长文本说明。</p>
         */
        String revokeReasonCode
) {

    /**
     * 从低敏 grant response 物化服务端事实。
     *
     * <p>只在 granted=true 时调用。response 里虽然携带 grantDecisionReference，
     * 但后续 final-check/probe 不再信任调用方单独提交的引用，而是回查本记录。</p>
     */
    public static AgentToolActionArtifactBodyReadGrantRecord fromDecision(
            AgentToolActionArtifactBodyReadGrantResponse decision,
            long issuedAtEpochMs) {
        return new AgentToolActionArtifactBodyReadGrantRecord(
                decision.grantDecisionReference(),
                decision.commandId(),
                decision.artifactReference(),
                decision.artifactReferenceType(),
                decision.readPurpose(),
                decision.requestedContentMode(),
                decision.maxReadableBytes(),
                decision.tenantId(),
                decision.projectId(),
                decision.actorId(),
                decision.runId(),
                decision.sessionId(),
                decision.toolCode(),
                decision.matchedReceiptFingerprint(),
                decision.replaySequence(),
                decision.receiptOutcome(),
                issuedAtEpochMs,
                decision.grantExpiresAtEpochMs(),
                AgentToolActionArtifactBodyReadGrantStatus.ACTIVE,
                null,
                null,
                null
        );
    }

    /**
     * 判断 grant 是否已经超过服务端有效期。
     *
     * <p>expiresAt 为空时按过期处理。正文读取属于高风险副作用边界，
     * 缺少有效期的历史记录不应被乐观放行。</p>
     */
    public boolean expiredAt(long nowEpochMs) {
        return expiresAtEpochMs == null || expiresAtEpochMs <= nowEpochMs;
    }

    /**
     * 判断记录在当前时刻是否仍可用于后续读取控制面。
     */
    public boolean activeAt(long nowEpochMs) {
        return status == AgentToolActionArtifactBodyReadGrantStatus.ACTIVE && !expiredAt(nowEpochMs);
    }

    /**
     * 把记录转换为过期状态，保留原始签发事实用于审计解释。
     */
    public AgentToolActionArtifactBodyReadGrantRecord expire(long checkedAtEpochMs) {
        return new AgentToolActionArtifactBodyReadGrantRecord(
                grantDecisionReference,
                commandId,
                artifactReference,
                artifactReferenceType,
                readPurpose,
                requestedContentMode,
                maxReadableBytes,
                tenantId,
                projectId,
                actorId,
                runId,
                sessionId,
                toolCode,
                matchedReceiptFingerprint,
                replaySequence,
                receiptOutcome,
                issuedAtEpochMs,
                expiresAtEpochMs == null ? checkedAtEpochMs : expiresAtEpochMs,
                AgentToolActionArtifactBodyReadGrantStatus.EXPIRED,
                revokedAtEpochMs,
                revokedBy,
                revokeReasonCode
        );
    }

    /**
     * 把记录转换为撤销状态。
     *
     * <p>撤销信息只保存低敏机器字段：操作者标识和原因码，不保存人工备注正文或审批说明正文，
     * 以免把敏感业务上下文写入 grant store。</p>
     */
    public AgentToolActionArtifactBodyReadGrantRecord revoke(
            String operatorId,
            String reasonCode,
            long revokedAtEpochMs) {
        return new AgentToolActionArtifactBodyReadGrantRecord(
                grantDecisionReference,
                commandId,
                artifactReference,
                artifactReferenceType,
                readPurpose,
                requestedContentMode,
                maxReadableBytes,
                tenantId,
                projectId,
                actorId,
                runId,
                sessionId,
                toolCode,
                matchedReceiptFingerprint,
                replaySequence,
                receiptOutcome,
                issuedAtEpochMs,
                expiresAtEpochMs,
                AgentToolActionArtifactBodyReadGrantStatus.REVOKED,
                revokedAtEpochMs,
                safeText(operatorId),
                safeText(reasonCode)
        );
    }

    /**
     * 校验“当前重新授权结果”是否仍然绑定到同一组低敏上下文。
     *
     * <p>final-check/probe 会先重新调用 grant 服务，确认 metadata、读取目的和读取形态当前仍可通过；
     * 随后再用本方法确认调用方提交的 previousGrantDecisionReference 确实属于同一 command/artifact/run/session。
     * 这样可以防止 A 命令的 grant 被拿去读取 B 命令的 artifact，也可以防止权限范围变化后继续复用旧引用。</p>
     */
    public boolean matchesDecision(AgentToolActionArtifactBodyReadGrantResponse decision) {
        if (decision == null || !decision.granted()) {
            return false;
        }
        return same(commandId, decision.commandId())
                && same(artifactReference, decision.artifactReference())
                && same(artifactReferenceType, decision.artifactReferenceType())
                && same(readPurpose, decision.readPurpose())
                && same(requestedContentMode, decision.requestedContentMode())
                && Objects.equals(maxReadableBytes, decision.maxReadableBytes())
                && same(tenantId, decision.tenantId())
                && same(projectId, decision.projectId())
                && same(actorId, decision.actorId())
                && same(runId, decision.runId())
                && same(sessionId, decision.sessionId())
                && same(toolCode, decision.toolCode())
                && same(matchedReceiptFingerprint, decision.matchedReceiptFingerprint())
                && Objects.equals(replaySequence, decision.replaySequence())
                && same(receiptOutcome, decision.receiptOutcome());
    }

    /**
     * 判断记录是否具备最小可索引字段。
     */
    public boolean indexable() {
        return hasText(grantDecisionReference) && status != null;
    }

    private boolean same(String expected, String actual) {
        return Objects.equals(safeText(expected), safeText(actual));
    }

    private static boolean hasText(String value) {
        return safeText(value) != null;
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
