import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator, build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest


class AgentConversationResponseTest(unittest.TestCase):
    """保护自然语言追问、补参和控制面接入之间的状态边界。"""

    def test_free_text_sync_request_returns_questions_without_creating_java_run(self) -> None:
        ingestion_client = CountingPlanIngestionClient()

        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="101",
                actor_id="1001",
                objective="帮我把 MySQL 的客户表全量同步到 PostgreSQL",
            ),
            build_default_orchestrator(),
            plan_ingestion_client=ingestion_client,
        )

        conversation = response["agentConversation"]
        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual("CREATE_DATA_SYNC_TASK", conversation["structuredIntent"]["intentType"])
        self.assertEqual("FULL", conversation["structuredIntent"]["syncMode"])
        self.assertEqual(
            ["sourceDatasourceId", "targetDatasourceId", "objectMappings"],
            conversation["missingParameters"],
        )
        self.assertEqual(
            ["SOURCE_DATASOURCE_SELECT", "TARGET_DATASOURCE_SELECT", "OBJECT_MAPPING_EDITOR"],
            [item["inputType"] for item in conversation["clarificationQuestions"]],
        )
        self.assertFalse(conversation["canExecute"])
        self.assertFalse(conversation["controlPlaneIngested"])
        self.assertEqual("DETERMINISTIC_FALLBACK", conversation["intentResolver"]["mode"])
        self.assertNotIn("controlPlaneIngestion", response)
        self.assertEqual(0, ingestion_client.call_count)

    def test_clarification_answers_create_confirmable_control_plane_plan(self) -> None:
        ingestion_client = CountingPlanIngestionClient()
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把两张客户表从 MySQL 全量同步到 PostgreSQL public schema",
            variables={
                "dataSyncRequest": {
                    "taskName": "Agent 客户表全量同步",
                    "sourceDatasourceId": 23,
                    "targetDatasourceId": 24,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [
                        {
                            "sourceObjectName": "fs_test_customer_source",
                            "targetSchemaName": "public",
                            "targetObjectName": "fs_test_customer_source",
                        },
                        {
                            "sourceObjectName": "fs_test_customer_target",
                            "targetSchemaName": "public",
                            "targetObjectName": "fs_test_customer_target",
                        },
                    ],
                }
            },
        )

        response = build_plan_response(
            request,
            build_default_orchestrator(),
            plan_ingestion_client=ingestion_client,
        )

        conversation = response["agentConversation"]
        self.assertEqual("READY_FOR_CONFIRMATION", conversation["phase"])
        self.assertEqual([], conversation["missingParameters"])
        self.assertEqual(2, conversation["structuredIntent"]["objectMappingCount"])
        self.assertTrue(conversation["canExecute"])
        self.assertTrue(conversation["controlPlaneIngested"])
        self.assertEqual("CONFIRM_AND_EXECUTE", conversation["nextAction"])
        self.assertEqual(1, ingestion_client.call_count)
        self.assertEqual("session-conversation", response["controlPlaneIngestion"]["sessionId"])
        self.assertNotIn("fs_test_customer_source", str(conversation))


class CountingPlanIngestionClient:
    """只记录真正通过准备度门禁的计划，模拟 Java session/run 引用。"""

    def __init__(self) -> None:
        self.call_count = 0

    def ingest(self, request: AgentRequest, plan: AgentPlan, trace_id: str | None = None):
        self.call_count += 1
        return FakePlanIngestionResult()


class FakePlanIngestionResult:
    def attach_to_plan(self, plan: AgentPlan) -> AgentPlan:
        return plan

    def to_summary(self) -> dict[str, object]:
        return {
            "ingested": True,
            "sessionId": "session-conversation",
            "runId": "run-conversation",
            "toolAuditCount": 9,
        }


if __name__ == "__main__":
    unittest.main()
