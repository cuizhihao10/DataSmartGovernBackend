"""Tests for the model-selected workspace text-search worker boundary."""

import json
import os
import secrets
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.workspace_text_search_worker import (
    WORKSPACE_TEXT_SEARCH_WORKER_API_SCHEMA_VERSION,
    register_workspace_text_search_worker_routes,
    workspace_text_search_worker_request_from_payload,
)
from datasmart_ai_runtime.services.tools.workspace_text_search_tool import (
    WorkspaceTextSearchService,
    WorkspaceTextSearchSettings,
)
from datasmart_ai_runtime.services.tools.workspace_text_search_worker import (
    WORKSPACE_TEXT_SEARCH_TOOL_CODE,
    WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY,
    WorkspaceTextSearchCommandWorker,
    workspace_text_search_settings_from_env,
)


class WorkspaceTextSearchWorkerApiTest(unittest.TestCase):
    """Verify parsing, execution, redaction and fail-closed configuration."""

    def test_request_parser_separates_control_facts_from_model_arguments(self) -> None:
        """Trusted identity/root facts and model query fields must remain distinct."""

        request = workspace_text_search_worker_request_from_payload(
            {
                "arguments": {
                    "query": "NeedleSymbol",
                    "relativePathPrefix": "src",
                    "caseSensitive": False,
                    "maxResults": 7,
                    "repositoryRoot": "D:/model-must-not-control-root",
                },
                "controlFacts": {
                    "commandId": "command-text-001",
                    "sessionId": "session-text-001",
                    "runId": "run-text-001",
                    "repositoryRoot": "C:/controlled/root",
                    "repositoryReference": "agent-repository:tenant-10/application-40/project-20",
                    "tenantId": "10",
                    "applicationId": "40",
                    "projectId": "20",
                },
            }
        )

        self.assertEqual("command-text-001", request.command_id)
        self.assertEqual("C:/controlled/root", request.repository_root)
        self.assertEqual("agent-repository:tenant-10/application-40/project-20", request.repository_reference)
        self.assertEqual("40", request.application_id)
        self.assertEqual("NeedleSymbol", request.query)
        self.assertEqual("src", request.relative_path_prefix)
        self.assertFalse(request.case_sensitive)
        self.assertEqual(7, request.max_results)

    def test_internal_route_executes_search_and_keeps_java_receipt_low_sensitive(self) -> None:
        """The worker may return bounded matches to the model but never root/query fields to Java."""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "src").mkdir()
            Path(workspace, "src", "example.py").write_text(
                "class NeedleSymbol:\n    pass\n",
                encoding="utf-8",
            )
            service_token = secrets.token_urlsafe(24)
            app = FakeApp()
            register_workspace_text_search_worker_routes(
                app,
                request_type=FakeHttpRequest,
                worker=self._worker(workspace),
                service_account_token=service_token,
                error_factory=FakeHttpError,
            )

            response = app.post_routes["/internal/agent/workspace-text/command-worker/run"](
                self._payload(workspace, query="NeedleSymbol"),
                FakeHttpRequest({"Authorization": f"Bearer {service_token}"}),
            )
            java_serialized = json.dumps(response["javaReceiptPayload"], ensure_ascii=False)
            full_serialized = json.dumps(response, ensure_ascii=False)

        self.assertEqual(WORKSPACE_TEXT_SEARCH_WORKER_API_SCHEMA_VERSION, response["schemaVersion"])
        self.assertEqual(WORKSPACE_TEXT_SEARCH_TOOL_CODE, response["toolCode"])
        self.assertEqual(WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY, response["payloadPolicy"])
        self.assertEqual("SUCCEEDED", response["workerResult"]["status"])
        self.assertEqual(1, response["workerResult"]["matchCount"])
        self.assertEqual("src/example.py", response["workerResult"]["matches"][0]["relativePath"])
        self.assertEqual("WORKSPACE_TEXT_SEARCH_COMPLETED", response["javaReceiptPayload"]["outcome"])
        self.assertEqual(WORKSPACE_TEXT_SEARCH_TOOL_CODE, response["javaReceiptPayload"]["toolCode"])
        self.assertFalse(response["javaReceiptPayload"]["sideEffectStarted"])
        self.assertFalse(response["javaReceiptPayload"]["sideEffectExecuted"])
        self.assertNotIn(workspace, full_serialized)
        self.assertNotIn("NeedleSymbol", java_serialized)
        self.assertNotIn("repositoryRoot", java_serialized)
        self.assertNotIn("query", java_serialized.lower())

    def test_unapproved_root_is_blocked_without_reading_file_content(self) -> None:
        """A root outside the immutable allowlist must fail closed."""

        with tempfile.TemporaryDirectory() as allowed, tempfile.TemporaryDirectory() as outside:
            marker = "OUTSIDE_CONTENT_MUST_NOT_BE_READ"
            Path(outside, "secret.txt").write_text(marker, encoding="utf-8")
            service_token = secrets.token_urlsafe(24)
            app = FakeApp()
            register_workspace_text_search_worker_routes(
                app,
                request_type=FakeHttpRequest,
                worker=self._worker(allowed),
                service_account_token=service_token,
                error_factory=FakeHttpError,
            )
            payload = self._payload(outside, query=marker)

            response = app.post_routes["/internal/agent/workspace-text/command-worker/run"](
                payload,
                FakeHttpRequest({"Authorization": f"Bearer {service_token}"}),
            )
            serialized = json.dumps(response, ensure_ascii=False)

        self.assertEqual("BLOCKED", response["workerResult"]["status"])
        self.assertEqual("WORKSPACE_TEXT_SEARCH_BLOCKED", response["javaReceiptPayload"]["outcome"])
        self.assertEqual(0, response["workerResult"]["matchCount"])
        self.assertNotIn(outside, serialized)
        self.assertNotIn(marker, serialized)

    def test_environment_settings_require_both_feature_flag_and_allowlist(self) -> None:
        """Enabling the flag alone must not grant filesystem access."""

        values = {"DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_ENABLED": "true"}
        disabled = workspace_text_search_settings_from_env(lambda name, default=None: values.get(name, default))
        self.assertFalse(disabled.enabled)
        self.assertEqual((), disabled.workspace_root_allowlist)

        values["DATASMART_AGENT_REPOSITORY_ROOT_ALLOWLIST"] = "/repositories/backend,/repositories/frontend"
        enabled = workspace_text_search_settings_from_env(lambda name, default=None: values.get(name, default))
        self.assertTrue(enabled.enabled)
        self.assertEqual(("/repositories/backend", "/repositories/frontend"), enabled.workspace_root_allowlist)

    def test_compose_wires_the_authenticated_read_only_worker_without_broad_host_exposure(self) -> None:
        """Rendered source contract must keep Java/Python token, roots and dispatcher scope aligned."""

        repository_root = Path(__file__).resolve().parents[2]
        compose = (repository_root / "docker-compose.application.yml").read_text(encoding="utf-8")
        env_example = (repository_root / ".env.application.example").read_text(encoding="utf-8")

        self.assertIn('DATASMART_AGENT_RUNTIME_ASYNC_COMMAND_DISPATCHER_ENABLED: "true"', compose)
        self.assertIn(
            "DATASMART_AGENT_RUNTIME_ASYNC_COMMAND_DISPATCHER_ALLOWED_TOOL_CODES: workspace.text.search",
            compose,
        )
        self.assertIn("DATASMART_AGENT_RUNTIME_WORKSPACE_TEXT_SEARCH_WORKER_REPOSITORY_ROOT: /repositories/backend", compose)
        self.assertIn("DATASMART_AGENT_REPOSITORY_ROOT_ALLOWLIST: /repositories/backend,/repositories/frontend", compose)
        token_lines = [
            line
            for line in compose.splitlines()
            if line.strip().startswith("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_SERVICE_TOKEN:")
        ]
        self.assertEqual(2, len(token_lines))
        self.assertIn("- .:/repositories/backend:ro", compose)
        self.assertIn("- ../DataSmartGovernFrontend:/repositories/frontend:ro", compose)
        self.assertIn('- "127.0.0.1:8090:8090"', compose)
        self.assertNotIn('- "8090:8090"', compose)
        self.assertIn("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_SERVICE_TOKEN=", env_example)

    def test_internal_route_rejects_missing_or_wrong_bearer_service_token(self) -> None:
        """The internal route must reject absent, malformed, and mismatched credentials with 401.

        The credential is generated in memory instead of being committed as a test fixture.  This exercises the
        actual HTTP boundary while keeping the repository, response payload, and test diagnostics free of secrets.
        """

        try:
            from fastapi import FastAPI, HTTPException, Request
            from fastapi.testclient import TestClient
        except ImportError:
            self.skipTest("FastAPI API extras are not installed")

        with tempfile.TemporaryDirectory() as workspace:
            service_token = secrets.token_urlsafe(24)
            wrong_token = secrets.token_urlsafe(24)
            app = FastAPI()
            register_workspace_text_search_worker_routes(
                app,
                request_type=Request,
                worker=self._worker(workspace),
                service_account_token=service_token,
                error_factory=lambda status_code, detail: HTTPException(
                    status_code=status_code,
                    detail=detail,
                ),
            )
            client = TestClient(app)

            for headers in (
                {},
                {"Authorization": service_token},
                {"Authorization": "Token malformed"},
                {"Authorization": f"Bearer {wrong_token}"},
            ):
                response = client.post(
                    "/internal/agent/workspace-text/command-worker/run",
                    headers=headers,
                    json=self._payload(workspace, query="NeedleSymbol"),
                )

                self.assertEqual(401, response.status_code, response.text)
                self.assertEqual(
                    "WORKSPACE_TEXT_SEARCH_WORKER_UNAUTHORIZED",
                    response.json()["detail"]["code"],
                )
                self.assertNotIn(service_token, response.text)
                self.assertNotIn(wrong_token, response.text)

    def test_internal_route_fails_closed_without_configured_service_token(self) -> None:
        """A deployment missing its expected token must not silently make the worker public."""

        try:
            from fastapi import FastAPI, HTTPException, Request
            from fastapi.testclient import TestClient
        except ImportError:
            self.skipTest("FastAPI API extras are not installed")

        with tempfile.TemporaryDirectory() as workspace:
            presented_token = secrets.token_urlsafe(24)
            app = FastAPI()
            register_workspace_text_search_worker_routes(
                app,
                request_type=Request,
                worker=self._worker(workspace),
                service_account_token=None,
                error_factory=lambda status_code, detail: HTTPException(
                    status_code=status_code,
                    detail=detail,
                ),
            )
            response = TestClient(app).post(
                "/internal/agent/workspace-text/command-worker/run",
                headers={"Authorization": f"Bearer {presented_token}"},
                json=self._payload(workspace, query="NeedleSymbol"),
            )

        self.assertEqual(401, response.status_code, response.text)
        self.assertEqual(
            "WORKSPACE_TEXT_SEARCH_WORKER_UNAUTHORIZED",
            response.json()["detail"]["code"],
        )
        self.assertNotIn(presented_token, response.text)

    def test_internal_route_accepts_the_configured_bearer_service_token(self) -> None:
        """Only the configured Bearer credential may reach the bounded filesystem worker."""

        try:
            from fastapi import FastAPI, HTTPException, Request
            from fastapi.testclient import TestClient
        except ImportError:
            self.skipTest("FastAPI API extras are not installed")

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "example.py").write_text("NeedleSymbol = object()\n", encoding="utf-8")
            service_token = secrets.token_urlsafe(24)
            app = FastAPI()
            register_workspace_text_search_worker_routes(
                app,
                request_type=Request,
                worker=self._worker(workspace),
                service_account_token=service_token,
                error_factory=lambda status_code, detail: HTTPException(
                    status_code=status_code,
                    detail=detail,
                ),
            )
            response = TestClient(app).post(
                "/internal/agent/workspace-text/command-worker/run",
                headers={"Authorization": f"Bearer {service_token}"},
                json=self._payload(workspace, query="NeedleSymbol"),
            )

        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("SUCCEEDED", response.json()["workerResult"]["status"])
        self.assertNotIn(service_token, response.text)

    @staticmethod
    def _worker(workspace: str) -> WorkspaceTextSearchCommandWorker:
        """Build a search worker with a single temporary allow-listed root."""

        return WorkspaceTextSearchCommandWorker(
            WorkspaceTextSearchService(
                WorkspaceTextSearchSettings(
                    enabled=True,
                    workspace_root_allowlist=(workspace,),
                    max_files=20,
                    max_file_bytes=4096,
                    max_total_scan_bytes=8192,
                    max_matches=10,
                )
            )
        )

    @staticmethod
    def _payload(workspace: str, *, query: str) -> dict:
        """Create the Java-style command payload used by route tests."""

        return {
            "arguments": {
                "query": query,
                "relativePathPrefix": ".",
            },
            "controlFacts": {
                "commandId": "command-text-002",
                "sessionId": "session-text-002",
                "runId": "run-text-002",
                "repositoryRoot": workspace,
                "repositoryReference": "agent-repository:tenant-10/application-40/project-20",
                "tenantId": "10",
                "applicationId": "40",
                "projectId": "20",
                "actorId": "30",
            },
        }


class FakeApp:
    """Minimal FastAPI decorator substitute used by route-level unit tests."""

    def __init__(self) -> None:
        self.post_routes = {}

    def post(self, path):
        """Record a POST handler under its route path."""

        def decorator(handler):
            self.post_routes[path] = handler
            return handler

        return decorator


class FakeHttpRequest:
    """Minimal Request substitute so direct route tests use the same Authorization input as FastAPI."""

    def __init__(self, headers: dict[str, str]) -> None:
        self.headers = headers


class FakeHttpError(Exception):
    """Small HTTPException-shaped error used when a direct unit test unexpectedly fails authorization."""

    def __init__(self, status_code: int, detail: dict) -> None:
        super().__init__(detail.get("code", "WORKSPACE_TEXT_SEARCH_WORKER_UNAUTHORIZED"))
        self.status_code = status_code
        self.detail = detail


if __name__ == "__main__":
    unittest.main()
