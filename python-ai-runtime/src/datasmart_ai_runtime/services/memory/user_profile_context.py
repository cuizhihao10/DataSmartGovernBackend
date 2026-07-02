"""用户画像上下文服务。

该服务是用户画像能力接入 Agent 主链路的“薄适配层”。它负责把规则抽取器、画像仓储和模型上下文块
连接起来，但不直接调用模型、不执行工具、不写 Java 控制面。这样用户画像可以先成为可观察、可测试、
可撤销的记忆能力，再逐步接入真实持久化和多 Agent 协作。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSensitivityLevel, ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.memory.user_profile_extractor import (
    RuleBasedUserProfileExtractor,
    UserProfileExtractionPolicy,
)
from datasmart_ai_runtime.services.memory.user_profile_memory import (
    InMemoryUserProfileStore,
    UserProfileFacet,
    UserProfileFacetStatus,
    UserProfileScope,
    UserProfileStore,
)


@dataclass(frozen=True)
class UserProfileContextResult:
    """用户画像上下文构建结果。

    字段说明：
    - `context_blocks`：允许进入模型上下文的画像摘要块，只包含 active 事实；
    - `observed_facets`：本次请求识别到并写入 store 的候选/激活事实；
    - `active_facets`：当前范围内可用于上下文的事实；
    - `skipped_reasons`：画像未注入的原因，例如未启用、没有 active 事实等。
    """

    context_blocks: tuple[ContextBlock, ...]
    observed_facets: tuple[UserProfileFacet, ...]
    active_facets: tuple[UserProfileFacet, ...]
    skipped_reasons: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """转换为 `/agent/plans` 可返回的低敏摘要。"""

        return {
            "contextBlockCount": len(self.context_blocks),
            "observedFacetCount": len(self.observed_facets),
            "activeFacetCount": len(self.active_facets),
            "candidateFacetCount": sum(
                1 for item in self.observed_facets if item.status == UserProfileFacetStatus.CANDIDATE
            ),
            "autoActivatedFacetCount": sum(
                1
                for item in self.observed_facets
                if item.status == UserProfileFacetStatus.ACTIVE
                and bool(item.attributes.get("autoActivated"))
            ),
            "observedFacets": tuple(item.to_summary() for item in self.observed_facets),
            "activeFacets": tuple(item.to_summary() for item in self.active_facets),
            "skippedReasons": self.skipped_reasons,
            "payloadPolicy": "LOW_SENSITIVE_PROFILE_FACTS_ONLY",
        }


class UserProfileMemoryService:
    """用户画像记忆服务。

    服务职责：
    1. 从请求中建立画像 scope；
    2. 用规则抽取器识别低敏偏好事实；
    3. 把候选/激活事实写入画像 store；
    4. 读取 active 事实并转成 ContextBlock；
    5. 提供 API 诊断、候选激活和拒绝入口。

    安全边界：
    - 不保存原始 objective；
    - 不保存 SQL、工具参数、样本数据、模型输出或 token；
    - 不跨 tenant/project/actor/workspace 共享画像；
    - 不把候选事实直接当成强事实，除非置信度达到策略阈值并且属于低风险偏好。
    """

    def __init__(
        self,
        *,
        store: UserProfileStore | None = None,
        extractor: RuleBasedUserProfileExtractor | None = None,
        enabled: bool = True,
        inject_context_enabled: bool = True,
    ) -> None:
        self._store = store or InMemoryUserProfileStore()
        self._extractor = extractor or RuleBasedUserProfileExtractor()
        self._enabled = enabled
        self._inject_context_enabled = inject_context_enabled

    @classmethod
    def default(cls) -> "UserProfileMemoryService":
        """创建默认画像服务。

        该方法方便 API bootstrap 与单元测试复用。生产环境后续可替换为 SQL/Redis store，但仍保留同一
        服务入口与响应结构。
        """

        return cls()

    def observe_and_build_context(self, request: AgentRequest) -> UserProfileContextResult:
        """观察请求并构建可注入上下文的用户画像。

        这是 Agent 主链路会调用的方法。它的副作用仅限画像 store 中的低敏事实候选/激活事实，不会触发
        工具执行、审批、worker 或外部网络请求。这样画像能力能在 `/agent/plans` 中闭环，又不会破坏
        当前项目对副作用边界的收敛要求。
        """

        if not self._enabled:
            return UserProfileContextResult(
                context_blocks=(),
                observed_facets=(),
                active_facets=(),
                skipped_reasons=("USER_PROFILE_MEMORY_DISABLED",),
            )
        scope = self.scope_from_request(request)
        observed = tuple(self._store.upsert_observed_facet(facet) for facet in self._extractor.extract(request, scope))
        active = self._store.active_facets(scope)
        context_blocks = self._context_blocks_from_active_facets(scope, active) if self._inject_context_enabled else ()
        skipped: list[str] = []
        if not active:
            skipped.append("NO_ACTIVE_PROFILE_FACTS")
        if not self._inject_context_enabled:
            skipped.append("USER_PROFILE_CONTEXT_INJECTION_DISABLED")
        return UserProfileContextResult(
            context_blocks=context_blocks,
            observed_facets=observed,
            active_facets=active,
            skipped_reasons=tuple(skipped),
        )

    def extract_preview(self, payload: dict[str, Any]) -> dict[str, Any]:
        """画像抽取预览。

        该方法面向 API 诊断：调用方可以用最小 payload 查看会识别出哪些低敏事实。预览会写入 store，
        因为它复用主链路观察逻辑；如果后续需要“纯 dry-run”，可增加显式 `dryRun=true` 策略。
        """

        request = AgentRequest(
            tenant_id=str(payload.get("tenantId") or payload.get("tenant_id") or "preview-tenant"),
            project_id=str(payload.get("projectId") or payload.get("project_id") or "preview-project"),
            actor_id=str(payload.get("actorId") or payload.get("actor_id") or "preview-actor"),
            objective=str(payload.get("objective") or ""),
            variables=dict(payload.get("variables") or {}),
        )
        return self.observe_and_build_context(request).to_summary()

    def activate(self, facet_id: str, *, operator_id: str, reason: str) -> dict[str, Any]:
        """激活画像候选事实。"""

        return self._store.activate(facet_id, operator_id=operator_id, reason=reason).to_summary()

    def reject(self, facet_id: str, *, operator_id: str, reason: str) -> dict[str, Any]:
        """拒绝画像候选事实。"""

        return self._store.reject(facet_id, operator_id=operator_id, reason=reason).to_summary()

    def snapshot(self, request: AgentRequest) -> dict[str, Any]:
        """查询当前请求范围的画像快照。"""

        scope = self.scope_from_request(request)
        facets = self._store.list_facets(scope)
        active = tuple(item for item in facets if item.status == UserProfileFacetStatus.ACTIVE)
        return {
            "scope": {
                "tenantId": scope.tenant_id,
                "projectId": scope.project_id,
                "actorId": scope.actor_id,
                "workspaceKey": scope.workspace_key,
                "profileNamespace": scope.profile_namespace,
            },
            "facetCount": len(facets),
            "activeFacetCount": len(active),
            "facets": tuple(item.to_summary() for item in facets),
            "payloadPolicy": "LOW_SENSITIVE_PROFILE_FACTS_ONLY",
        }

    def diagnostics(self) -> dict[str, Any]:
        """返回用户画像运行时诊断。"""

        return {
            "enabled": self._enabled,
            "contextInjectionEnabled": self._inject_context_enabled,
            "store": self._store.diagnostics(),
            "policy": UserProfileExtractionPolicy().__dict__,
            "safetyBoundary": {
                "rawPromptStored": False,
                "sqlStored": False,
                "toolArgumentsStored": False,
                "modelOutputStored": False,
                "tenantProjectActorScoped": True,
            },
        }

    @staticmethod
    def scope_from_request(request: AgentRequest) -> UserProfileScope:
        """从请求构建画像 scope。

        workspaceKey 优先来自 trustedControlPlane 或 agentWorkspace，因为生产环境应由 gateway/Java 控制面
        注入可信工作区；如果没有工作区，则落到 actor/project 默认画像。
        """

        variables = request.variables
        trusted = variables.get("trustedControlPlane") if isinstance(variables.get("trustedControlPlane"), dict) else {}
        workspace_key = (
            trusted.get("workspaceKey")
            or variables.get("workspaceKey")
            or variables.get("workspace_key")
        )
        workspace_text = str(workspace_key).strip() if workspace_key is not None else None
        namespace = f"profile:{request.tenant_id}:{request.project_id}:{request.actor_id}:{workspace_text or 'default'}"
        return UserProfileScope(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            workspace_key=workspace_text or None,
            profile_namespace=namespace,
        )

    @staticmethod
    def _context_blocks_from_active_facets(
        scope: UserProfileScope,
        active_facets: tuple[UserProfileFacet, ...],
    ) -> tuple[ContextBlock, ...]:
        """把 active 画像事实转换成模型上下文块。

        为了降低 token 消耗和敏感面，所有画像事实会压缩进一个 ContextBlock。内容只包含枚举化偏好和值，
        不包含任何原始用户表达。
        """

        if not active_facets:
            return ()
        lines = [
            "以下是当前用户的低敏画像偏好，仅用于调整回答风格、Agent 自主性和 ETL/数据同步规划倾向：",
        ]
        for facet in active_facets[:20]:
            lines.append(f"- {facet.facet_type.value}.{facet.key} = {facet.value} (confidence={facet.confidence:.2f})")
        content = "\n".join(lines)
        return (
            ContextBlock(
                source_type=ContextSourceType.USER_PROFILE,
                title="用户画像偏好",
                content=content,
                relevance_score=0.88,
                metadata={
                    "profileNamespace": scope.profile_namespace,
                    "facetCount": len(active_facets),
                    "payloadPolicy": "LOW_SENSITIVE_PROFILE_FACTS_ONLY",
                },
                sensitivity_level=ContextSensitivityLevel.INTERNAL,
                source_id=scope.profile_namespace,
                token_estimate=max(24, len(content) // 2),
            ),
        )
