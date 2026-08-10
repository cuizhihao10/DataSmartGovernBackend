import json
import os
import sys
import unittest
from unittest.mock import patch

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistToolActivity,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_fact_client import (
    JavaSpecialistTurnFactClient,
    JavaSpecialistTurnFactClientError,
    JavaSpecialistTurnFactClientSettings,
)


class FakeHttpResponse:
    """单元测试用的 urllib response 替身。"""

    def __init__(self, payload: dict[str, object], *, status: int = 200) -> None:
        """保存 Java 平台响应和 HTTP 状态码。"""

        self.status = status
        self._body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    def __enter__(self):
        """支持客户端使用与真实 urlopen 一致的上下文管理器协议。"""

        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        """测试替身不吞掉客户端异常。"""

        return False

    def read(self) -> bytes:
        """返回编码后的 JSON 响应。"""

        return self._body


class RecordingTransport:
    """记录请求的 HTTP transport，可配置成功响应或网络异常。"""

    def __init__(self, response: FakeHttpResponse | None = None, error: Exception | None = None) -> None:
        """初始化 transport 状态。"""

        self.response = response
        self.error = error
        self.requests = []

    def __call__(self, request, timeout: int):
        """记录 Request 与 timeout，然后返回 response 或抛出配置的异常。"""

        self.requests.append((request, timeout))
        if self.error is not None:
            raise self.error
        if self.response is None:
            raise AssertionError("测试 transport 未配置 response")
        return self.response


def _request(application_id: str | None = "10010") -> SpecialistTurnRequest:
    """构造真实 Java DTO 可以接受的数字租户/项目范围请求。"""

    return SpecialistTurnRequest(
        turn_id="turn-001",
        session_id="session-001",
        run_id="run-001",
        role=AgentSessionRole.KNOWLEDGE_AGENT,
        # 客户端故意不读取 objective；这里放入明显标记，防止回归时误序列化。
        objective="不要发送 objective: SELECT secret FROM credentials",
        scope=SpecialistDelegationScope(
            tenant_id="10",
            application_id=application_id,
            project_id="101",
            actor_id="ordinary-user",
            delegation_id="delegation-001",
            allowed_tool_names=("knowledge.rag.query",),
        ),
        # context_summary 同样不应进入 SpecialistTurnFact。
        context_summary={"prompt": "private prompt", "password": "do-not-send"},
        evidence_references=("rag-evidence:case-001",),
    )


def _result() -> SpecialistTurnResult:
    """构造含模型元数据、工具活动和敏感诱饵字段的专业 Agent 结果。"""

    return SpecialistTurnResult(
        agent_id="knowledge-agent-v1",
        role=AgentSessionRole.KNOWLEDGE_AGENT,
        turn_id="turn-001",
        status=SpecialistTurnStatus.COMPLETED,
        public_summary="已基于治理知识库引用完成配置检查。",
        structured_output={
            "prompt": "structured prompt must not be serialized",
            "sql": "SELECT password FROM credentials",
            "credential": "structured-secret",
            "modelOutput": "完整模型回答正文",
        },
        evidence_references=("rag-evidence:case-002",),
        tool_activities=(
            SpecialistToolActivity(
                tool_name="knowledge.rag.query",
                status="COMPLETED",
                # 工具正文也不能被客户端读取或发送。
                public_summary="tool parameter password=do-not-send; model output正文",
                evidence_reference="tool-event:rag-001",
                duration_ms=37,
            ),
        ),
        model_invocation_summary={
            "actualModelName": "luna-Max",
            "modelInvocationId": "provider-call-001",
            "rawPrompt": "raw prompt must not be serialized",
            "rawOutput": "full model output must not be serialized",
        },
        duration_ms=123,
    )


