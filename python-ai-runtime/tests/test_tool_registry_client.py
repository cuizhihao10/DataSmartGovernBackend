import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolRiskLevel
from datasmart_ai_runtime.services.tool_registry_client import (
    JavaAgentToolRegistryClient,
    ToolRegistryClientError,
)


class JavaAgentToolRegistryClientTest(unittest.TestCase):
    def test_parse_java_platform_response_to_tool_definition(self) -> None:
        payload = {
            "code": 0,
            "reason": "SUCCESS",
            "data": [
                {
                    "toolCode": "datasource.metadata.read",
                    "enabled": True,
                    "toolType": "DATASOURCE_METADATA",
                    "displayName": "读取数据源元数据",
                    "description": "读取指定数据源元数据",
                    "targetService": "datasource-management",
                    "targetEndpoint": "/datasources/{datasourceId}/metadata/discover",
                    "readOnly": True,
                    "riskLevel": "LOW",
                    "executionMode": "SYNC",
                    "requiresApproval": False,
                    "idempotent": True,
                    "timeoutMs": 3000,
                    "maxRetries": 1,
                    "allowedActions": ["VIEW"],
                    "inputSchema": [
                        {
                            "name": "datasourceId",
                            "type": "string",
                            "required": True,
                            "description": "数据源 ID",
                            "example": "ds-001",
                        }
                    ],
                }
            ],
        }

        tools = JavaAgentToolRegistryClient.parse_platform_response(payload)

        self.assertEqual(1, len(tools))
        tool = tools[0]
        self.assertEqual("datasource.metadata.read", tool.name)
        self.assertEqual(ToolRiskLevel.LOW, tool.risk_level)
        self.assertEqual(ToolExecutionMode.SYNC, tool.execution_mode)
        self.assertTrue(tool.read_only)
        self.assertTrue(tool.idempotent)
        self.assertEqual(("VIEW",), tool.allowed_actions)
        self.assertEqual("string", tool.input_schema["datasourceId"]["type"])

    def test_parse_java_descriptor_response_to_tool_definition(self) -> None:
        payload = {
            "code": 0,
            "reason": "SUCCESS",
            "data": [
                {
                    "schemaVersion": "datasmart.agent.tool.v1",
                    "descriptorType": "DATASMART_AGENT_TOOL",
                    "protocolHint": "MCP_STYLE",
                    "toolCode": "datasource.metadata.read",
                    "displayName": "读取数据源元数据",
                    "description": "读取指定数据源元数据",
                    "toolType": "DATASOURCE_METADATA",
                    "invocation": {
                        "targetService": "datasource-management",
                        "targetEndpoint": "/datasources/{datasourceId}/metadata/discover",
                        "executionMode": "SYNC",
                        "idempotent": True,
                        "timeoutMs": 3000,
                        "maxRetries": 1,
                    },
                    "governance": {
                        "enabled": True,
                        "readOnly": True,
                        "riskLevel": "LOW",
                        "requiresApproval": False,
                        "tenantScoped": True,
                        "projectScoped": True,
                        "allowedActions": ["VIEW"],
                        "sensitiveFields": ["datasourceId"],
                    },
                    "memory": {
                        "memoryWritePolicy": "SEMANTIC",
                        "cachePolicy": "PROJECT_SAFE",
                    },
                    "parameters": [
                        {
                            "name": "datasourceId",
                            "type": "number",
                            "required": True,
                            "sensitive": True,
                            "resolution": "CAN_FILL_FROM_CONTEXT",
                            "description": "数据源 ID",
                            "example": "1001",
                        }
                    ],
                }
            ],
        }

        tools = JavaAgentToolRegistryClient.parse_descriptor_platform_response(payload)

        self.assertEqual(1, len(tools))
        tool = tools[0]
        self.assertEqual("datasmart.agent.tool.v1", tool.schema_version)
        self.assertEqual("MCP_STYLE", tool.protocol_hint)
        self.assertEqual("DATASOURCE_METADATA", tool.tool_type)
        self.assertTrue(tool.tenant_scoped)
        self.assertTrue(tool.project_scoped)
        self.assertEqual(("datasourceId",), tool.sensitive_fields)
        self.assertEqual("semantic", tool.memory_write_policy)
        self.assertEqual("project_safe", tool.cache_policy)
        self.assertTrue(tool.input_schema["datasourceId"]["sensitive"])
        self.assertEqual("can_fill_from_context", tool.input_schema["datasourceId"]["resolution"])

    def test_non_success_response_raises_clear_error(self) -> None:
        with self.assertRaises(ToolRegistryClientError):
            JavaAgentToolRegistryClient.parse_platform_response(
                {"code": 403, "reason": "FORBIDDEN", "message": "权限不足", "data": None}
            )


if __name__ == "__main__":
    unittest.main()
