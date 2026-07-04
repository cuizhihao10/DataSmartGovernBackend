"""KNOWLEDGE_AGENT 的 RAG 能力合同。

本模块只描述“治理知识 RAG 能力如何被多 Agent runner 调度”，不真正执行检索、重排或模型生成。
这样拆分有三个目的：
- `services/rag` 继续负责 RAG 算法管线和 LangGraph checkpoint；
- `controlled_turn_runner` 继续负责 turn 状态机，不被 RAG 细节污染；
- Java 控制面、前端和审计系统可以先消费一份低敏能力合同，再决定是否创建 command proposal、
  是否需要审批、是否等待 checkpoint/worker receipt。

这里的能力合同刻意不保存用户问题、召回正文、引用 URL、模型回答或压缩上下文，只保存节点名、边界、
证据编码和可调用角色等稳定控制面字段。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.services.rag import LANGGRAPH_RAG_GRAPH_NAME
from datasmart_ai_runtime.services.multi_agent.turn_runner_models import ControlledAgentTurnAttempt


KNOWLEDGE_RAG_CAPABILITY_CODE = "knowledge.rag.query"


@dataclass(frozen=True)
class KnowledgeAgentRagCapability:
    """`KNOWLEDGE_AGENT` 可向主控暴露的 RAG 能力摘要。

    字段说明：
    - `source_turn_id`：如果本轮已有 KNOWLEDGE_AGENT turn attempt，则绑定其低敏 turnId，便于恢复；
    - `tool_name`：对应工具目录中的受控工具名，真实执行仍必须走 Java/Host 控制面；
    - `langgraph_nodes`：RAG 当前已经 checkpoint 化的节点链路；
    - `required_evidence_codes`：调用前必须存在的 host fact，避免 Python runner 单边执行；
    - `callable_by_roles`：哪些 Agent 可以把它作为 manager-as-tools 能力请求；
    - `input_policy/output_policy`：明确不允许把 prompt、证据正文、模型输出等敏感内容塞进 runner 状态。
    """

    source_turn_id: str | None
    source_work_item_id: str | None
    tool_name: str = KNOWLEDGE_RAG_CAPABILITY_CODE
    langgraph_graph_name: str = LANGGRAPH_RAG_GRAPH_NAME
    langgraph_nodes: tuple[str, ...] = (
        "rag_retrieve_knowledge",
        "rag_evidence_gate",
        "rag_grounded_answer_completed",
        "rag_no_evidence_completed",
    )
    required_evidence_codes: tuple[str, ...] = (
        "TURN_CHECKPOINT_REQUIRED",
        "RAG_SCOPE_POLICY_REQUIRED",
        "RAG_EVIDENCE_GATE_REQUIRED",
        "RAG_LANGGRAPH_CHECKPOINT_REQUIRED",
        "JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED",
        "WORKER_RECEIPT_REQUIRED",
    )
    callable_by_roles: tuple[str, ...] = (
        "MASTER_ORCHESTRATOR",
        "DATA_QUALITY_AGENT",
        "DATASOURCE_AGENT",
        "DATA_SYNC_AGENT",
        "TASK_AGENT",
        "PERMISSION_AGENT",
    )

    def to_summary(self) -> dict[str, Any]:
        """输出低敏能力合同摘要。

        该摘要可以安全进入 `/agent/plans`、runtime event 或 Java projection。它只说明能力边界和恢复
        事实，不包含用户查询、文档正文、引用 URL、模型回答、embedding 向量或 Provider 原始响应。
        """

        return {
            "capabilityCode": KNOWLEDGE_RAG_CAPABILITY_CODE,
            "agentRole": "KNOWLEDGE_AGENT",
            "sourceTurnId": self.source_turn_id,
            "sourceWorkItemId": self.source_work_item_id,
            "toolName": self.tool_name,
            "langGraphGraphName": self.langgraph_graph_name,
            "langGraphNodes": self.langgraph_nodes,
            "requiredEvidenceCodes": self.required_evidence_codes,
            "callableByRoles": self.callable_by_roles,
            "invocationPolicy": "MANAGER_AS_TOOLS_REQUIRES_JAVA_CONTROL_PLANE_AND_RAG_CHECKPOINT",
            "checkpointPolicy": "RAG_NODE_CHAIN_MUST_RECORD_LOW_SENSITIVE_LANGGRAPH_CHECKPOINT",
            "inputPolicy": (
                "只允许 queryRef、scopePolicy、evidencePolicy 等低敏引用进入控制面；"
                "用户问题正文必须在 RAG 执行器内部解析，不写入 turn runner 状态。"
            ),
            "outputPolicy": (
                "只返回 citationCount、acceptedEvidenceCount、weakEvidenceRejectedCount、failClosed 等摘要；"
                "证据正文、sourceUri、compressedContext 和模型回答不能写入 runner 状态。"
            ),
            "sideEffectBoundary": {
                "ragExecutedByTurnRunner": False,
                "modelCalledByTurnRunner": False,
                "documentBodyStoredInRunner": False,
                "javaControlPlaneRequiredForSideEffects": True,
                "workerReceiptRequiredForSideEffects": True,
            },
            "payloadPolicy": "LOW_SENSITIVE_KNOWLEDGE_AGENT_RAG_CAPABILITY_ONLY",
        }


def build_knowledge_agent_rag_capabilities(
    attempts: tuple[ControlledAgentTurnAttempt, ...],
) -> tuple[dict[str, Any], ...]:
    """根据 turn attempt 生成 KNOWLEDGE_AGENT 的 RAG 能力合同。

    只有当本轮存在 `KNOWLEDGE_AGENT` attempt 时才输出能力合同。这样可以避免所有请求都默认暴露 RAG，
    同时又让知识问答、网页搜索补证据、质量规则解释、权限说明等场景在 runner 中拥有清晰的调度入口。
    """

    knowledge_attempt = next((attempt for attempt in attempts if attempt.agent_role == "KNOWLEDGE_AGENT"), None)
    if knowledge_attempt is None:
        return ()
    capability = KnowledgeAgentRagCapability(
        source_turn_id=knowledge_attempt.turn_id,
        source_work_item_id=knowledge_attempt.work_item_id,
    )
    return (capability.to_summary(),)
