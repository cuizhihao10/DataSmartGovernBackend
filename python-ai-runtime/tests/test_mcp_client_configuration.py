"""出站 MCP Client 配置与安全边界测试。"""

import json
import os
import sys
import tempfile
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools.mcp import (
    McpClientError,
    McpClientRuntimeSettings,
    mcp_client_runtime_settings_from_env,
    mcp_configuration_diagnostics,
    mcp_server_configurations_from_env,
    validate_mcp_server_configuration,
)


class McpClientConfigurationTest(unittest.TestCase):
    """验证 MCP 默认关闭、HTTP SSRF 防护和 stdio 子进程白名单。"""

    def test_default_runtime_is_disabled_and_does_not_enable_stdio(self) -> None:
        """零配置环境不能自动联网或启动外部进程。"""

        settings = mcp_client_runtime_settings_from_env({})

        self.assertFalse(settings.enabled)
        self.assertFalse(settings.stdio_enabled)
        self.assertFalse(settings.discovery_on_startup)
        self.assertTrue(settings.fail_open)

    def test_streamable_http_requires_tls_and_host_allowlist(self) -> None:
        """生产远程连接必须使用 HTTPS，并且 host 必须命中显式 allowlist。"""

        configuration = self._server(
            {
                "serverId": "enterprise-search",
                "enabled": True,
                "transport": "streamable-http",
                "endpoint": "https://mcp.example.internal/mcp",
                "allowedHosts": ["mcp.example.internal"],
            }
        )

        validate_mcp_server_configuration(configuration, McpClientRuntimeSettings(enabled=True))

        insecure = self._server(
            {
                "serverId": "unsafe",
                "enabled": True,
                "transport": "http",
                "endpoint": "http://mcp.example.internal/mcp",
                "allowedHosts": ["mcp.example.internal"],
            }
        )
        with self.assertRaisesRegex(McpClientError, "HTTPS"):
            validate_mcp_server_configuration(insecure, McpClientRuntimeSettings(enabled=True))

    def test_endpoint_rejects_query_credentials_and_unlisted_host(self) -> None:
        """endpoint query 可能携带 token，重定向 host 也可能形成 SSRF，因此都应在连接前拒绝。"""

        query_endpoint = self._server(
            {
                "serverId": "query-secret",
                "enabled": True,
                "endpoint": "https://mcp.example.internal/mcp?token=secret",
                "allowedHosts": ["mcp.example.internal"],
            }
        )
        wrong_host = self._server(
            {
                "serverId": "wrong-host",
                "enabled": True,
                "endpoint": "https://attacker.example/mcp",
                "allowedHosts": ["mcp.example.internal"],
            }
        )

        with self.assertRaises(McpClientError) as query_error:
            validate_mcp_server_configuration(query_endpoint, McpClientRuntimeSettings(enabled=True))
        with self.assertRaises(McpClientError) as host_error:
            validate_mcp_server_configuration(wrong_host, McpClientRuntimeSettings(enabled=True))

        self.assertEqual("MCP_HTTP_ENDPOINT_SENSITIVE_COMPONENT", query_error.exception.code)
        self.assertEqual("MCP_HTTP_HOST_NOT_ALLOWED", host_error.exception.code)

    def test_stdio_requires_global_enable_command_and_workspace_allowlists(self) -> None:
        """stdio 会启动真实子进程，必须同时满足总开关、命令白名单和 cwd 根目录白名单。"""

        with tempfile.TemporaryDirectory() as workspace:
            configuration = self._server(
                {
                    "serverId": "local-tool",
                    "enabled": True,
                    "transport": "stdio",
                    "command": "python.exe",
                    "args": ["server.py"],
                    "cwd": workspace,
                }
            )
            with self.assertRaises(McpClientError) as disabled:
                validate_mcp_server_configuration(
                    configuration,
                    McpClientRuntimeSettings(enabled=True),
                )
            self.assertEqual("MCP_STDIO_DISABLED", disabled.exception.code)

            validate_mcp_server_configuration(
                configuration,
                McpClientRuntimeSettings(
                    enabled=True,
                    stdio_enabled=True,
                    stdio_allowed_commands=("python.exe",),
                    stdio_allowed_roots=(workspace,),
                ),
            )

    def test_diagnostics_never_return_endpoint_or_token_value(self) -> None:
        """诊断只能显示 token 是否已注入，不能显示地址或凭据。"""

        configuration = self._server(
            {
                "serverId": "secure-server",
                "displayName": "企业 MCP",
                "enabled": True,
                "endpoint": "https://mcp.example.internal/mcp",
                "allowedHosts": ["mcp.example.internal"],
                "authTokenEnv": "DATASMART_TEST_MCP_TOKEN",
            }
        )
        previous = os.environ.get("DATASMART_TEST_MCP_TOKEN")
        os.environ["DATASMART_TEST_MCP_TOKEN"] = "super-secret-token"
        try:
            diagnostics = mcp_configuration_diagnostics(
                McpClientRuntimeSettings(enabled=True),
                (configuration,),
            )
        finally:
            if previous is None:
                os.environ.pop("DATASMART_TEST_MCP_TOKEN", None)
            else:
                os.environ["DATASMART_TEST_MCP_TOKEN"] = previous

        serialized = json.dumps(diagnostics, ensure_ascii=False)
        self.assertNotIn("super-secret-token", serialized)
        self.assertNotIn("mcp.example.internal", serialized)
        self.assertTrue(diagnostics["servers"][0]["authTokenConfigured"])

    @staticmethod
    def _server(payload: dict):
        configurations = mcp_server_configurations_from_env(
            {"DATASMART_AI_MCP_SERVERS_JSON": json.dumps([payload])}
        )
        return configurations[0]


if __name__ == "__main__":
    unittest.main()
