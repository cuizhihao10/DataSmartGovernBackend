import os
import sys
import types
import unittest
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_memory_write import register_memory_write_routes
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory_write_candidate_store import InMemoryAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory_write_governance import AgentMemoryWriteGovernanceService


class AgentMemoryWriteApiPaginationTest(unittest.TestCase):
    """记忆写入候选 API 分页与错误语义测试。

    该测试单独成文件，是为了避免继续膨胀已有治理服务测试文件。
    它不依赖真实 FastAPI 包，而是用最小 fake app 捕获路由函数，验证 API 契约本身。
    """

    def test_list_route_returns_cursor_page_info_and_next_page(self) -> None:
        """候选列表应返回稳定 cursor，支持审批台继续向后翻页。"""

        service = self._service_with_candidates()
        app = FakeFastApiApp()
        with fake_fastapi_module():
            register_memory_write_routes(app, service)

        first_page = app.call("GET", "/agent/memory/write-candidates", tenantId="tenant-a", limit=2)
        second_page = app.call(
            "GET",
            "/agent/memory/write-candidates",
            tenantId="tenant-a",
            limit=2,
            cursor=first_page["pageInfo"]["nextCursor"],
        )

        self.assertEqual(2, first_page["candidateCount"])
        self.assertTrue(first_page["pageInfo"]["hasMore"])
        self.assertIsNotNone(first_page["pageInfo"]["nextCursor"])
        self.assertEqual(("candidate-3", "candidate-2"), tuple(item["candidateId"] for item in first_page["candidates"]))
        self.assertEqual(1, second_page["candidateCount"])
        self.assertFalse(second_page["pageInfo"]["hasMore"])
        self.assertEqual(("candidate-1",), tuple(item["candidateId"] for item in second_page["candidates"]))

    def test_invalid_cursor_returns_structured_error_detail(self) -> None:
        """非法 cursor 应返回机器可读错误码，而不是只能靠中文字符串判断。"""

        app = FakeFastApiApp()
        with fake_fastapi_module():
            register_memory_write_routes(app, self._service_with_candidates())

        with self.assertRaises(FakeHttpException) as raised:
            app.call("GET", "/agent/memory/write-candidates", cursor="not-a-valid-cursor")

        self.assertEqual(400, raised.exception.status_code)
        self.assertEqual("MEMORY_WRITE_CURSOR_INVALID", raised.exception.detail["errorCode"])

    def test_missing_candidate_returns_structured_not_found_error(self) -> None:
        """候选不存在时应返回稳定错误码，便于前端展示和 gateway 统计。"""

        app = FakeFastApiApp()
        with fake_fastapi_module():
            register_memory_write_routes(app, self._service_with_candidates())

        with self.assertRaises(FakeHttpException) as raised:
            app.call("GET", "/agent/memory/write-candidates/{candidate_id}", "missing-candidate")

        self.assertEqual(404, raised.exception.status_code)
        self.assertEqual("MEMORY_WRITE_CANDIDATE_NOT_FOUND", raised.exception.detail["errorCode"])

    @staticmethod
    def _service_with_candidates() -> AgentMemoryWriteGovernanceService:
        """构造带三条候选的治理服务。

        三条候选使用不同 `created_at`，验证列表按 `createdAt DESC, candidateId DESC` 翻页。
        """

        store = InMemoryAgentMemoryWriteCandidateStore()
        for index in range(1, 4):
            store.save(
                AgentMemoryWriteCandidate(
                    candidate_id=f"candidate-{index}",
                    memory_type=AgentMemoryType.EPISODIC,
                    scope=AgentMemoryScope.PROJECT,
                    status=AgentMemoryWriteCandidateStatus.DRAFT,
                    tenant_id="tenant-a",
                    project_id="project-a",
                    actor_id="analyst-a",
                    title=f"候选 {index}",
                    content_summary=f"第 {index} 条候选摘要",
                    source="unit-test",
                    created_at=datetime(2026, 5, 28, 12, index, tzinfo=timezone.utc),
                )
            )
        return AgentMemoryWriteGovernanceService(store=store)


class FakeHttpException(Exception):
    """测试用 HTTPException，模拟 FastAPI 的 `status_code/detail` 字段。"""

    def __init__(self, status_code: int, detail) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


class fake_fastapi_module:
    """临时注入 fake fastapi，避免默认单元测试依赖可选 API 包。"""

    def __enter__(self) -> None:
        self._old = sys.modules.get("fastapi")
        module = types.ModuleType("fastapi")
        module.HTTPException = FakeHttpException
        sys.modules["fastapi"] = module

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._old is None:
            sys.modules.pop("fastapi", None)
        else:
            sys.modules["fastapi"] = self._old


class FakeFastApiApp:
    """捕获 `@app.get/post` 注册结果的最小测试桩。"""

    def __init__(self) -> None:
        self.routes: dict[tuple[str, str], object] = {}

    def get(self, path: str):
        def decorator(func):
            self.routes[("GET", path)] = func
            return func

        return decorator

    def post(self, path: str):
        def decorator(func):
            self.routes[("POST", path)] = func
            return func

        return decorator

    def call(self, method: str, path: str, *args, **kwargs):
        return self.routes[(method, path)](*args, **kwargs)


if __name__ == "__main__":
    unittest.main()
