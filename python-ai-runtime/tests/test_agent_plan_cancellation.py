import os
import sys
import threading
import unittest


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelMessage,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.services.model_gateway.agent_plan_cancellation import (
    AgentPlanCancellationIdentity,
    AgentPlanCancellationRegistry,
    AgentPlanCancelled,
    bind_agent_plan_cancellation,
)
from datasmart_ai_runtime.services.model_gateway.openai_compatible_provider import (
    OpenAICompatibleModelProvider,
    OpenAICompatibleProviderSettings,
)


class AgentPlanCancellationTest(unittest.TestCase):
    """保护停止模型思考的身份边界和真实传输中断语义。"""

    def test_registry_requires_full_tenant_project_actor_identity(self) -> None:
        registry = AgentPlanCancellationRegistry()
        owner = _identity(actor_id="1001")
        token = registry.register(owner)

        other_actor_result = registry.cancel(_identity(actor_id="1002"))

        self.assertEqual("NOT_FOUND", other_actor_result["state"])
        self.assertFalse(other_actor_result["cancelled"])
        self.assertFalse(token.cancelled)

        owner_result = registry.cancel(owner)

        self.assertEqual("CANCELLED", owner_result["state"])
        self.assertTrue(owner_result["cancelled"])
        with self.assertRaises(AgentPlanCancelled):
            token.raise_if_cancelled()

    def test_cancelling_stream_closes_active_provider_response(self) -> None:
        registry = AgentPlanCancellationRegistry()
        identity = _identity()
        token = registry.register(identity)
        response = BlockingSseResponse()
        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=lambda request, timeout: response,
        )
        raised: list[BaseException] = []

        def consume_stream() -> None:
            try:
                with bind_agent_plan_cancellation(token):
                    tuple(provider.stream(_model_request()))
            except BaseException as exc:  # 测试线程需要把异常传回主线程断言。
                raised.append(exc)

        worker = threading.Thread(target=consume_stream, daemon=True)
        worker.start()
        self.assertTrue(response.read_started.wait(timeout=1.0))

        registry.cancel(identity)
        worker.join(timeout=1.0)

        self.assertFalse(worker.is_alive())
        self.assertTrue(response.closed)
        self.assertEqual(1, len(raised))
        self.assertIsInstance(raised[0], AgentPlanCancelled)


class BlockingSseResponse:
    """模拟阻塞在 SSE 下一行读取处的 urllib response。"""

    def __init__(self) -> None:
        self.read_started = threading.Event()
        self._released = threading.Event()
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def __iter__(self):
        return self

    def __next__(self) -> bytes:
        self.read_started.set()
        self._released.wait(timeout=2.0)
        if self.closed:
            return b"data: [DONE]\n"
        raise StopIteration

    def close(self) -> None:
        self.closed = True
        self._released.set()


def _identity(*, actor_id: str = "1001") -> AgentPlanCancellationIdentity:
    return AgentPlanCancellationIdentity(
        tenant_id="10",
        project_id="101",
        actor_id=actor_id,
        request_id="request-cancel-001",
    )


def _model_request() -> ModelInvocationRequest:
    return ModelInvocationRequest(
        route=ModelRoute(
            workload=WorkloadType.AGENT_REASONING,
            provider_name="cancel-test-provider",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            model_name="cancel-test-model",
            endpoint="http://model-gateway.local/v1",
            timeout_seconds=30,
        ),
        messages=(ModelMessage(role="user", content="测试停止模型思考"),),
    )


if __name__ == "__main__":
    unittest.main()
