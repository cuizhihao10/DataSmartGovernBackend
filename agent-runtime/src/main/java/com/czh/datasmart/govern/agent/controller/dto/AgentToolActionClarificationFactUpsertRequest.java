/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactUpsertRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Agent 工具动作澄清事实登记请求。
 *
 * <p>该请求用于把“用户已经补充了恢复所需信息”登记成 Java 控制面可回查的低敏事实。
 * 它不是澄清内容上传接口，因此刻意没有 answer、content、prompt、sql、arguments、payload
 * 这类字段。调用方只提交 factId、run/session/command/tool 等低敏 locator 和状态元数据。</p>
 *
 * <p>典型调用场景：
 * 1. Python Runtime 或前端发现某个工具动作缺少必要参数，暂停并让用户补充；
 * 2. 用户在受控页面完成补充后，网关/Agent Host 调用本接口登记澄清事实；
 * 3. 后续 `/tool-action-resume-facts/bundles/query` 携带同一个 clarificationFactId；
 * 4. Java fact bundle 回查本事实，确认范围匹配、未过期、未撤销后，才把 CLARIFICATION_FACT 标记为 AVAILABLE。</p>
 */
public record AgentToolActionClarificationFactUpsertRequest(
        /** 澄清事实 ID，建议由 Agent Host 使用稳定幂等键生成，避免前端重试产生多条事实。 */
        String clarificationFactId,

        /** Agent 会话 ID，用于把澄清事实限定在同一对话窗口。 */
        String sessionId,

        /** Agent run ID，用于防止旧 run 的用户补充被复用到新 run。 */
        String runId,

        /** 受控工具动作 commandId；如果用户澄清发生在 command 生成前，可以暂时为空。 */
        String commandId,

        /** 工具编码，例如 datasource.metadata.read 或 task.draft.persist。 */
        String toolCode,

        /** 当前工具治理策略版本，用于恢复预检时发现策略版本漂移。 */
        String requestedPolicyVersion,

        /**
         * 请求体中的租户 ID。
         *
         * <p>该字段只能与可信 Header 中的 tenantId 一致，不能扩大调用方权限范围。
         * 最终写入值以 Header 为准；保留该字段是为了让调用方在联调时显式表达目标边界。</p>
         */
        Long tenantId,

        /**
         * 项目 ID。
         *
         * <p>PROJECT 数据范围下必须落在 Header 透传的 authorizedProjectIds 内。
         * 如果调用方不传且授权项目只有一个，服务端可以自动补齐；否则会拒绝登记，避免事实边界含糊。</p>
         */
        Long projectId,

        /**
         * 请求体中的 actorId。
         *
         * <p>最终写入值以可信 Header 的 actorId 为准；如果请求体显式传入且与 Header 不一致，会被拒绝。</p>
         */
        String actorId,

        /**
         * 事实状态。
         *
         * <p>默认 AVAILABLE；可选 REVOKED/REJECTED 用于受控页面撤销澄清或人工判定该澄清不可再采信。</p>
         */
        String status,

        /** 低敏证据码，只能写类似 USER_CLARIFICATION_CAPTURED、FORM_CONFIRMED 这样的枚举语义。 */
        List<String> evidenceCodes,

        /** 低敏问题码，只能写枚举语义，不能写用户原文、SQL、prompt 或工具参数。 */
        List<String> issueCodes,

        /** 可选过期时间；为空时服务端按默认 TTL 生成。 */
        Instant expiresAt
) {
}
