"""Agent Runtime 实时事件可见性与字段脱敏策略。

Java agent-runtime 在 3.90 阶段已经为 HTTP 查询入口补齐了事件类型可见性与字段级脱敏；但实时
WebSocket/replay 通道的事件 envelope 是 Python AI Runtime 构建的。如果只保护 Java 查询接口，
前端仍可能通过 `/agent/events/ws` 或 `/agent/events/replay` 拿到未经脱敏的 prompt、SQL、Token、
原始输入输出和调试上下文。

因此本模块在 Python 侧建立一套与 Java 同构的轻量策略，并让 replay/live push 在入 envelope 前
统一调用它。当前策略先写在代码中，便于稳定协议；后续更成熟的产品形态应迁移到 permission-admin
字段级策略、租户配置或工具 schema 的 `sensitive=true` 标记中。
"""

from __future__ import annotations

from dataclasses import replace
from enum import Enum
import re
from threading import Lock
from typing import Any

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent


class RuntimeEventVisibilityLevel(str, Enum):
    """实时事件细节可见级别。

    这里的级别不是登录角色本身，而是多个角色归并后的“事件细节展示策略”：
    - `FULL`：平台管理员、租户管理员、运维员、服务账号可查看完整运行轨迹，用于故障定位；
    - `AUDIT`：审计员可查看关键审计/工具/异常事件，但不暴露 prompt 快照、token 流和原始上下文；
    - `PROJECT`：项目负责人可查看本项目运行进度和工具行为，但不暴露模型内部上下文；
    - `BASIC`：普通用户只看业务进度类事件，并对 attributes 做最保守脱敏。
    """

    FULL = "FULL"
    AUDIT = "AUDIT"
    PROJECT = "PROJECT"
    BASIC = "BASIC"


