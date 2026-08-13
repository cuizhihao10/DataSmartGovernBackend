from __future__ import annotations

import hashlib
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any

from datasmart_ai_runtime.services.agent_execution.autopilot_recovery import (
    AutopilotRecoveryCoordinator,
    AutopilotRecoveryRequest,
)
from datasmart_ai_runtime.services.agent_execution.autopilot_recovery_investigation import (
    AutopilotRecoveryInvestigationResult,
)
from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import SpecialistAgentRegistry
from datasmart_ai_runtime.services.rag.models import RagCitation, RagPipelineResult


class _SequencedRecoveryAgent:
    """按测试预设顺序返回 Recovery 结果，并保存每轮可信请求供断言。"""

    role = AgentSessionRole.RECOVERY_AGENT

    def __init__(self, outputs: list[dict[str, Any]]) -> None:
        self._outputs = list(outputs)
        self.requests: list[SpecialistTurnRequest] = []

    def execute(self, request: SpecialistTurnRequest, event_sink: Any = None) -> SpecialistTurnResult:
        """返回下一份低敏 Specialist 输出，模拟真实 Recovery Agent 的两轮行为。"""

        self.requests.append(request)
        output = self._outputs.pop(0)
        return SpecialistTurnResult(
            agent_id="recovery-specialist-test",
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary="test recovery result",
            structured_output=output,
        )


class _StaticRagPipeline:
    """返回固定引用的 RAG 替身，用于验证检索策略映射而不调用模型或数据库。"""

    def __init__(self, *, with_citation: bool = True) -> None:
        self.with_citation = with_citation
        self.queries: list[Any] = []

    def answer(self, query: Any) -> RagPipelineResult:
        """记录查询合同，并返回包含或不包含证据的固定结果。"""

        self.queries.append(query)
        citations = (
            (
                RagCitation(
                    citation_id="C1",
                    document_id="runbook-1",
                    chunk_id="chunk-1",
                    title="Retry runbook",
                    source_uri="docs/runbook.md",
                    snippet="bounded retry guidance",
                    final_score=0.91,
                ),
            )
            if self.with_citation
            else ()
        )
        evidence_records = (
            (
                {
                    "evidenceId": "rag-evidence-1",
                    "sourceType": "runbook",
                    "retrievedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                },
            )
            if citations
            else ()
        )
        return RagPipelineResult(
            answer="Use the bounded failed-object retry path." if citations else "No evidence.",
            citations=citations,
            selected_chunks=(),
            compressed_context="",
            retrieval_summary={
                "evidenceCount": len(citations),
                "evidenceDigest": "sha256:" + "3" * 64,
                "evidenceRecords": evidence_records,
                "evidenceSourceTypes": ("runbook",) if citations else (),
                "retrievedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "scope": {
                    "tenantId": query.tenant_id,
                    "projectId": query.project_id,
                    "workspaceKey": query.workspace_key,
                },
            },
            model_summary={"providerSucceeded": True},
            generated=bool(citations),
        )


