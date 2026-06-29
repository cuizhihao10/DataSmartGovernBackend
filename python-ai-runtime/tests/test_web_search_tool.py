import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.tools.web_search_tool import (
    WEB_SEARCH_TOOL_PAYLOAD_POLICY,
    WebSearchGovernancePolicy,
    WebSearchGovernanceService,
)


class WebSearchGovernanceServiceTest(unittest.TestCase):
    """受控网页搜索治理服务测试。

    测试重点不是“能否真的搜索网页”，而是保护商业化安全边界：
    - ToolPlan 和事件只能携带 query digest 与策略摘要，不能携带原始查询；
    - 查询里出现密钥、内网地址、SQL 或敏感业务词时，要给出稳定风险码；
    - Provider、缓存、限流和引用策略要结构化，后续接真实搜索 Provider 时不需要推翻契约。
    """

    def test_prepare_returns_low_sensitive_query_reference_and_policies(self) -> None:
        """普通搜索请求应转为低敏引用和可执行前治理策略。"""

        query = "Qwen3.7 Agent tool calling latest release"
        decision = WebSearchGovernanceService(
            WebSearchGovernancePolicy(
                provider_allowlist=("enterprise-search", "searxng"),
                public_web_allowed=False,
                max_results=3,
                cache_ttl_seconds=120,
            )
        ).prepare(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请联网搜索资料",
                variables={"searchQuery": query, "sessionId": "session-a"},
            )
        )

        arguments = decision.to_tool_arguments()
        serialized = str(arguments)

        self.assertEqual(WEB_SEARCH_TOOL_PAYLOAD_POLICY, arguments["payloadPolicy"])
        self.assertEqual("low", decision.sensitivity_level)
        self.assertEqual(64, len(decision.query_digest))
        self.assertEqual(2, arguments["providerPolicy"]["providerAllowlistCount"])
        self.assertFalse(arguments["networkPolicy"]["publicWebAllowed"])
        self.assertEqual(120, arguments["cachePolicy"]["ttlSeconds"])
        self.assertEqual(3, arguments["resultPolicy"]["maxResults"])
        self.assertTrue(arguments["resultPolicy"]["citationRequired"])
        self.assertNotIn(query, serialized)

    def test_sensitive_query_generates_issue_codes_without_echoing_query(self) -> None:
        """敏感查询应生成风险码，但不能把原始正文回显给计划或事件。"""

        query = "search api_key=abc123 http://127.0.0.1/admin select * from customer where phone='13800000000'"
        decision = WebSearchGovernanceService().prepare(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请联网搜索",
                variables={"searchQuery": query},
            )
        )

        serialized = str(decision.to_tool_arguments()).lower()

        self.assertEqual("high", decision.sensitivity_level)
        self.assertIn("QUERY_CONTAINS_SECRET_MARKER", decision.issue_codes)
        self.assertIn("QUERY_MENTIONS_INTERNAL_ENDPOINT", decision.issue_codes)
        self.assertIn("QUERY_CONTAINS_SQL_MARKER", decision.issue_codes)
        self.assertTrue(decision.high_risk)
        self.assertNotIn("api_key=abc123", serialized)
        self.assertNotIn("127.0.0.1", serialized)
        self.assertNotIn("select * from", serialized)
        self.assertNotIn("13800000000", serialized)


if __name__ == "__main__":
    unittest.main()