class RuntimeEventVisibilityPolicy:
    """Agent runtime event 的角色可见性与脱敏策略。

    该类只依赖订阅请求和事件对象，不依赖 FastAPI、WebSocket、Redis 或 Kafka。这样它可以同时用于：
    - HTTP replay 查询；
    - WebSocket subscribe/reconnect 的 replay envelope；
    - live push 入队前的 envelope；
    - 后续 Kafka/审计导出前的“面向用户视图”脱敏。

    注意：它不负责身份认证，也不负责租户/项目范围判断。范围判断已经由订阅授权器、会话管理器和
    store 的 replay 条件处理；本类只处理“已经匹配到的事件，当前角色能不能看，以及能看多细”。
    """

    MASKED_VALUE = "***MASKED***"
    MASKED_FIELDS_ATTRIBUTE = "_datasmartMaskedFields"
    VISIBILITY_LEVEL_ATTRIBUTE = "_datasmartVisibilityLevel"

    _SENSITIVE_KEY_PATTERN = re.compile(
        r".*(password|passwd|secret|token|authorization|api[-_]?key|credential|prompt|"
        r"systemprompt|userprompt|sql|query|sample|row|stack|trace|exception|raw|payload|"
        r"input|output|embedding|vector|kv[-_]?cache|memory|cookie|session[-_]?key).*",
        re.IGNORECASE,
    )

    _PROJECT_HIDDEN_EVENT_MARKERS = frozenset(
        {
            "prompt",
            "memory_retrieved",
            "memory_raw",
            "raw_context",
            "token_stream",
            "debug",
            "internal",
            "kv_cache",
            "model_gateway_routed",
        }
    )

    _AUDIT_HIDDEN_EVENT_MARKERS = frozenset(
        {
            "token_stream",
            "debug_chunk",
            "model_prompt",
            "prompt_snapshot",
            "memory_raw",
            "raw_context",
            "kv_cache",
        }
    )

    _BASIC_VISIBLE_EVENT_MARKERS = frozenset(
        {
            "started",
            "completed",
            "failed",
            "cancelled",
            "canceled",
            "progress",
            "status",
            "approval_required",
            "approval_waiting",
            "context_collected",
            "context_filtered",
            "context_deduplicated",
            "context_truncated",
            "intent_analyzed",
            "tool_planned",
            "tool_action_intake_recorded",
            "tool_execution",
            "tool_completed",
            "tool_parameter_validated",
            "model_tool_call_proposed",
            "model_tool_call_accepted",
            "model_tool_call_rejected",
            "model_tool_call_approval_required",
            "skill_visibility_snapshot_recorded",
            "tool_result_feedback_built",
            "agent_loop_control_decided",
            "model_second_turn_completed",
            "model_second_turn_skipped",
            "run_started",
            "run_completed",
            "run_failed",
        }
    )

    def __init__(self, stats: "RuntimeEventVisibilityStats | None" = None) -> None:
        """初始化可见性策略。

        `stats` 是可选统计器。策略本身可以独立工作，便于单元测试和离线工具复用；在 FastAPI 运行态
        组件中会注入共享 stats，让 replay 与 live push 的脱敏/过滤行为进入同一个诊断快照。
        """

        self._stats = stats

    def filter_and_mask(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        request: RuntimeEventSubscriptionRequest,
    ) -> tuple[AgentRuntimeEvent, ...]:
        """按订阅请求中的角色信息过滤并脱敏事件集合。

        调用方应在“范围匹配”之后调用本方法。这样职责层次更清晰：
        1. store/live hub 先判断事件是否属于当前 session/run/request/tenant/project；
        2. visibility policy 再判断当前角色是否应看到这类事件；
        3. 最后才构建 WebSocket/replay envelope。
        """

        if not events:
            return ()

        level = self.resolve_level(request)
        visible_events: list[AgentRuntimeEvent] = []
        filtered_count = 0
        masked_field_count = 0
        masked_event_count = 0

        for event in events:
            if not self.is_event_visible(event, level):
                filtered_count += 1
                continue
            masked_event = self.mask_event(event, level)
            event_masked_field_count = self._masked_field_count(masked_event)
            if event_masked_field_count > 0:
                masked_event_count += 1
                masked_field_count += event_masked_field_count
            visible_events.append(masked_event)

        if self._stats is not None:
            self._stats.record(
                level=level,
                evaluated_events=len(events),
                visible_events=len(visible_events),
                filtered_events=filtered_count,
                masked_events=masked_event_count,
                masked_fields=masked_field_count,
            )
        return tuple(visible_events)

    def resolve_level(self, request: RuntimeEventSubscriptionRequest) -> RuntimeEventVisibilityLevel:
        """把订阅请求中的角色集合归并为可见性级别。

        生产上角色通常来自 Java gateway 透传的认证上下文。这里对大小写、短横线和下划线做归一化，
        让 `PROJECT_OWNER`、`project-owner`、`project_owner` 都能落到同一策略。

        如果没有任何角色，按 `BASIC` 处理而不是默认 FULL。这是实时事件通道的 fail-safe 设计：
        缺失认证上下文时最多展示低敏进度，而不是暴露完整 Agent 轨迹。
        """

        roles = {self._normalize_role(role) for role in request.roles if str(role).strip()}
        if roles & {"PLATFORM_ADMINISTRATOR", "PLATFORM_ADMIN", "TENANT_ADMIN", "OPERATOR", "SERVICE_ACCOUNT"}:
            return RuntimeEventVisibilityLevel.FULL
        if "AUDITOR" in roles:
            return RuntimeEventVisibilityLevel.AUDIT
        if "PROJECT_OWNER" in roles:
            return RuntimeEventVisibilityLevel.PROJECT
        return RuntimeEventVisibilityLevel.BASIC

    def is_event_visible(self, event: AgentRuntimeEvent, level: RuntimeEventVisibilityLevel) -> bool:
        """判断单条事件是否允许进入当前角色的实时事件流。"""

        event_type = self._normalize_event_type(event)
        if level == RuntimeEventVisibilityLevel.FULL:
            return True
        if level == RuntimeEventVisibilityLevel.AUDIT:
            return not any(marker in event_type for marker in self._AUDIT_HIDDEN_EVENT_MARKERS)
        if level == RuntimeEventVisibilityLevel.PROJECT:
            return not any(marker in event_type for marker in self._PROJECT_HIDDEN_EVENT_MARKERS)
        return any(marker in event_type for marker in self._BASIC_VISIBLE_EVENT_MARKERS)

    def mask_event(
        self,
        event: AgentRuntimeEvent,
        level: RuntimeEventVisibilityLevel,
    ) -> AgentRuntimeEvent:
        """按可见性级别生成脱敏后的事件副本。

        `AgentRuntimeEvent` 是 frozen dataclass，因此这里用 `replace(...)` 生成新对象，避免污染原始
        事件事实。这个边界非常重要：审计存储和平台管理员排障仍可能需要原始事件，而面向普通用户
        的 WebSocket frame 则必须是脱敏视图。
        """

        if level == RuntimeEventVisibilityLevel.FULL:
            return event

        masked_attributes, _ = self._mask_attributes(event.attributes, level)
        message = self._mask_text_if_needed(event.message, event, level)
        stage = self._mask_text_if_needed(event.stage, event, level)
        return replace(
            event,
            stage=stage,
            message=message,
            attributes=masked_attributes,
        )

    def _mask_attributes(
        self,
        attributes: dict[str, Any],
        level: RuntimeEventVisibilityLevel,
    ) -> tuple[dict[str, Any], tuple[str, ...]]:
        """对 attributes 做字段级脱敏，并返回脱敏字段清单。"""

        masked_fields: list[str] = []
        if not attributes:
            return {self.VISIBILITY_LEVEL_ATTRIBUTE: level.value}, ()

        masked: dict[str, Any] = {}
        for key, value in attributes.items():
            if self._is_sensitive_key(key) or level == RuntimeEventVisibilityLevel.BASIC:
                masked[key] = self.MASKED_VALUE
                masked_fields.append(key)
            else:
                masked[key] = self._mask_nested_value(key, value, masked_fields, depth=0)

        if masked_fields:
            masked[self.MASKED_FIELDS_ATTRIBUTE] = tuple(masked_fields)
        masked[self.VISIBILITY_LEVEL_ATTRIBUTE] = level.value
        return masked, tuple(masked_fields)

    def _mask_nested_value(self, path: str, value: Any, masked_fields: list[str], depth: int) -> Any:
        """递归处理嵌套 dict/list。

        递归深度限制为 4 层，是为了防御异常 payload 或非常深的工具输出对象。实时 WebSocket 通道
        对延迟敏感，脱敏逻辑不能因为一个巨大嵌套对象拖垮连接发送。
        """

        if value is None or depth >= 4:
            return value
        if isinstance(value, dict):
            masked: dict[str, Any] = {}
            for nested_key, nested_value in value.items():
                nested_key_text = str(nested_key)
                nested_path = f"{path}.{nested_key_text}"
                if self._is_sensitive_key(nested_key_text):
                    masked[nested_key_text] = self.MASKED_VALUE
                    masked_fields.append(nested_path)
                else:
                    masked[nested_key_text] = self._mask_nested_value(
                        nested_path,
                        nested_value,
                        masked_fields,
                        depth + 1,
                    )
            return masked
        if isinstance(value, (list, tuple)):
            return tuple(
                self._mask_nested_value(f"{path}[]", item, masked_fields, depth + 1)
                for item in value
            )
        return value

    def _mask_text_if_needed(
        self,
        value: str,
        event: AgentRuntimeEvent,
        level: RuntimeEventVisibilityLevel,
    ) -> str:
        """按角色和事件类型决定是否隐藏 stage/message。

        BASIC 级用户只需要知道进度，不应该从 message 中看到工具参数或内部上下文；另外，即使是
        AUDIT/PROJECT 级别，如果事件类型本身属于 prompt/raw/debug，也应隐藏文本。
        """

        if not value:
            return value
        if level == RuntimeEventVisibilityLevel.BASIC or self._is_sensitive_event_type(event):
            return "事件详情已按当前角色权限脱敏"
        return value

    def _is_sensitive_event_type(self, event: AgentRuntimeEvent) -> bool:
        event_type = self._normalize_event_type(event)
        return any(marker in event_type for marker in self._PROJECT_HIDDEN_EVENT_MARKERS | self._AUDIT_HIDDEN_EVENT_MARKERS)

    def _is_sensitive_key(self, key: str) -> bool:
        return bool(key and self._SENSITIVE_KEY_PATTERN.match(key))

    @staticmethod
    def _normalize_event_type(event: AgentRuntimeEvent) -> str:
        value = getattr(event.event_type, "value", event.event_type)
        return str(value or "").strip().lower()

    @staticmethod
    def _normalize_role(role: object) -> str:
        return str(role).strip().replace("-", "_").upper()

    def _masked_field_count(self, event: AgentRuntimeEvent) -> int:
        value = event.attributes.get(self.MASKED_FIELDS_ATTRIBUTE)
        if isinstance(value, (list, tuple, set)):
            return len(value)
        return 0


