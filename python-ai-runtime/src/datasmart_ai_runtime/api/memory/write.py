"""Agent 记忆写入候选 API 路由注册器。

`api.py` 应保持“应用创建与依赖装配”职责，不适合继续塞入越来越多业务路由细节。
本文件专门注册长期记忆写入候选的查询、审批和拒绝接口，让 API 层与记忆治理服务解耦。

这些接口当前面向 Python Runtime 本地调试和未来 gateway 转发契约。生产化时必须由 Java
gateway 与 permission-admin 保护：
- 查询候选需要 `VIEW_MEMORY_WRITE_CANDIDATES`；
- 审批通过需要 `APPROVE_MEMORY_WRITE`；
- 拒绝候选需要 `REJECT_MEMORY_WRITE`；
- 普通业务用户不应直接访问跨项目、跨租户候选。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.api.memory.write_pagination import paginate_memory_write_candidates
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory.memory_write_governance import (
    AgentMemoryWriteGovernanceService,
    approve_memory_write_candidate,
    reject_memory_write_candidate,
)


def register_memory_write_routes(app: Any, service: AgentMemoryWriteGovernanceService) -> None:
    """注册记忆写入候选治理路由。

    参数说明：
    - `app`：FastAPI 应用实例。这里使用 `Any`，避免核心包默认依赖 FastAPI；
    - `service`：记忆写入治理服务，负责候选查询、审批和拒绝。

    路由设计：
    - `GET /agent/memory/write-candidates`：审批台列表，支持 tenantId/projectId/status/limit；
    - `GET /agent/memory/write-candidates/{candidate_id}`：候选详情；
    - `POST /agent/memory/write-candidates/{candidate_id}/approve`：审批通过；
    - `POST /agent/memory/write-candidates/{candidate_id}/reject`：拒绝写入。

    当前 Python Runtime 不直接做鉴权，是因为项目的统一入口应在 gateway/permission-admin。
    但路由字段已经保留 `operatorId`、`reason`，为后续审计表和审批中心接入打基础。
    """

    from fastapi import HTTPException

    @app.get("/agent/memory/write-candidates")
    def list_memory_write_candidates(
        tenantId: str | None = None,
        projectId: str | None = None,
        status: str | None = None,
        limit: int = 100,
        cursor: str | None = None,
    ) -> dict[str, Any]:
        """查询记忆写入候选列表。

        `tenantId/projectId` 是数据范围过滤条件。未来 gateway 应根据操作者角色把这两个字段
        强制注入或收紧，不能让普通用户自由枚举其他租户和项目。

        分页说明：
        - `limit` 是页大小，当前 API 层限制在 1-100，避免审批台误拉全量数据；
        - `cursor` 来自上一页 `pageInfo.nextCursor`，调用方不需要解析，原样传回即可；
        - 当前先在 API 层用稳定游标切片，后续 MySQL store 会把相同游标下沉为 SQL 条件。
        """

        parsed_status = _parse_status(status)
        safe_limit = _safe_limit(limit)
        try:
            # 当前 store 协议还没有原生 cursor 查询，所以先读取一个受保护的窗口再做 API 层分页。
            # 这里最多取 500 条，是为了兼容已有 store 的安全上限；真实审批台大规模翻页时，
            # 下一阶段应把 cursor 条件下沉到 MySQL，避免 Python Runtime 承担过多列表切片压力。
            candidates = service.list_candidates(
                tenant_id=tenantId,
                project_id=projectId,
                status=parsed_status,
                limit=500,
            )
            page = paginate_memory_write_candidates(candidates, limit=safe_limit, cursor=cursor)
        except ValueError as exc:
            raise _http_error(
                HTTPException,
                status_code=400,
                error_code="MEMORY_WRITE_CURSOR_INVALID",
                message=str(exc),
            ) from exc
        return {
            "candidateCount": len(page.items),
            "candidates": tuple(candidate.to_summary() for candidate in page.items),
            "pageInfo": {
                "limit": safe_limit,
                "hasMore": page.has_more,
                "nextCursor": page.next_cursor,
            },
            "filters": {
                "tenantId": tenantId,
                "projectId": projectId,
                "status": parsed_status.value if parsed_status else None,
                "limit": safe_limit,
                "cursor": cursor,
            },
        }

    @app.get("/agent/memory/write-candidates/{candidate_id}")
    def get_memory_write_candidate(candidate_id: str) -> dict[str, Any]:
        """查询单个记忆写入候选详情。"""

        candidate = service.get(candidate_id)
        if candidate is None:
            raise _http_error(
                HTTPException,
                status_code=404,
                error_code="MEMORY_WRITE_CANDIDATE_NOT_FOUND",
                message=f"记忆写入候选不存在: {candidate_id}",
            )
        return {"candidate": candidate.to_summary()}

    @app.post("/agent/memory/write-candidates/{candidate_id}/approve")
    def approve_candidate(candidate_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        """审批通过记忆写入候选。

        审批通过只表示“允许后续持久化 worker 写入长期记忆”，并不等于当前接口已经写入
        Chroma/Neo4j。这个边界很重要，否则审批 API 会和真实写入副作用混在一起。
        """

        candidate = _decide_candidate(
            service,
            candidate_id=candidate_id,
            payload=payload,
            approve=True,
        )
        return {"candidate": candidate.to_summary()}

    @app.post("/agent/memory/write-candidates/{candidate_id}/reject")
    def reject_candidate(candidate_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        """拒绝记忆写入候选。"""

        candidate = _decide_candidate(
            service,
            candidate_id=candidate_id,
            payload=payload,
            approve=False,
        )
        return {"candidate": candidate.to_summary()}

    def _parse_status(status: str | None) -> AgentMemoryWriteCandidateStatus | None:
        """解析状态查询参数并给出清晰错误。"""

        if status is None or not str(status).strip():
            return None
        try:
            return AgentMemoryWriteCandidateStatus(str(status).strip())
        except ValueError as exc:
            allowed = ", ".join(item.value for item in AgentMemoryWriteCandidateStatus)
            raise _http_error(
                HTTPException,
                status_code=400,
                error_code="MEMORY_WRITE_STATUS_INVALID",
                message=f"不支持的候选状态: {status}，可选值: {allowed}",
            ) from exc

    def _decide_candidate(
        memory_service: AgentMemoryWriteGovernanceService,
        *,
        candidate_id: str,
        payload: dict[str, Any],
        approve: bool,
    ) -> Any:
        """执行审批或拒绝，并把领域异常映射为 HTTP 语义。"""

        operator_id = str(payload.get("operatorId") or payload.get("operator_id") or "").strip()
        reason = str(payload.get("reason") or "").strip()
        if not operator_id:
            raise _http_error(
                HTTPException,
                status_code=400,
                error_code="MEMORY_WRITE_OPERATOR_REQUIRED",
                message="operatorId 必填，用于审计记忆写入审批责任人。",
            )
        try:
            if approve:
                return approve_memory_write_candidate(
                    memory_service,
                    candidate_id=candidate_id,
                    operator_id=operator_id,
                    reason=reason,
                )
            return reject_memory_write_candidate(
                memory_service,
                candidate_id=candidate_id,
                operator_id=operator_id,
                    reason=reason,
                )
        except KeyError as exc:
            raise _http_error(
                HTTPException,
                status_code=404,
                error_code="MEMORY_WRITE_CANDIDATE_NOT_FOUND",
                message=str(exc),
            ) from exc
        except ValueError as exc:
            raise _http_error(
                HTTPException,
                status_code=409,
                error_code="MEMORY_WRITE_DECISION_CONFLICT",
                message=str(exc),
            ) from exc
        except RuntimeError as exc:
            raise _http_error(
                HTTPException,
                status_code=409,
                error_code="MEMORY_WRITE_VERSION_CONFLICT",
                message=str(exc),
            ) from exc

    def _safe_limit(limit: int) -> int:
        """规范化审批台页大小。

        列表 API 的 limit 和底层 store 的 limit 含义不同：
        - API limit 是前端一页展示多少条，控制在 1-100；
        - store limit 是当前临时读取窗口，控制在 500，等待后续 SQL cursor 下沉。
        """

        return max(1, min(limit, 100))

    def _http_error(
        http_exception_type: Any,
        *,
        status_code: int,
        error_code: str,
        message: str,
    ) -> Exception:
        """构造结构化 HTTP 错误。

        早期接口直接返回字符串 detail，前端只能做文本判断。这里改成稳定结构：
        - `errorCode`：机器可读错误码，便于前端、gateway 和日志统计；
        - `message`：中文人读解释，便于学习、联调和运维定位；
        - `statusCode`：保留 HTTP 语义，方便统一错误面板展示。
        """

        return http_exception_type(
            status_code=status_code,
            detail={
                "errorCode": error_code,
                "message": message,
                "statusCode": status_code,
            },
        )
