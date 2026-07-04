/**
 * @Author : Cui
 * @Date: 2026/07/05 00:00
 * @Description DataSmart Govern Backend - AgentTurnRunnerCheckpointProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent Turn Runner 关联的 LangGraph durable checkpoint 低敏定位视图。
 *
 * <p>这个 DTO 的业务定位是“恢复锚点”，不是 checkpoint state 正文。Python Runtime 在生成
 * {@code agentTurnRunnerCheckpoint} 后，会把其中可审计、可恢复、可查询的 locator 追加到
 * {@code agent_turn_runner_recorded} runtime event。Java agent-runtime 再把该 locator 投影成
 * 本视图，供管理台、审计台和后续 resume 控制面查询。</p>
 *
 * <p>为什么 checkpointId 可以出现在这里：它是低敏控制面定位符，用于找到 LangGraph durable thread
 * 的暂停点、恢复点或分支起点；它不包含用户自然语言目标、prompt、工具参数、SQL、样本数据、模型输出、
 * 文档正文或内部 endpoint。真正敏感的 checkpoint state 正文仍然只能由受控 checkpointer 服务读取，
 * 且需要经过权限、审计和恢复事实校验。</p>
 *
 * <p>字段白名单边界：</p>
 * <p>1. 允许：threadId、checkpointId、graphName、nodeName、状态、版本号、下一节点、恢复条件 code、
 * Agent role/status 摘要；</p>
 * <p>2. 禁止：prompt、ToolPlan.arguments、SQL、样本数据、模型输出、RAG answer、artifact body、
 * endpoint、token、secret、provider 原始响应。</p>
 */
public record AgentTurnRunnerCheckpointProjectionView(
        /**
         * LangGraph threadId。它表示可恢复状态机线程，通常由 session/run/request 的低敏 ID 派生。
         * 管理台可用它查询最新 checkpoint、事件流、pause/resume/fork/recover 结果。
         */
        String threadId,

        /**
         * checkpointId。它是单个暂停点或状态版本的定位符，可用于精确恢复或创建分支。
         */
        String checkpointId,

        /**
         * 父 checkpointId。只有 fork、loop 或恢复后推进版本时才可能存在；为空表示当前 checkpoint
         * 是 thread 的初始锚点或事件未提供父子关系。
         */
        String parentCheckpointId,

        /**
         * LangGraph 图名称。多 Agent turn runner 当前固定为 datasmart.agent.multi-agent-turn-runner，
         * 后续如果拆分 RAG、MCP、ETL 等子图，管理台可按 graphName 分组展示。
         */
        String graphName,

        /**
         * 图契约版本。用于未来升级节点、边、状态结构时做兼容判断，避免旧 runtime event 被新控制面误读。
         */
        String graphVersion,

        /**
         * 当前停留节点，例如 multi_agent_turn_wait_human 或 multi_agent_turn_wait_control_plane。
         * 节点名让 Agent 从固定流水线升级为可解释状态机：我们能明确知道是等待人工、等待工具还是被策略阻断。
         */
        String nodeName,

        /**
         * checkpoint 状态，来自 Python LangGraph checkpointer 的低敏状态枚举，例如 waiting_human、
         * waiting_tool、running、failed、completed、paused。
         */
        String checkpointStatus,

        /**
         * 同一 thread 内的 checkpoint 版本号。版本号用于判断恢复现场是否前进，避免旧页面或旧 worker
         * 基于过期 checkpoint 发起恢复动作。
         */
        Integer checkpointVersion,

        /**
         * 下一批候选节点。它解释状态机接下来可走哪些边，例如 wait_approval_fact、resume_after_human_decision。
         * 这里只保留节点 code，不包含任何业务正文。
         */
        List<String> nextNodes,

        /**
         * 恢复所需事实 key，例如 requiredEvidenceCodes、javaControlPlaneRequired、workerReceiptRequired。
         * 注意这里记录的是恢复条件键名或 code，而不是审批意见、worker 输出或工具结果。
         */
        List<String> resumeRequirementKeys,

        /**
         * checkpoint state 顶层 key 摘要。它只能说明 state 中有哪些低敏结构块，不能返回 state 值本身。
         */
        List<String> stateTopLevelKeys,

        /**
         * 是否成功从 checkpoint 恢复出多 Agent 状态摘要。
         */
        Boolean recoveryFound,

        /**
         * 恢复摘要中的 checkpoint 状态。它与 checkpointStatus 通常一致，但保留独立字段便于未来恢复服务
         * 对 checkpoint 做二次解释或降级处理。
         */
        String recoveryStatus,

        /**
         * checkpoint 中恢复出的 Agent 角色集合，例如 MASTER_ORCHESTRATOR、DATA_QUALITY_AGENT。
         * 仅保留角色名称，不保留工作项 payload。
         */
        List<String> recoveryAgentRoles,

        /**
         * Agent role -> status 的低敏映射。它解释多 Agent 协作现场，例如哪个 Agent 正在等待审批、
         * 哪个 Agent 可以进入控制面 handoff。
         */
        Map<String, String> recoveryAgentStatuses,

        /**
         * 是否仍需要 Java 控制面、审批事实或 worker receipt handoff。
         */
        Boolean handoffRequired,

        /**
         * payload 策略声明。用于审计和回归测试确认该视图只承载低敏 locator。
         */
        String payloadPolicy
) {
}