class RuntimeEventVisibilityStats:
    """实时事件可见性策略统计器。

    商业化系统做了脱敏还不够，还需要知道脱敏策略在生产中是否真的生效、是否过度过滤、是否某类角色
    总是命中大量敏感字段。该统计器先提供进程内计数快照，后续可以很自然地接到 Prometheus/Micrometer
    或安全审计日志。

    这里使用 `Lock` 是为了适配 FastAPI 多请求并发场景。虽然 Python 的 GIL 能降低部分数据竞争风险，
    但复合计数更新仍应显式加锁，避免 diagnostics 读取到半更新状态。
    """

    def __init__(self) -> None:
        self._lock = Lock()
        self._policy_evaluation_count = 0
        self._evaluated_event_count = 0
        self._visible_event_count = 0
        self._filtered_event_count = 0
        self._masked_event_count = 0
        self._masked_field_count = 0
        self._level_hit_counts: dict[str, int] = {level.value: 0 for level in RuntimeEventVisibilityLevel}

    def record(
        self,
        level: RuntimeEventVisibilityLevel,
        evaluated_events: int,
        visible_events: int,
        filtered_events: int,
        masked_events: int,
        masked_fields: int,
    ) -> None:
        """记录一次 replay/live 可见性策略执行结果。

        参数以“本次调用的增量”表达，而不是把事件对象传进统计器，是为了让统计器只关心指标，不理解
        具体业务事件结构。这样后续如果事件对象从 dataclass 迁移到 Pydantic，也不会影响统计器。
        """

        with self._lock:
            self._policy_evaluation_count += 1
            self._evaluated_event_count += max(0, evaluated_events)
            self._visible_event_count += max(0, visible_events)
            self._filtered_event_count += max(0, filtered_events)
            self._masked_event_count += max(0, masked_events)
            self._masked_field_count += max(0, masked_fields)
            self._level_hit_counts[level.value] = self._level_hit_counts.get(level.value, 0) + 1

    def snapshot(self) -> dict[str, Any]:
        """返回当前统计快照。

        返回 dict 而不是暴露内部字段，是为了 diagnostics 可以直接序列化，同时避免调用方修改内部计数。
        """

        with self._lock:
            return {
                "policyEvaluationCount": self._policy_evaluation_count,
                "evaluatedEventCount": self._evaluated_event_count,
                "visibleEventCount": self._visible_event_count,
                "filteredEventCount": self._filtered_event_count,
                "maskedEventCount": self._masked_event_count,
                "maskedFieldCount": self._masked_field_count,
                "levelHitCounts": dict(self._level_hit_counts),
            }