class _RecordingInvestigationCollaborator:
    """记录 coordinator 选择的单一调查动作，并返回一份低敏 Java receipt 摘要。"""

    def __init__(self, *, preview_result: dict[str, Any] | None = None) -> None:
        """保存可选 receipt 覆盖值，用于验证 coordinator 对真实 preview 事实的 fail-closed 校验。

        中文说明：默认结果模拟 Java ``PREVIEWED`` 回执，包含精确样本 ID、确认 digest 与零 issue；测试可
        传入一个已完成但业务上不合格的 preview 结果，证明 Python 仍不会把它提升为 apply 候选。协作者只
        记录调用和返回低敏事实，不具备 data-sync 写能力。

        English: the fake models a receipt that has already crossed the Java control plane.  Overriding only the
        result lets tests isolate receipt validation without faking an apply executor or changing Java state.
        """

        self.calls: list[dict[str, Any]] = []
        self._preview_result = dict(preview_result) if preview_result is not None else None

    def investigate(self, *, request: Any, specialist_result: Any, action_type: str):  # noqa: ANN201
        """模拟真实 bridge/Java preview 已成功，供下一轮 Recovery 消费新证据。

        中文说明：返回的 ``auditId/runId/outputRef`` 是 Java 侧 receipt locator，不是模型字段。默认样本 ID
        故意按非升序排列，方便测试最终 apply 指纹必须按数值排序，而非采信原始列表顺序。

        English: this method does not execute quarantine.  It represents the completed read-only preview stage so
        the following Recovery turn can decide between an apply candidate and a retry candidate.
        """

        self.calls.append(
            {
                "eventId": request.event_id,
                "turnId": specialist_result.turn_id,
                "actionType": action_type,
            }
        )
        preview_result = self._preview_result or {
            "taskId": int(request.sync_task_id),
            "executionId": int(request.current_execution_id),
            "selectedCount": 2,
            "eligibleCount": 2,
            "operationState": "PREVIEWED",
            "confirmationDigest": "f" * 64,
            "selectedSampleIds": (9, 3),
            "issueCodes": (),
        }
        return AutopilotRecoveryInvestigationResult(
            completed=True,
            reason_code="RECOVERY_INVESTIGATION_COMPLETED",
            evidence_summary={
                "source": "JAVA_AGENT_RUNTIME_TOOL_RECEIPT",
                "actionType": action_type,
                "toolName": "sync.dirty-record.quarantine.preview",
                "auditId": "audit-preview-1",
                "runId": "run-preview-1",
                "outputRef": "agent-runtime://sessions/session-1/runs/run-preview-1/tool-results/audit-preview-1",
                "result": preview_result,
                "payloadPolicy": "LOW_SENSITIVE_AUTOPILOT_INVESTIGATION_RECEIPT_ONLY",
            },
            evidence_references=(
                "agent-runtime://sessions/session-1/runs/run-preview-1/tool-results/audit-preview-1",
            ),
        )


def _evidence_audit() -> dict[str, Any]:
    """创建满足 Java/Python 证据门的固定低敏审计摘要。"""

    return {
        "queryDigest": "sha256:" + "1" * 64,
        "retrievedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "evidenceCount": 1,
        "sourceTypes": ("STRUCTURED_API",),
        "evidenceRecords": (
            {
                "evidenceId": "diagnostic-evidence-1",
                "sourceType": "STRUCTURED_API",
                "sourceRef": "sync-execution:31:41",
                "retrievedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "queryDigest": "sha256:" + "1" * 64,
            },
        ),
        "evidenceDigest": "sha256:" + "2" * 64,
    }


def _request(**overrides: Any) -> AutopilotRecoveryRequest:
    """创建一份有效的 Java 已验证触发请求，允许测试覆盖单个字段。"""

    values = {
        "event_id": "autopilot-trigger:test-1",
        "root_session_id": "session-1",
        "root_run_id": "run-1",
        "tenant_id": "11",
        "application_id": "12",
        "project_id": "13",
        "user_id": "14",
        "actor_id": "14",
        "agent_id": "main-agent",
        "delegation_id": "delegation-root",
        "workspace_key": "workspace-13",
        "sync_task_id": "31",
        "root_execution_id": "40",
        "current_execution_id": "41",
        "cycle": 2,
        "max_recovery_cycles": 5,
        "deadline_at": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
        "error_fingerprint": "a" * 64,
        "repeated_error_count": 1,
        "previous_repair_fingerprint": "b" * 64,
        "issue_codes": ("OBJECT_TRANSFER_FAILED",),
        "triggered_at": datetime.now(timezone.utc).isoformat(),
    }
    values.update(overrides)
    return AutopilotRecoveryRequest(**values)


def _durable_fact_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> dict[str, bool]:
    """Model a Java control-plane acknowledgement for coordinator success-path tests.

    Recording that a callback was invoked is not enough for Autopilot.  The production client returns these typed
    flags after the Java durable fact endpoint accepts the low-sensitive specialist receipt, so tests must model the
    same contract explicitly.
    """

    return {
        "attempted": True,
        "registered": True,
        "skipped": False,
        "duplicate": False,
    }


