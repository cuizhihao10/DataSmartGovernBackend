"""使用 PRECHECK_AGENT 与 MONITOR_AGENT 复核一次真实的 Autopilot 重试。

Python 恢复规划有意停在业务副作用之前。授权、策略评估、幂等、隔离以及真实的失败对象
重试均由 Java 和 data-sync 负责。本模块只在 data-sync 返回真实动作回执后启动，再让两个
只读 Specialist 针对回执绑定的任务和执行进行检查，并通过既有 Java 事实接收端持久化回合
事实。

该流程具备安全重放能力：

* 根据恢复事件和动作回执派生唯一且稳定的 checkpoint 线程；
* 为每个 Specialist 角色派生稳定的回合 ID；
* 已完成的 checkpoint 直接重放结果，不会再次调用 Specialist；
* 中断后的尝试可以重新执行，但 Java 会收到相同的回合 ID 和幂等键，因此只会更新原有
  两条事实，不会插入重复记录。

本模块中的任何方法都无权重试任务、执行隔离、运行 SQL 或扩大授权范围；它只负责动作后
的只读观察边界。
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import (
    SpecialistAgentCoordinator,
)


AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION = (
    "datasmart.autopilot.post-recovery-verification.v1"
)
_PAYLOAD_POLICY = "LOW_SENSITIVE_AUTOPILOT_POST_RECOVERY_VERIFICATION_ONLY"
_REQUIRED_ROLES = ("PRECHECK_AGENT", "MONITOR_AGENT")
_SAFE_CODE = re.compile(r"[A-Z0-9_.:-]{1,96}")


class AutopilotPostRecoveryVerificationError(RuntimeError):
    """表示一次可以由基础设施重试的恢复后复核故障。"""


@dataclass(frozen=True)
class AutopilotPostRecoveryVerificationRequest:
    """Java 根据真实 data-sync 重试回执构造的可信请求投影。

    身份与作用域字段来自已经通过校验的恢复触发事件。``task_id`` 和 ``execution_id``
    来自 data-sync 重试响应，且必须与触发事件作用域完全一致。该相等性校验可以阻止格式
    错误或被篡改的下游响应把 Specialist 的读取请求重定向到其他资源。
    """

    event_id: str
    root_session_id: str
    root_run_id: str
    tenant_id: str
    application_id: str
    project_id: str
    user_id: str
    actor_id: str
    agent_id: str
    delegation_id: str
    workspace_key: str
    sync_task_id: str
    current_execution_id: str
    task_id: str
    execution_id: str
    case_id: str
    recovery_action: str
    cycle: int

    def __post_init__(self) -> None:
        """在写入 checkpoint 或发起 HTTP 调用前规范化内部合同。

        这是纵深防御措施，不能替代 Java 授权。自由格式引用会受到长度和字符约束，数据库
        ID 必须为十进制正整数，回执中的资源定位信息也必须处于原触发事件作用域内。
        """

        reference_fields = (
            "event_id",
            "root_session_id",
            "root_run_id",
            "user_id",
            "actor_id",
            "agent_id",
            "delegation_id",
        )
        for field_name in reference_fields:
            value = _bounded_reference(getattr(self, field_name))
            if value is None:
                raise ValueError(f"{field_name} 不是受支持的内部引用")
            object.__setattr__(self, field_name, value)

        workspace_key = str(self.workspace_key or "").strip()
        if not workspace_key or len(workspace_key) > 240:
            raise ValueError("workspace_key 缺失或过长")
        object.__setattr__(self, "workspace_key", workspace_key)

        numeric_fields = (
            "tenant_id",
            "application_id",
            "project_id",
            "sync_task_id",
            "current_execution_id",
            "task_id",
            "execution_id",
            "case_id",
        )
        for field_name in numeric_fields:
            value = str(getattr(self, field_name) or "").strip()
            if not value.isascii() or not value.isdecimal() or int(value) <= 0:
                raise ValueError(f"{field_name} 必须是正整数标识")
            object.__setattr__(self, field_name, value)

        action = str(self.recovery_action or "").strip().upper()
        if not _SAFE_CODE.fullmatch(action):
            raise ValueError("recovery_action 不是受支持的动作代码")
        object.__setattr__(self, "recovery_action", action)
        if self.cycle < 1:
            raise ValueError("cycle 必须大于零")
        if self.task_id != self.sync_task_id:
            raise ValueError("data-sync retry receipt 的 taskId 与恢复触发范围不一致")
        if self.execution_id != self.current_execution_id:
            raise ValueError("data-sync retry receipt 的 executionId 与恢复触发范围不一致")

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AutopilotPostRecoveryVerificationRequest":
        """映射固定的 Java JSON 请求体，不为安全字段虚构默认值。"""

        if not isinstance(payload, Mapping):
            raise ValueError("Autopilot post-recovery payload 必须是 JSON object")
        return cls(
            event_id=str(payload.get("eventId") or ""),
            root_session_id=str(payload.get("rootSessionId") or ""),
            root_run_id=str(payload.get("rootRunId") or ""),
            tenant_id=str(payload.get("tenantId") or ""),
            application_id=str(payload.get("applicationId") or ""),
            project_id=str(payload.get("projectId") or ""),
            user_id=str(payload.get("userId") or ""),
            actor_id=str(payload.get("actorId") or ""),
            agent_id=str(payload.get("agentId") or ""),
            delegation_id=str(payload.get("delegationId") or ""),
            workspace_key=str(payload.get("workspaceKey") or ""),
            sync_task_id=str(payload.get("syncTaskId") or ""),
            current_execution_id=str(payload.get("currentExecutionId") or ""),
            task_id=str(payload.get("taskId") or ""),
            execution_id=str(payload.get("executionId") or ""),
            case_id=str(payload.get("caseId") or ""),
            recovery_action=str(payload.get("recoveryAction") or ""),
            cycle=int(payload.get("cycle") or 0),
        )

    def binding(self) -> dict[str, str | int]:
        """返回写入 checkpoint 的低敏、不可变身份绑定。"""

        return {
            "eventId": self.event_id,
            "tenantId": self.tenant_id,
            "applicationId": self.application_id,
            "projectId": self.project_id,
            "actorId": self.actor_id,
            "userId": self.user_id,
            "agentId": self.agent_id,
            "sessionId": self.root_session_id,
            "runId": self.root_run_id,
            "delegationId": self.delegation_id,
            "workspaceKey": self.workspace_key,
            "taskId": self.task_id,
            "executionId": self.execution_id,
            "caseId": self.case_id,
            "recoveryAction": self.recovery_action,
            "cycle": self.cycle,
        }


@dataclass(frozen=True)
class AutopilotPostRecoveryVerificationResult:
    """两条事实持久化完成后返回给 Java 的低敏成功合同。"""

    event_id: str
    task_id: str
    execution_id: str
    executed_roles: tuple[str, ...]
    completed_roles: tuple[str, ...]
    batch_status: str
    checkpoint_thread_id: str
    replayed: bool = False

    def to_summary(self) -> dict[str, Any]:
        """只序列化稳定的角色和资源证据，绝不写入工具原始输出。"""

        return {
            "schemaVersion": AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION,
            "status": "VERIFIED",
            "eventId": self.event_id,
            "taskId": self.task_id,
            "executionId": self.execution_id,
            "executedRoles": self.executed_roles,
            "completedRoles": self.completed_roles,
            "batchStatus": self.batch_status,
            "checkpointThreadId": self.checkpoint_thread_id,
            "replayed": self.replayed,
            "payloadPolicy": _PAYLOAD_POLICY,
        }

    @classmethod
    def from_summary(
        cls,
        summary: Mapping[str, Any],
        *,
        replayed: bool,
    ) -> "AutopilotPostRecoveryVerificationResult":
        """重建并校验用于重放的终态 checkpoint 响应。"""

        if (
            not isinstance(summary, Mapping)
            or summary.get("schemaVersion")
            != AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION
            or summary.get("status") != "VERIFIED"
            or summary.get("payloadPolicy") != _PAYLOAD_POLICY
        ):
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_CHECKPOINT_RESULT_INVALID"
            )
        return cls(
            event_id=str(summary.get("eventId") or ""),
            task_id=str(summary.get("taskId") or ""),
            execution_id=str(summary.get("executionId") or ""),
            executed_roles=tuple(str(item) for item in summary.get("executedRoles") or ()),
            completed_roles=tuple(str(item) for item in summary.get("completedRoles") or ()),
            batch_status=str(summary.get("batchStatus") or ""),
            checkpoint_thread_id=str(summary.get("checkpointThreadId") or ""),
            replayed=replayed,
        )


class AutopilotPostRecoveryVerificationCoordinator:
    """运行两个动作后只读 Specialist，并持久登记其事实。"""

    def __init__(
        self,
        *,
        specialist_coordinator: SpecialistAgentCoordinator,
        allowed_tools_by_role: Mapping[str, tuple[str, ...]],
        checkpointer: LangGraphDurableCheckpointerService,
        result_sink: Callable[[SpecialistTurnRequest, SpecialistTurnResult], Any],
    ) -> None:
        """保存共享运行时依赖和 Java 持久事实接收端。

        事实接收端有意设计为必填依赖。若复核响应没有对应的持久事实，Java 可能在审计证据
        仍只存在于 Python 内存时就确认 Kafka 消息，从而造成不可追溯的恢复结果。
        """

        if specialist_coordinator is None or checkpointer is None or result_sink is None:
            raise ValueError("Autopilot post-recovery verification dependencies are required")
        self._specialist_coordinator = specialist_coordinator
        self._allowed_tools_by_role = {
            role: tuple(allowed_tools_by_role.get(role) or ())
            for role in _REQUIRED_ROLES
        }
        self._checkpointer = checkpointer
        self._result_sink = result_sink

    def verify(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
    ) -> AutopilotPostRecoveryVerificationResult:
        """复核一个动作回执，持久化两条事实，并记录最终 checkpoint。

        已完成的 checkpoint 会被直接返回。若上次执行中断，则使用相同的两个回合 ID 重跑，
        Java 事实库据此执行保持身份不变的 upsert。任何角色缺失、Specialist 失败、事实接收
        失败或 checkpoint 失败都会抛出技术异常，使 Java Kafka 监听器不确认该消息，并进入
        有界重试或 DLT。
        """

        if not isinstance(request, AutopilotPostRecoveryVerificationRequest):
            raise TypeError("request 必须是 AutopilotPostRecoveryVerificationRequest")
        thread_id = _verification_thread_id(request)
        binding = request.binding()
        latest = self._checkpointer.latest_for_thread(thread_id)
        if latest is not None:
            if dict(latest.state.get("requestBinding") or {}) != binding:
                raise AutopilotPostRecoveryVerificationError(
                    "AUTOPILOT_POST_RECOVERY_CHECKPOINT_SCOPE_CONFLICT"
                )
            terminal = latest.state.get("terminalResult")
            if (
                latest.node_name == "autopilot_post_recovery_verification_completed"
                and latest.status == LangGraphCheckpointStatus.COMPLETED
                and isinstance(terminal, Mapping)
            ):
                replay = AutopilotPostRecoveryVerificationResult.from_summary(
                    terminal,
                    replayed=True,
                )
                self._validate_result_binding(request, replay)
                return replay
        else:
            self._record_started_checkpoint(request, thread_id, binding)

        agent_request = self._agent_request(request)
        turn_runner = {
            "maxConcurrentAgentTurns": 2,
            "turnAttempts": tuple(
                {
                    "turnId": _verification_turn_id(request, role),
                    "agentRole": role,
                    "turnStatus": "READY_FOR_SPECIALIST_TURN",
                }
                for role in _REQUIRED_ROLES
            ),
        }
        execution_session = {
            "sessionId": request.root_session_id,
            "runId": request.root_run_id,
            "workItems": tuple(
                {
                    "agentRole": role,
                    "dependsOnRoles": ("MASTER_ORCHESTRATOR",),
                }
                for role in _REQUIRED_ROLES
            ),
        }
        batch = self._specialist_coordinator.run(
            request=agent_request,
            turn_runner=turn_runner,
            execution_session=execution_session,
            allowed_tools_by_role=self._allowed_tools_by_role,
            base_context={
                "taskId": request.task_id,
                "executionId": request.execution_id,
                "resourceReference": request.task_id,
                "resourceFingerprint": _resource_fingerprint(request),
                "postRecoveryVerification": True,
                "recoveryAction": request.recovery_action,
                "recoveryCaseId": request.case_id,
            },
            checkpoint_recorded=True,
            event_sink=None,
            result_sink=self._register_durable_fact,
        )
        results = tuple(batch.results or ())
        executed_roles = tuple(sorted(item.role.value for item in results))
        completed_roles = tuple(
            sorted(
                item.role.value
                for item in results
                if item.status == SpecialistTurnStatus.COMPLETED
            )
        )
        if (
            executed_roles != tuple(sorted(_REQUIRED_ROLES))
            or completed_roles != tuple(sorted(_REQUIRED_ROLES))
            or batch.status != "COMPLETED"
        ):
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_SPECIALIST_VERIFICATION_INCOMPLETE"
            )

        result = AutopilotPostRecoveryVerificationResult(
            event_id=request.event_id,
            task_id=request.task_id,
            execution_id=request.execution_id,
            executed_roles=executed_roles,
            completed_roles=completed_roles,
            batch_status=batch.status,
            checkpoint_thread_id=thread_id,
        )
        self._record_completed_checkpoint(request, thread_id, binding, result)
        return result

    def _register_durable_fact(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> Any:
        """要求配置的事实接收端明确确认已经完成持久登记。

        本地开发时，``JavaSpecialistTurnFactClient`` 可以配置为失败放行，但 Autopilot 不能
        采用这种较弱语义：无人值守恢复只有在每条 Specialist 事实都被真实接收后才能提交
        Kafka。因此，即使共享客户端没有抛出异常，被跳过或未登记的回执也会转为可重试的
        技术故障。
        """

        receipt = self._result_sink(request, result)
        registered = bool(
            receipt.get("registered")
            if isinstance(receipt, Mapping)
            else getattr(receipt, "registered", False)
        )
        duplicate = bool(
            receipt.get("duplicate")
            if isinstance(receipt, Mapping)
            else getattr(receipt, "duplicate", False)
        )
        if not registered and not duplicate:
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_SPECIALIST_FACT_NOT_DURABLE"
            )
        return receipt

    def _agent_request(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
    ) -> AgentRequest:
        """构造协调器所需的最小可信请求。"""

        return AgentRequest(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            objective="复核自动恢复后的同步任务预检查与运行状态。",
            request_id=f"autopilot-post-recovery:{request.event_id}",
            variables={
                "workspaceKey": request.workspace_key,
                "autopilotPostRecoveryVerification": True,
                "trustedControlPlane": {
                    "tenantId": request.tenant_id,
                    "applicationId": request.application_id,
                    "projectId": request.project_id,
                    "actorId": request.actor_id,
                    "delegationId": request.delegation_id,
                },
            },
        )

    def _record_started_checkpoint(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
        thread_id: str,
        binding: Mapping[str, Any],
    ) -> None:
        """在设置 ``checkpoint_recorded=True`` 前先持久化准入事实。"""

        self._checkpointer.record_checkpoint(
            LangGraphDurableCheckpoint(
                checkpoint_id=_checkpoint_id(request, "started"),
                thread_id=thread_id,
                graph_name="autopilot-post-recovery-verification",
                graph_version="v1",
                node_name="autopilot_post_recovery_verification_started",
                status=LangGraphCheckpointStatus.RUNNING,
                state={"requestBinding": dict(binding)},
                next_nodes=("precheck_agent", "monitor_agent"),
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                workspace_key=request.workspace_key,
                run_id=request.root_run_id,
                session_id=request.root_session_id,
                checkpoint_version=1,
                low_sensitive_summary=(
                    "Autopilot 重试回执已进入只读 Specialist 复核流程。"
                ),
            ),
            event_type="autopilot_post_recovery_verification_started",
        )

    def _record_completed_checkpoint(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
        thread_id: str,
        binding: Mapping[str, Any],
        result: AutopilotPostRecoveryVerificationResult,
    ) -> None:
        """持久化可重放的低敏终态响应。"""

        self._checkpointer.record_checkpoint(
            LangGraphDurableCheckpoint(
                checkpoint_id=_checkpoint_id(request, "completed"),
                thread_id=thread_id,
                graph_name="autopilot-post-recovery-verification",
                graph_version="v1",
                node_name="autopilot_post_recovery_verification_completed",
                status=LangGraphCheckpointStatus.COMPLETED,
                state={
                    "requestBinding": dict(binding),
                    "terminalResult": result.to_summary(),
                },
                next_nodes=(),
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                workspace_key=request.workspace_key,
                run_id=request.root_run_id,
                session_id=request.root_session_id,
                checkpoint_version=2,
                low_sensitive_summary=(
                    "PRECHECK_AGENT 与 MONITOR_AGENT 事实已完成持久登记。"
                ),
                created_at=datetime.now(timezone.utc),
                updated_at=datetime.now(timezone.utc),
            ),
            event_type="autopilot_post_recovery_verification_completed",
        )

    @staticmethod
    def _validate_result_binding(
        request: AutopilotPostRecoveryVerificationRequest,
        result: AutopilotPostRecoveryVerificationResult,
    ) -> None:
        """在向 Java 重放前拒绝格式错误或绑定不一致的终态 checkpoint。"""

        if (
            result.event_id != request.event_id
            or result.task_id != request.task_id
            or result.execution_id != request.execution_id
            or result.executed_roles != tuple(sorted(_REQUIRED_ROLES))
            or result.completed_roles != tuple(sorted(_REQUIRED_ROLES))
            or result.batch_status != "COMPLETED"
        ):
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_CHECKPOINT_BINDING_INVALID"
            )


def _resource_fingerprint(request: AutopilotPostRecoveryVerificationRequest) -> str:
    """对回执绑定资源计算哈希，且不嵌入用户数据。"""

    material = f"{request.task_id}|{request.execution_id}"
    return "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()


def _bounded_reference(value: Any) -> str | None:
    """接受常见企业身份格式，同时拒绝可控制请求头的危险字符。

    操作者与用户标识可能合法地使用邮箱、外部主体名称或其他可打印文本，而不一定是数据库
    数字。若强制套用普通标识符正则，会误拒绝合法租户。本方法只保留真正的信任边界：内容
    非空、最长 160 个字符，且不得包含可能破坏日志或下游 HTTP 请求头的 ASCII 控制字符。
    """

    text = str(value or "").strip()
    if not text or len(text) > 160:
        return None
    if any(ord(character) < 32 or ord(character) == 127 for character in text):
        return None
    return text


def _verification_thread_id(request: AutopilotPostRecoveryVerificationRequest) -> str:
    """为每个恢复事件及其真实重试回执创建唯一的持久线程。"""

    material = f"{request.event_id}|{request.task_id}|{request.execution_id}"
    digest = hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]
    return f"autopilot-post-recovery:{digest}"


def _verification_turn_id(
    request: AutopilotPostRecoveryVerificationRequest,
    role: str,
) -> str:
    """为单个 Specialist 角色创建稳定的 Java 事实身份。"""

    material = (
        f"{request.event_id}|{request.task_id}|{request.execution_id}|{role}|"
        "autopilot-post-recovery-verification"
    )
    return "autopilot-verify-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def _checkpoint_id(
    request: AutopilotPostRecoveryVerificationRequest,
    phase: str,
) -> str:
    """创建确定性 checkpoint ID，使响应丢失后的重试执行 upsert。"""

    material = (
        f"{request.event_id}|{request.task_id}|{request.execution_id}|{phase}|"
        "autopilot-post-recovery-verification"
    )
    return "aprv-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]


__all__ = (
    "AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION",
    "AutopilotPostRecoveryVerificationCoordinator",
    "AutopilotPostRecoveryVerificationError",
    "AutopilotPostRecoveryVerificationRequest",
    "AutopilotPostRecoveryVerificationResult",
)