class SpecialistTurnFactClientTest(unittest.TestCase):
    """验证专业 Agent turn 事实客户端的写回、降级和脱敏合同。"""

    def test_success_registers_low_sensitive_fact_with_service_token_header(self) -> None:
        """成功响应应返回结构化 receipt，并使用 Java 约定的内部 token Header。"""

        transport = RecordingTransport(FakeHttpResponse({"code": 0, "data": {"accepted": True, "factId": "fact-1"}}))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(
                enabled=True,
                base_url="http://agent-runtime.test",
                service_token="internal-secret-token",
            ),
            transport=transport,
        )

        receipt = client.register(_request(), _result(), trace_id="trace-001")

        self.assertTrue(receipt.attempted)
        self.assertTrue(receipt.registered)
        self.assertFalse(receipt.skipped)
        self.assertEqual("fact-1", receipt.fact_id)
        self.assertEqual(1, len(transport.requests))
        http_request, timeout = transport.requests[0]
        self.assertEqual(3, timeout)
        self.assertEqual(
            "http://agent-runtime.test/agent-runtime/specialist-turn-facts",
            http_request.full_url,
        )
        self.assertEqual("internal-secret-token", http_request.headers["X-datasmart-internal-service-token"])
        self.assertEqual("python-ai-runtime", http_request.headers["X-datasmart-source-service"])
        self.assertEqual("trace-001", http_request.headers["X-datasmart-trace-id"])
        self.assertEqual("10", http_request.headers["X-datasmart-tenant-id"])
        self.assertEqual("10010", http_request.headers["X-datasmart-application-id"])
        self.assertEqual("101", http_request.headers["X-datasmart-project-id"])
        self.assertEqual("ordinary-user", http_request.headers["X-datasmart-actor-id"])
        self.assertEqual("knowledge-agent-v1", http_request.headers["X-datasmart-agent-id"])
        self.assertEqual("delegation-001", http_request.headers["X-datasmart-agent-delegation-id"])

        payload = json.loads(http_request.data.decode("utf-8"))
        self.assertEqual(10, payload["tenantId"])
        self.assertEqual(10010, payload["applicationId"])
        self.assertEqual(101, payload["projectId"])
        self.assertEqual("ordinary-user", payload["userId"])
        self.assertEqual("delegation-001", payload["delegationId"])
        self.assertEqual("luna-Max", payload["modelName"])
        self.assertEqual("provider-call-001", payload["modelInvocationId"])
        self.assertEqual(["tool-activity:knowledge.rag.query:COMPLETED"], payload["toolActivitySummaryRefs"])
        self.assertIn("tool-event:rag-001", payload["evidenceRefs"])

    def test_application_header_and_body_are_built_from_the_same_trusted_scope_value(self) -> None:
        """应用 Header 与 body 必须来自同一份委派 scope，不能形成跨应用不一致请求。"""

        transport = RecordingTransport(FakeHttpResponse({"code": 0, "data": {"accepted": True}}))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="internal-token"),
            transport=transport,
        )

        client.register(_request("20020"), _result())

        http_request = transport.requests[0][0]
        payload = json.loads(http_request.data.decode("utf-8"))
        self.assertEqual(20020, payload["applicationId"])
        self.assertEqual(str(payload["applicationId"]), http_request.headers["X-datasmart-application-id"])

    def test_missing_application_id_is_rejected_before_http_registration(self) -> None:
        """缺失应用边界时必须 fail-closed，不能省略 Header 后继续请求 Java。"""

        transport = RecordingTransport(error=AssertionError("invalid scope must not call transport"))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="internal-token"),
            transport=transport,
        )

        with self.assertRaises(JavaSpecialistTurnFactClientError) as raised:
            client.register(_request(None), _result())

        self.assertEqual(
            "SPECIALIST_TURN_FACT_SCOPE_APPLICATION_ID_POSITIVE_INTEGER_REQUIRED",
            raised.exception.code,
        )
        self.assertEqual([], transport.requests)

    def test_non_numeric_application_id_is_rejected_before_http_registration(self) -> None:
        """应用名称不是 applicationId；客户端不能从名称、正文或 projectId 猜测数字 ID。"""

        transport = RecordingTransport(error=AssertionError("invalid scope must not call transport"))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="internal-token"),
            transport=transport,
        )

        with self.assertRaises(JavaSpecialistTurnFactClientError) as raised:
            client.register(_request("datasmart-govern"), _result())

        self.assertEqual(
            "SPECIALIST_TURN_FACT_SCOPE_APPLICATION_ID_POSITIVE_INTEGER_REQUIRED",
            raised.exception.code,
        )
        self.assertEqual([], transport.requests)

    def test_default_urllib_transport_passes_timeout_as_keyword(self) -> None:
        """默认标准库 transport 必须把 timeout 传给 timeout 参数，而不能当成 data。

        可注入 transport 的历史合同仍然使用 ``(request, timeout)`` 两个位置参数；但真实
        ``urllib.request.urlopen`` 的第二个位置参数是请求体。这个回归用例直接走客户端默认
        transport，并检查标准库收到的是关键字 timeout，防止生产环境在事实登记阶段出现
        “memoryview 需要 bytes”这类与模型无关、却会阻断 fail-closed 多 Agent 闭环的错误。
        """

        fake_response = FakeHttpResponse({"code": 0, "data": {"accepted": True}})
        with patch(
            "datasmart_ai_runtime.services.multi_agent.specialist_fact_client.urlopen",
            return_value=fake_response,
        ) as mocked_urlopen:
            client = JavaSpecialistTurnFactClient(
                JavaSpecialistTurnFactClientSettings(enabled=True, service_token="internal-token"),
            )

            receipt = client.register(_request(), _result())

        self.assertTrue(receipt.registered)
        mocked_urlopen.assert_called_once()
        self.assertEqual(3, mocked_urlopen.call_args.kwargs["timeout"])
        self.assertNotIn("data", mocked_urlopen.call_args.kwargs)

    def test_disabled_is_explicitly_skipped_without_transport_call(self) -> None:
        """enabled=False 必须明确 skipped，而不能返回假成功。"""

        transport = RecordingTransport(error=AssertionError("disabled client must not call transport"))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=False, service_token="unused"),
            transport=transport,
        )

        receipt = client.register(_request(), _result())

        self.assertFalse(receipt.attempted)
        self.assertFalse(receipt.registered)
        self.assertTrue(receipt.skipped)
        self.assertIsNone(receipt.error_code)
        self.assertEqual([], transport.requests)
        self.assertIn("跳过", receipt.message)

    def test_fail_open_returns_low_sensitive_failure_receipt(self) -> None:
        """fail_closed=False 时网络失败应返回机器码，不阻断调用方流程。"""

        transport = RecordingTransport(error=OSError("password=secret prompt=private SQL"))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="token", fail_closed=False),
            transport=transport,
        )

        receipt = client.register(_request(), _result())

        self.assertFalse(receipt.registered)
        self.assertFalse(receipt.skipped)
        self.assertEqual("SPECIALIST_TURN_FACT_HTTP_POST_FAILED", receipt.error_code)
        self.assertNotIn("password", receipt.message.lower())
        self.assertNotIn("secret", receipt.message.lower())

    def test_fail_closed_raises_low_sensitive_error(self) -> None:
        """fail_closed=True 时网络失败必须抛出稳定错误码，避免事实链路静默丢失。"""

        transport = RecordingTransport(error=OSError("internal endpoint and secret must stay hidden"))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="token", fail_closed=True),
            transport=transport,
        )

        with self.assertRaises(JavaSpecialistTurnFactClientError) as raised:
            client.register(_request(), _result())

        self.assertEqual("SPECIALIST_TURN_FACT_HTTP_POST_FAILED", raised.exception.code)
        self.assertNotIn("secret", str(raised.exception).lower())

    def test_payload_never_contains_forbidden_content_or_structured_output(self) -> None:
        """objective、prompt、SQL、工具正文、structuredOutput、凭据和模型原文都不能进入 body。"""

        transport = RecordingTransport(FakeHttpResponse({"code": 0, "data": {"registered": True}}))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="credential-token"),
            transport=transport,
        )

        client.register(_request(), _result())
        body = transport.requests[0][0].data.decode("utf-8")

        forbidden_fragments = (
            "不要发送 objective",
            "private prompt",
            "SELECT password",
            "structured-secret",
            "tool parameter",
            "完整模型回答正文",
            "raw prompt",
            "raw output",
            "credential-token",
            "do-not-send",
        )
        for fragment in forbidden_fragments:
            self.assertNotIn(fragment, body)
        self.assertNotIn("structuredOutput", body)
        self.assertIn("lowSensitiveSummary", body)

    def test_client_instance_can_be_used_directly_as_result_sink(self) -> None:
        """客户端实例应能直接作为 coordinator 的二参数 result sink，并保留结构化 receipt。"""

        transport = RecordingTransport(FakeHttpResponse({"code": 0, "data": {"accepted": True}}))
        client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(enabled=True, service_token="internal-token"),
            transport=transport,
        )

        receipt = client(_request(), _result())

        self.assertTrue(receipt.registered)
        self.assertEqual(1, len(transport.requests))


if __name__ == "__main__":
    unittest.main()