class AutopilotRecoveryCoordinatorTest(unittest.TestCase):
    def test_completed_event_replay_returns_exact_terminal_result_without_replanning(self) -> None:
        """同一 Kafka 事件重投时必须复用 durable 终态，不能再次调用任何决策或调查依赖。

        中文说明：第一次规划故意依次经过 Recovery Specialist、模型自主 SEARCH、RAG 和 Java 只读
        quarantine preview，最后得到普通低风险 retry 候选。第二次使用完全相同的可信事件调用 ``plan``；
        如果 coordinator 再次执行任一阶段，预设输出会耗尽或调用计数会增长，测试立即失败。除返回合同
        必须逐字段一致外，checkpoint 版本和事件数也必须不变，证明重放只是读取既有终态。

        English: the durable terminal checkpoint is the idempotency receipt for planning.  Kafka redelivery after
        Java side effects must not let a new model turn choose a different candidate for the same event.
        """

        search_output = {
            "repairActions": ({"actionType": "SEARCH_RECOVERY_KNOWLEDGE"},),
            "actionFingerprint": "sha256:" + "4" * 64,
            "retrievalDecision": "SEARCH",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.78,
        }
        preview_output = {
            "repairActions": ({"actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "5" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.86,
        }
        retry_output = {
            "repairActions": ({"actionType": "RETRY_FAILED_OBJECTS"},),
            "actionFingerprint": "sha256:" + "6" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.93,
        }
        request = _request()
        agent = _SequencedRecoveryAgent([search_output, preview_output, retry_output])
        rag = _StaticRagPipeline()
        investigation = _RecordingInvestigationCollaborator()
        checkpointer = LangGraphDurableCheckpointerService()
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=rag,  # type: ignore[arg-type]
            checkpointer=checkpointer,
            investigation_collaborator=investigation,  # type: ignore[arg-type]
            result_sink=_durable_fact_sink,
        )

        first = coordinator.plan(request)
        latest_before_replay = checkpointer.latest_for_thread(first.checkpoint_thread_id or "")
        events_before_replay = checkpointer.events_for_thread(first.checkpoint_thread_id or "")
        second = coordinator.plan(request)
        latest_after_replay = checkpointer.latest_for_thread(second.checkpoint_thread_id or "")
        events_after_replay = checkpointer.events_for_thread(second.checkpoint_thread_id or "")

        self.assertEqual(first.to_summary(), second.to_summary())
        self.assertEqual(3, len(agent.requests))
        self.assertEqual(1, len(rag.queries))
        self.assertEqual(1, len(investigation.calls))
        self.assertIsNotNone(latest_before_replay)
        self.assertIsNotNone(latest_after_replay)
        self.assertEqual(latest_before_replay.checkpoint_id, latest_after_replay.checkpoint_id)
        self.assertEqual(latest_before_replay.checkpoint_version, latest_after_replay.checkpoint_version)
        self.assertEqual(events_before_replay, events_after_replay)

    def test_completed_event_replay_rejects_changed_trusted_bindings(self) -> None:
        """同一 eventId 不能借用给不同范围、任务、执行或错误事实。

        终态重放发生在模型、RAG 和 Java preview 之前，因此绑定不一致必须抛出稳定的 ``ValueError``，
        而不是悄悄创建新 checkpoint 或重新规划。测试用 ``dataclasses.replace`` 保留原请求的时间和其它字段，
        确保每个反例只改变一个可信事实。
        """

        output = {
            "repairActions": ({"actionType": "RETRY_FAILED_OBJECTS"},),
            "actionFingerprint": "sha256:" + "6" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.84,
        }
        request = _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        agent = _SequencedRecoveryAgent([output])
        rag = _StaticRagPipeline()
        investigation = _RecordingInvestigationCollaborator()
        checkpointer = LangGraphDurableCheckpointerService()
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=rag,  # type: ignore[arg-type]
            checkpointer=checkpointer,
            investigation_collaborator=investigation,  # type: ignore[arg-type]
            result_sink=_durable_fact_sink,
        )
        first = coordinator.plan(request)
        thread_id = first.checkpoint_thread_id or ""
        checkpoint_before = checkpointer.latest_for_thread(thread_id)
        events_before = checkpointer.events_for_thread(thread_id)

        changed_bindings = {
            "project": {"project_id": "99"},
            "workspace": {"workspace_key": "workspace-99"},
            "task": {"sync_task_id": "99"},
            "root_execution": {"root_execution_id": "98"},
            "current_execution": {"current_execution_id": "99"},
            "error_fingerprint": {"error_fingerprint": "d" * 64},
        }
        for label, changes in changed_bindings.items():
            with self.subTest(binding=label):
                with self.assertRaisesRegex(ValueError, "replay binding"):
                    coordinator.plan(replace(request, **changes))

        checkpoint_after = checkpointer.latest_for_thread(thread_id)
        self.assertIsNotNone(checkpoint_before)
        self.assertIsNotNone(checkpoint_after)
        self.assertEqual(checkpoint_before.checkpoint_id, checkpoint_after.checkpoint_id)
        self.assertEqual(events_before, checkpointer.events_for_thread(thread_id))
        self.assertEqual(1, len(agent.requests))
        self.assertEqual([], rag.queries)
        self.assertEqual([], investigation.calls)

    def test_model_selected_search_runs_rag_then_returns_retry_candidate(self) -> None:
        """模型选择检索时必须先取得引用，再执行第二轮 Recovery 并返回单一候选。"""

        first_output = {
            "repairActions": ({"actionType": "SEARCH_RECOVERY_KNOWLEDGE"},),
            "actionFingerprint": "sha256:" + "4" * 64,
            "retrievalDecision": "SEARCH",
            "retrievalStrategy": "EXACT_SEARCH",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.78,
        }
        second_output = {
            "repairActions": ({"actionType": "RETRY_FAILED_OBJECTS"},),
            "actionFingerprint": "sha256:" + "5" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "EXACT_SEARCH",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.91,
            # 第二轮模型即使没有自报策略变化，协调器也能根据已完成 SEARCH 与 retrieval audit 独立标记。
            "strategyChanged": False,
        }
        agent = _SequencedRecoveryAgent([first_output, second_output])
        rag = _StaticRagPipeline()
        checkpointer = LangGraphDurableCheckpointerService()
        sink_calls: list[tuple[SpecialistTurnRequest, SpecialistTurnResult]] = []
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=rag,  # type: ignore[arg-type]
            checkpointer=checkpointer,
            result_sink=lambda request, result: (
                sink_calls.append((request, result))
                or _durable_fact_sink(request, result)
            ),
        )

        result = coordinator.plan(_request())

        self.assertEqual("CANDIDATE_READY", result.status)
        self.assertEqual("RETRY_EXECUTION", result.action)
        self.assertEqual("LOW", result.risk_level)
        self.assertTrue(result.idempotent)
        self.assertEqual("5" * 64, result.repair_fingerprint)
        self.assertEqual("SEARCH", result.retrieval_decision)
        self.assertEqual("EXACT_SEARCH", result.retrieval_strategy)
        self.assertTrue(result.strategy_changed)
        self.assertEqual(2, len(agent.requests))
        self.assertEqual(2, len(sink_calls))
        self.assertEqual("lexical", rag.queries[0].retrieval_mode)
        trusted = agent.requests[1].context_summary["trustedAutopilotRecovery"]
        self.assertEqual(1, trusted["repeatedErrorCount"])
        self.assertEqual("b" * 64, trusted["previousRepairFingerprint"])
        knowledge = agent.requests[1].context_summary["knowledgeSummary"]
        self.assertTrue(knowledge["grounded"])
        latest = checkpointer.latest_for_thread(result.checkpoint_thread_id or "")
        self.assertIsNotNone(latest)
        self.assertEqual("autopilot_recovery_finished", latest.node_name)

    def test_skip_search_returns_candidate_without_calling_rag(self) -> None:
        """模型已有足够诊断证据并选择 SKIP 时，不得机械调用 RAG。"""

        output = {
            "repairActions": ({"actionType": "RETRY_FAILED_OBJECTS"},),
            "actionFingerprint": "sha256:" + "6" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.84,
        }
        agent = _SequencedRecoveryAgent([output])
        rag = _StaticRagPipeline()
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=rag,  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(_request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None))

        self.assertEqual("CANDIDATE_READY", result.status)
        self.assertEqual("SKIP", result.retrieval_decision)
        self.assertEqual([], rag.queries)
        self.assertEqual(1, len(agent.requests))

    def test_autonomous_candidate_without_durable_fact_sink_is_not_success(self) -> None:
        """确认后的自治候选没有 durable fact sink 时必须失败，不能形成可 ACK 的规划结果。"""

        output = {
            "repairActions": ({"actionType": "RETRY_FAILED_OBJECTS"},),
            "actionFingerprint": "sha256:" + "7" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.95,
        }
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((_SequencedRecoveryAgent([output]),)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
        )

        with self.assertRaisesRegex(
            RuntimeError,
            "AUTOPILOT_RECOVERY_SPECIALIST_FACT_NOT_DURABLE",
        ):
            coordinator.plan(_request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None))

    def test_read_only_diagnosis_plus_single_retry_keeps_the_retry_candidate(self) -> None:
        """只读诊断是已经完成的证据步骤，不应把授权盒内唯一重试误判为多个副作用动作。"""

        output = {
            "repairActions": (
                {
                    "actionType": "READ_ONLY_DIAGNOSIS",
                    "classification": "READ_ONLY_DIAGNOSTIC",
                    "requiresApproval": False,
                },
                {
                    "actionType": "RETRY_FAILED_OBJECTS",
                    "classification": "HIGH_RISK_SIDE_EFFECT",
                    "requiresApproval": True,
                },
            ),
            "actionFingerprint": "sha256:" + "8" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((_SequencedRecoveryAgent([output]),)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("CANDIDATE_READY", result.status)
        self.assertEqual("RETRY_EXECUTION", result.action)
        self.assertEqual("LOW", result.risk_level)
        self.assertTrue(result.idempotent)

    def test_multiple_investigation_previews_select_one_autonomous_low_risk_step(self) -> None:
        """多个纯调查候选只执行一个 preview，receipt 后仍可选择普通 retry 候选。

        中文说明：第一轮虽然包含两个只读 preview 建议，平台只选唯一可自治的 quarantine preview；Java 回执
        进入第二轮后，模型可以改为 ``RETRY_FAILED_OBJECTS``。这证明 preview 是证据扩展，不会把后续 retry
        路径锁死，也不会让 Python 自己执行 retry。

        English: a governed preview is a one-shot evidence step, not a terminal recovery action.  The next model turn
        may still return a normal Java-owned retry candidate after seeing the receipt, with no additional preview call.
        """

        output = {
            "repairActions": (
                {
                    "actionType": "PREVIEW_SCHEMA_REPAIR",
                    "classification": "LOW_RISK_DRAFT",
                    "requiresApproval": False,
                },
                {
                    "actionType": "PREVIEW_QUARANTINE",
                    "classification": "HIGH_RISK_SIDE_EFFECT",
                    "requiresApproval": True,
                },
            ),
            "actionFingerprint": "sha256:" + "d" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        second_output = {
            "repairActions": ({"actionType": "RETRY_FAILED_OBJECTS"},),
            "actionFingerprint": "sha256:" + "e" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.91,
        }
        agent = _SequencedRecoveryAgent([output, second_output])
        investigation = _RecordingInvestigationCollaborator()
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            investigation_collaborator=investigation,  # type: ignore[arg-type]
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("CANDIDATE_READY", result.status)
        self.assertEqual("RETRY_EXECUTION", result.action)
        self.assertEqual("LOW", result.risk_level)
        self.assertTrue(result.idempotent)
        self.assertEqual("PREVIEW_QUARANTINE", investigation.calls[0]["actionType"])
        self.assertEqual(2, len(agent.requests))
        receipt = agent.requests[1].context_summary["recoveryInvestigation"]
        self.assertEqual(2, receipt["result"]["selectedCount"])

    def test_repeated_preview_request_stops_before_a_second_java_preview(self) -> None:
        """同一 Autopilot 规划中第二次 PREVIEW_QUARANTINE 必须在 Java 调用前停止。

        中文说明：首轮 preview 已通过 Java 控制面并生成 receipt；第二轮模型若仍请求同一 preview，coordinator
        不能再次提交 diagnosis/preview ToolPlan。它返回固定 ATTENTION_REQUIRED，保留外层 Java/Kafka 重试与
        人工审计空间，同时确保 Python 内层没有重复执行只读工具造成不必要的审计和预算消耗。

        English: this is the preview counterpart to the one-shot RAG limit.  The assertion counts collaborator calls,
        not just model turns, so a regression cannot quietly issue a second Java-controlled preview under the same
        recovery event.
        """

        preview_output = {
            "repairActions": ({"actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "a" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.82,
        }
        repeated_preview_output = {
            "repairActions": ({"actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "b" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.82,
        }
        investigation = _RecordingInvestigationCollaborator()
        rag = _StaticRagPipeline()
        agent = _SequencedRecoveryAgent([preview_output, repeated_preview_output])
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=rag,  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            investigation_collaborator=investigation,  # type: ignore[arg-type]
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("RECOVERY_INVESTIGATION_LOOP_LIMIT_REACHED", result.reason_code)
        self.assertEqual(1, len(investigation.calls))
        self.assertEqual(2, len(agent.requests))
        self.assertEqual([], rag.queries)

    def test_real_preview_receipt_enables_apply_candidate_with_canonical_fingerprint(self) -> None:
        """真实 Java preview 通过全部门禁后，APPLY 只能作为 receipt 绑定候选返回 Java。

        本测试同时固定跨语言 fingerprint material：模型给出无效的 actionFingerprint 也不能影响最终值，
        因为 coordinator 必须用 event、错误指纹、execution、preview digest 与数值排序后的样本 ID 重算。测试
        不存在 data-sync 写调用；最终 ``quarantinePreview`` 只是 Java 用于重读 receipt、执行 apply 和 retry 的
        低敏契约。
        """

        preview_output = {
            "repairActions": ({"actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "c" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        apply_output = {
            "repairActions": ({"actionType": "APPLY_QUARANTINE"},),
            # This deliberately malformed model field proves the candidate fingerprint is receipt-derived.
            "actionFingerprint": "model-forged-fingerprint",
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.93,
        }
        request = _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        agent = _SequencedRecoveryAgent([preview_output, apply_output])
        investigation = _RecordingInvestigationCollaborator()
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
            investigation_collaborator=investigation,  # type: ignore[arg-type]
        )

        result = coordinator.plan(request)

        material = "|".join(
            (
                request.event_id,
                request.error_fingerprint,
                request.current_execution_id,
                "APPLY_QUARANTINE",
                "f" * 64,
                "3,9",
            )
        )
        expected_fingerprint = hashlib.sha256(material.encode("utf-8")).hexdigest()
        self.assertEqual("CANDIDATE_READY", result.status)
        self.assertEqual("APPLY_QUARANTINE", result.action)
        self.assertEqual("LOW", result.risk_level)
        self.assertTrue(result.idempotent)
        self.assertEqual(expected_fingerprint, result.repair_fingerprint)
        self.assertEqual(1, len(investigation.calls))
        self.assertEqual(2, len(agent.requests))
        summary = result.to_summary()
        self.assertEqual(
            {
                "taskId": 31,
                "executionId": 41,
                "selectedCount": 2,
                "eligibleCount": 2,
                "confirmationDigest": "f" * 64,
                "selectedSampleIds": (3, 9),
                "issueCodes": (),
                "auditId": "audit-preview-1",
                "runId": "run-preview-1",
                "outputRef": "agent-runtime://sessions/session-1/runs/run-preview-1/tool-results/audit-preview-1",
            },
            summary["quarantinePreview"],
        )
        self.assertNotIn("operationState", summary["quarantinePreview"])

    def test_apply_quarantine_without_real_preview_receipt_requires_attention(self) -> None:
        """模型直接提出 APPLY 时，缺少真实 Java preview receipt 必须 fail-closed。

        中文说明：这里不装配 investigation collaborator，证明模型动作本身和伪造 actionFingerprint 都不能
        解锁隔离。协调器返回低敏 ATTENTION_REQUIRED，Java 因而不会把 Python 响应误当成 data-sync apply
        请求；真正的 preview 必须先由 Java ToolPlan/receipt 链完成。

        English: an apply proposal without prior Java receipt is a business attention outcome, not a Python tool
        failure and not a direct write.  This distinction lets Java persist a clear governance state without invoking
        quarantine or retry.
        """

        output = {
            "repairActions": ({"actionType": "APPLY_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "9" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.9,
        }
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((_SequencedRecoveryAgent([output]),)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("RECOVERY_APPLY_QUARANTINE_PREVIEW_RECEIPT_REQUIRED", result.reason_code)
        self.assertEqual({}, result.to_summary()["quarantinePreview"])

    def test_invalid_preview_receipt_never_unlocks_apply_quarantine(self) -> None:
        """已完成但含 issue 的 preview 不是可 apply receipt，必须保留人工关注。

        本例覆盖 receipt 的业务完整性而非 transport 缺失：即使 action、audit、run 与 URI 看似正常，
        ``issueCodes`` 非空表示 data-sync 明确拒绝精确隔离。Python 不能通过修改模型置信度、候选指纹或
        风险标签绕过这一 Java 事实。
        """

        preview_output = {
            "repairActions": ({"actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "c" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        apply_output = {
            "repairActions": ({"actionType": "APPLY_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "d" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.95,
        }
        investigation = _RecordingInvestigationCollaborator(
            preview_result={
                "taskId": 31,
                "executionId": 41,
                "selectedCount": 2,
                "eligibleCount": 2,
                "operationState": "PREVIEWED",
                "confirmationDigest": "f" * 64,
                "selectedSampleIds": (3, 9),
                "issueCodes": ("NO_ELIGIBLE_DIRTY_SAMPLE",),
            }
        )
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry(
                (_SequencedRecoveryAgent([preview_output, apply_output]),)
            ),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            investigation_collaborator=investigation,  # type: ignore[arg-type]
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID", result.reason_code)
        self.assertEqual({}, result.quarantine_preview)

    def test_repeated_search_and_preview_requests_stop_at_their_one_shot_limits(self) -> None:
        """RAG 与 Java preview 各最多一次，第三轮重复 SEARCH 必须在调用前停止。

        中文说明：第一轮模型自主选择 SEARCH，第二轮基于一次 RAG 结果选择 PREVIEW_QUARANTINE，第三轮又
        请求 SEARCH。协调器必须在第三轮返回固定 attention 结果，既不发起第二次 RAG，也不丢弃已发生的
        preview receipt；这使同一 Autopilot 请求的内层循环保持有界。

        English: the test deliberately traverses both evidence expansions, then repeats SEARCH.  It proves that the
        coordinator records one RAG call and one Java-governed preview only, while the outer recovery-cycle budget
        remains owned by Java rather than a recursive Python planner.
        """

        search_output = {
            "repairActions": ({"actionType": "SEARCH_RECOVERY_KNOWLEDGE"},),
            "actionFingerprint": "sha256:" + "1" * 64,
            "retrievalDecision": "SEARCH",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.7,
        }
        preview_output = {
            "repairActions": ({"actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "2" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.8,
        }
        repeated_search_output = {
            "repairActions": ({"actionType": "SEARCH_RECOVERY_KNOWLEDGE"},),
            "actionFingerprint": "sha256:" + "3" * 64,
            "retrievalDecision": "SEARCH",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.8,
        }
        rag = _StaticRagPipeline()
        investigation = _RecordingInvestigationCollaborator()
        agent = _SequencedRecoveryAgent([search_output, preview_output, repeated_search_output])
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=rag,  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            investigation_collaborator=investigation,  # type: ignore[arg-type]
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("RECOVERY_RETRIEVAL_LOOP_LIMIT_REACHED", result.reason_code)
        self.assertEqual(1, len(rag.queries))
        self.assertEqual(1, len(investigation.calls))
        self.assertEqual(3, len(agent.requests))

    def test_preview_plus_mutating_action_never_discards_the_later_write_intent(self) -> None:
        """调查与写动作混合时保持整体阻断，不能为了自治而静默丢弃 apply/replay/schema。"""

        output = {
            "repairActions": (
                {"actionType": "PREVIEW_QUARANTINE"},
                {"actionType": "REPLAY_FAILED_SHARDS"},
            ),
            "actionFingerprint": "sha256:" + "e" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((_SequencedRecoveryAgent([output]),)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("MULTIPLE_RECOVERY_ACTIONS_REQUIRE_REVIEW", result.reason_code)

    def test_retry_plus_schema_change_still_requires_human_review(self) -> None:
        """存在第二个真实副作用时不得替模型选一个执行，尤其不能忽略 schema 变更。"""

        output = {
            "repairActions": (
                {
                    "actionType": "RETRY_FAILED_OBJECTS",
                    "classification": "HIGH_RISK_SIDE_EFFECT",
                    "requiresApproval": True,
                },
                {
                    "actionType": "CHANGE_SCHEMA",
                    "classification": "HIGH_RISK_SIDE_EFFECT",
                    "requiresApproval": True,
                },
            ),
            "actionFingerprint": "sha256:" + "9" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((_SequencedRecoveryAgent([output]),)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("MULTIPLE_RECOVERY_ACTIONS_REQUIRE_REVIEW", result.reason_code)

    def test_specialist_checkpoint_records_only_bounded_low_sensitive_action_codes(self) -> None:
        """checkpoint 只保存动作类型摘要，不能把模型参数、理由或其它正文持久化。"""

        repair_actions = (
            {
                "actionType": "retry failed objects",
                "parameters": {"credential": "must-not-be-persisted"},
                "reason": "must-not-be-persisted",
            },
            {"actionType": "RETRY_FAILED_OBJECTS"},
            {"actionType": "CHANGE_SCHEMA", "arguments": {"ddl": "must-not-be-persisted"}},
            *({"actionType": f"PLATFORM_ACTION_{index}"} for index in range(1, 10)),
        )
        output = {
            "repairActions": repair_actions,
            "actionFingerprint": "sha256:" + "c" * 64,
            "retrievalDecision": "SKIP",
            "retrievalStrategy": "STRUCTURED_DIAGNOSTIC",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.88,
        }
        checkpointer = LangGraphDurableCheckpointerService()
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((_SequencedRecoveryAgent([output]),)),
            rag_pipeline=_StaticRagPipeline(),  # type: ignore[arg-type]
            checkpointer=checkpointer,
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(
            _request(cycle=1, repeated_error_count=0, previous_repair_fingerprint=None)
        )

        latest = checkpointer.latest_for_thread(result.checkpoint_thread_id or "")
        self.assertIsNotNone(latest)
        specialist = checkpointer.checkpoint_by_id(latest.parent_checkpoint_id or "")
        self.assertIsNotNone(specialist)
        self.assertEqual(len(repair_actions), specialist.state["repairActionCount"])
        self.assertEqual(
            (
                "RETRY_FAILED_OBJECTS",
                "CHANGE_SCHEMA",
                "PLATFORM_ACTION_1",
                "PLATFORM_ACTION_2",
                "PLATFORM_ACTION_3",
                "PLATFORM_ACTION_4",
                "PLATFORM_ACTION_5",
                "PLATFORM_ACTION_6",
                "PLATFORM_ACTION_7",
                "PLATFORM_ACTION_8",
            ),
            specialist.state["repairActionCodes"],
        )
        serialized_state = repr(specialist.state)
        self.assertNotIn("must-not-be-persisted", serialized_state)
        self.assertNotIn("credential", serialized_state)
        self.assertNotIn("arguments", serialized_state)

    def test_search_without_citations_stops_before_second_recovery_turn(self) -> None:
        """检索没有引用时必须转人工关注，不能让第二轮模型假装已有证据。"""

        output = {
            "repairActions": ({"actionType": "SEARCH_RECOVERY_KNOWLEDGE"},),
            "actionFingerprint": "sha256:" + "7" * 64,
            "retrievalDecision": "SEARCH",
            "retrievalStrategy": "RAG",
            "diagnosticEvidenceGate": {"satisfied": True, "ragRequired": False},
            "evidenceAudit": _evidence_audit(),
            "modelConfidence": 0.75,
        }
        agent = _SequencedRecoveryAgent([output])
        coordinator = AutopilotRecoveryCoordinator(
            specialist_registry=SpecialistAgentRegistry((agent,)),
            rag_pipeline=_StaticRagPipeline(with_citation=False),  # type: ignore[arg-type]
            checkpointer=LangGraphDurableCheckpointerService(),
            result_sink=_durable_fact_sink,
        )

        result = coordinator.plan(_request())

        self.assertEqual("ATTENTION_REQUIRED", result.status)
        self.assertEqual("RECOVERY_SEARCH_EVIDENCE_NOT_FOUND", result.reason_code)
        self.assertEqual(1, len(agent.requests))

    def test_expired_deadline_is_rejected_before_any_agent_call(self) -> None:
        """过期触发不能进入诊断、模型或 RAG。"""

        with self.assertRaisesRegex(ValueError, "deadline"):
            _request(deadline_at=(datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat())


if __name__ == "__main__":
    unittest.main()
