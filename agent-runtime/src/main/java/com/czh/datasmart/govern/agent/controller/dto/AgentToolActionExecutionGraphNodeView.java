/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphNodeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 工具动作执行图中的单个条件节点。
 *
 * <p>这里的“节点”不是线程、进程、真实 worker，也不是 LangGraph/OpenClaw 已经运行的节点实例。
 * 它是 Java 控制面根据低敏 intake/contract 事实推导出的治理节点，用于让前端确认页、审计台和后续执行器理解：
 * 一个外部工具动作在进入真实副作用前，需要依次经过哪些关卡。</p>
 *
 * <p>安全边界：节点只展示工具名、状态、缺口代码、证据引用等低敏治理事实。它不携带 arguments、SQL、prompt、
 * 样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文，因此可以安全用于 timeline、控制面图谱和运营报表。</p>
 *
 * @param nodeId 单张图内唯一的节点 ID，用于边引用和前端渲染。
 * @param nodeType 节点类型，例如 INTAKE、READINESS_GATE、HUMAN_APPROVAL、DURABLE_CONTRACT、OUTBOX_COMMAND、WORKER_RECEIPT。
 * @param stage 节点所属业务阶段，用于把节点归入协议入口、准备度判断、人工确认、持久化命令或 worker 回执等阶段。
 * @param status 节点当前状态，通常由 contract state 推导，例如 PASSED、WAITING、BLOCKED、WAITING_EVIDENCE。
 * @param required 该节点是否是本轮动作进入真实执行链路的必要步骤。
 * @param executable 该节点当前是否具备进入下一阶段的最低条件。只读图不会执行，只表达条件是否满足。
 * @param blockedByNodeIds 阻塞该节点的前置节点 ID，用于解释为什么当前节点不能继续。
 * @param evidenceRefs 支撑该节点存在的低敏证据引用，例如 replaySequence、contractId、idempotencyKey、issueCode。
 * @param missingRequirements 当前节点仍缺失的生产级要求，使用稳定机器码便于前端筛选和审计统计。
 * @param reasons 给学习、排障和审计使用的中文解释，说明节点为什么存在以及当前状态如何形成。
 * @param recommendedActions 面向确认页、运营台或后续执行器的下一步建议。
 */
public record AgentToolActionExecutionGraphNodeView(
        String nodeId,
        String nodeType,
        String stage,
        String status,
        Boolean required,
        Boolean executable,
        List<String> blockedByNodeIds,
        List<String> evidenceRefs,
        List<String> missingRequirements,
        List<String> reasons,
        List<String> recommendedActions
) {
}
