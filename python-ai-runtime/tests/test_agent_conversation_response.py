import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator, build_plan_response
from datasmart_ai_runtime.api.agent.conversation_response import (
    build_agent_conversation_response,
    build_intent_resolver_summary,
)
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelRoute,
    ProviderType,
    WorkloadType,
    ToolParameterIssue,
    ToolParameterIssueAction,
    ToolParameterValidationResult,
    ToolPlan,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.tools import ToolExecutionReadinessService


class AgentConversationResponseTest(unittest.TestCase):
    """保护自然语言追问、补参和控制面接入之间的状态边界。"""

    def test_english_incomplete_sync_request_returns_actionable_questions(self) -> None:
        ingestion_client = CountingPlanIngestionClient()

        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="101",
                actor_id="1001",
                objective="Create a full data synchronization task for me.",
            ),
            build_default_orchestrator(),
            plan_ingestion_client=ingestion_client,
        )

        conversation = response["agentConversation"]
        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(
            ["sourceDatasourceId", "targetDatasourceId", "objectMappings"],
            conversation["missingParameters"],
        )
        self.assertEqual(3, len(conversation["clarificationQuestions"]))
        self.assertFalse(conversation["canExecute"])
        self.assertEqual(0, ingestion_client.call_count)

    def test_free_text_connector_types_start_read_only_candidate_resolution(self) -> None:
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
        self.assertEqual("RESOLVING_AUTONOMOUSLY", conversation["phase"])
        self.assertEqual("CREATE_DATA_SYNC_TASK", conversation["structuredIntent"]["intentType"])
        self.assertEqual("FULL", conversation["structuredIntent"]["syncMode"])
        self.assertEqual([], conversation["missingParameters"])
        self.assertEqual([], conversation["clarificationQuestions"])
        self.assertFalse(conversation["canExecute"])
        self.assertTrue(conversation["controlPlaneIngested"])
        self.assertEqual("DETERMINISTIC_FALLBACK", conversation["intentResolver"]["mode"])
        self.assertIn("controlPlaneIngestion", response)
        self.assertEqual(1, ingestion_client.call_count)

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
                            "fieldMappings": [{
                                "sourceField": "id",
                                "targetField": "id",
                                "syncEnabled": True,
                            }],
                        },
                        {
                            "sourceObjectName": "fs_test_customer_target",
                            "targetSchemaName": "public",
                            "targetObjectName": "fs_test_customer_target",
                            "fieldMappings": [{
                                "sourceField": "id",
                                "targetField": "id",
                                "syncEnabled": True,
                            }],
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
        self.assertEqual(
            "fs_test_customer_source",
            conversation["resolvedConfiguration"]["objectMappings"][0]["sourceObjectName"],
        )
        self.assertEqual(
            "AUTHORIZED_PROJECT_METADATA_NO_CREDENTIALS",
            conversation["resolvedConfiguration"]["payloadPolicy"],
        )

    def test_model_catalog_lookup_is_reported_as_autonomous_resolution_not_full_form(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective=(
                "创建任务 customer-full，把 mysql-prod.customer 全量同步到 "
                "pg-warehouse.public.customer"
            ),
        )
        tool_plans = (
            ToolPlan(
                tool_name="datasource.source.catalog.search",
                reason="resolve explicit source name",
                arguments={"keyword": "mysql-prod"},
            ),
            ToolPlan(
                tool_name="datasource.target.catalog.search",
                reason="resolve explicit target name",
                arguments={"keyword": "pg-warehouse"},
            ),
        )
        plan = AgentPlan(
            request_id="request-autonomous-resolution",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="正在解析授权数据源。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=(
                    "datasource.source.catalog.search",
                    "datasource.target.catalog.search",
                ),
                missing_parameters=(
                    "sourceDatasourceId",
                    "targetDatasourceId",
                    "objectMappings",
                ),
            ),
        )
        readiness = ToolExecutionReadinessService().evaluate(tool_plans)

        conversation = build_agent_conversation_response(
            request,
            plan,
            readiness,
            control_plane_ingested=True,
        )

        self.assertEqual("RESOLVING_AUTONOMOUSLY", conversation["phase"])
        self.assertEqual([], conversation["missingParameters"])
        self.assertEqual([], conversation["clarificationQuestions"])
        self.assertEqual("CONTINUE_AUTONOMOUSLY", conversation["nextAction"])
        self.assertFalse(conversation["canExecute"])

    def test_selected_datasources_with_incomplete_draft_still_ask_for_object_mappings(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective=(
                "Synchronize fs_test_customer_source and fs_test_customer_target "
                "from MySQL to PostgreSQL public schema."
            ),
            variables={
                "dataSyncRequest": {
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                }
            },
        )
        tool_plans = (
            ToolPlan(
                tool_name="datasource.source.connection.test",
                reason="verify selected source",
                arguments={"datasourceId": 27},
            ),
            ToolPlan(
                tool_name="datasource.target.connection.test",
                reason="verify selected target",
                arguments={"datasourceId": 28},
            ),
            ToolPlan(
                tool_name="datasource.source.metadata.read",
                reason="read selected source metadata",
                arguments={"datasourceId": 27},
            ),
            ToolPlan(
                tool_name="datasource.target.metadata.read",
                reason="read selected target metadata",
                arguments={"datasourceId": 28},
            ),
            ToolPlan(
                tool_name="sync.task.draft.save",
                reason="save task after required mapping is supplied",
                arguments={
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "objectMappings": [],
                },
                parameter_validation=ToolParameterValidationResult(
                    can_execute=False,
                    can_create_draft=False,
                    issues=(
                        ToolParameterIssue(
                            parameter_name="objectMappings",
                            expected_type="array",
                            action=ToolParameterIssueAction.MUST_CLARIFY,
                            message="Object mappings are required before saving the draft.",
                        ),
                    ),
                ),
            ),
        )
        plan = AgentPlan(
            request_id="request-selected-datasources-missing-mapping",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="Selected datasource connections can be tested, but mappings are missing.",
            intent_analysis=IntentAnalysis(
                summary="Data sync with selected datasource instances.",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=tuple(item.tool_name for item in tool_plans),
                missing_parameters=("objectMappings",),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(["objectMappings"], conversation["missingParameters"])
        self.assertEqual(1, len(conversation["clarificationQuestions"]))
        self.assertEqual(
            "OBJECT_MAPPING_EDITOR",
            conversation["clarificationQuestions"][0]["inputType"],
        )
        self.assertFalse(conversation["canExecute"])

    def test_ambiguous_datasource_only_asks_user_to_choose_from_authorized_candidates(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把 mysql-orders 的订单表同步到 pg-warehouse",
        )
        tool_plans = (
            ToolPlan(
                tool_name="datasource.source.catalog.search",
                reason="resolve source",
                arguments={"keyword": "mysql-orders"},
            ),
            ToolPlan(
                tool_name="datasource.target.catalog.search",
                reason="resolve target",
                arguments={"keyword": "pg-warehouse"},
            ),
        )
        plan = AgentPlan(
            request_id="request-ambiguous-source",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="正在解析授权数据源。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=(
                    "datasource.source.catalog.search",
                    "datasource.target.catalog.search",
                ),
                missing_parameters=(
                    "sourceDatasourceId",
                    "targetDatasourceId",
                    "objectMappings",
                ),
            ),
        )
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=2,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source",
                    tool_name="datasource.source.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="源端存在多个候选。",
                    result={
                        "matchStatus": "AMBIGUOUS",
                        "candidates": [
                            {
                                "datasourceId": 27,
                                "name": "mysql-orders-source",
                                "type": "MYSQL",
                                "usagePurpose": "SOURCE",
                            },
                            {
                                "datasourceId": 29,
                                "name": "mysql-orders-backup",
                                "type": "MYSQL",
                                "usagePurpose": "SOURCE",
                            },
                        ],
                    },
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target",
                    tool_name="datasource.target.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="目标端唯一匹配。",
                    result={
                        "matchStatus": "EXACT",
                        "resolvedDatasourceId": 28,
                    },
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 2},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            control_plane_feedback=feedback,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(["sourceDatasourceId"], conversation["missingParameters"])
        self.assertFalse(conversation["canExecute"])
        self.assertEqual(
            [27, 29],
            [
                item["datasourceId"]
                for item in conversation["clarificationQuestions"][0]["candidates"]
            ],
        )
        self.assertEqual(
            28,
            conversation["resolvedConfiguration"]["targetDatasourceId"],
        )

    def test_follow_up_exact_datasources_and_metadata_return_autofill_configuration(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective=(
                "将 MySQL 中的 fs_test_customer_source 和 fs_test_customer_target "
                "全量同步到 PostgreSQL public schema 的同名表。"
            ),
            variables={
                "latestUserMessage": (
                    "源数据源为 mysql2pgsql_test_0709_source，"
                    "目标数据源为 pgsql2mysql_test_0709_target。"
                ),
            },
        )
        tool_plans = (
            ToolPlan(tool_name="datasource.source.metadata.read", reason="read source metadata"),
            ToolPlan(tool_name="datasource.target.metadata.read", reason="read target metadata"),
        )
        plan = AgentPlan(
            request_id="request-resolved-configuration",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="已读取两端真实元数据并找到唯一同名表。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=tuple(item.tool_name for item in tool_plans),
                missing_parameters=(
                    "sourceDatasourceId",
                    "targetDatasourceId",
                    "objectMappings",
                ),
            ),
        )
        source_objects = [
            {
                "schemaName": None,
                "tableName": table_name,
                "columns": [
                    {"columnName": "id", "dataTypeName": "BIGINT", "primaryKey": True},
                    {"columnName": "name", "dataTypeName": "VARCHAR"},
                ],
            }
            for table_name in ("fs_test_customer_source", "fs_test_customer_target")
        ]
        target_objects = [
            {
                "schemaName": "public",
                "tableName": table_name,
                "columns": [
                    {"columnName": "id", "dataTypeName": "BIGINT", "primaryKey": True},
                    {"columnName": "name", "dataTypeName": "VARCHAR"},
                ],
            }
            for table_name in ("fs_test_customer_source", "fs_test_customer_target")
        ]
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=4,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source-catalog",
                    tool_name="datasource.source.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="源端唯一匹配。",
                    result={
                        "matchStatus": "EXACT",
                        "resolvedDatasourceId": 27,
                        "resolvedDatasourceName": "mysql2pgsql_test_0709_source",
                    },
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-catalog",
                    tool_name="datasource.target.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="目标端唯一匹配。",
                    result={
                        "matchStatus": "EXACT",
                        "resolvedDatasourceId": 28,
                        "resolvedDatasourceName": "pgsql2mysql_test_0709_target",
                    },
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source-metadata",
                    tool_name="datasource.source.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="源端元数据读取成功。",
                    result={"summary": {"objects": source_objects}},
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-metadata",
                    tool_name="datasource.target.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="目标端元数据读取成功。",
                    result={"summary": {"objects": target_objects}},
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 4},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            control_plane_feedback=feedback,
            autonomous_resolution_stopped=True,
        )

        resolved = conversation["resolvedConfiguration"]
        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(
            ["mappingDefaultsConfirmation"],
            conversation["missingParameters"],
        )
        self.assertFalse(conversation["canExecute"])
        self.assertEqual(
            "MAPPING_DEFAULTS_CONFIRMATION",
            conversation["clarificationQuestions"][0]["inputType"],
        )
        self.assertEqual(27, resolved["sourceDatasourceId"])
        self.assertEqual(28, resolved["targetDatasourceId"])
        self.assertEqual("mysql2pgsql_test_0709_source", resolved["sourceDatasourceName"])
        self.assertEqual("pgsql2mysql_test_0709_target", resolved["targetDatasourceName"])
        self.assertEqual("VERIFIED_METADATA_SAME_NAME_MATCH", resolved["objectMappingSource"])
        self.assertEqual(2, len(resolved["objectMappings"]))
        self.assertEqual(
            ["fs_test_customer_source", "fs_test_customer_target"],
            [item["sourceObjectName"] for item in resolved["objectMappings"]],
        )
        self.assertTrue(all(
            item["targetSchemaName"] == "public"
            and len(item["fieldMappings"]) == 2
            and item["whereCondition"] == ""
            for item in resolved["objectMappings"]
        ))
        self.assertFalse(resolved["mappingDefaultsConfirmed"])
        self.assertEqual(
            "VERIFIED_METADATA_SAME_NAME_FIELDS",
            resolved["fieldMappingSource"],
        )

    def test_user_stated_same_name_tables_are_preserved_before_metadata_feedback(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "将 MySQL 中的 fs_test_customer_source 和 fs_test_customer_target "
                "全量同步到 PostgreSQL public schema 的同名表。"
            ),
            variables={
                "latestUserMessage": (
                    "源数据源是 mysql2pgsql_test_0709_source，目标数据源是 "
                    "mysql2pgsql_test_0709_target；将 fs_test_customer_source、"
                    "fs_test_customer_target 分别映射到 public schema 下的同名表。"
                ),
            },
        )
        tool_plans = (
            ToolPlan(tool_name="datasource.source.catalog.search", reason="resolve source"),
            ToolPlan(tool_name="datasource.target.catalog.search", reason="resolve target"),
        )
        plan = AgentPlan(
            request_id="request-user-stated-mappings",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="正在解析两端数据源。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=tuple(item.tool_name for item in tool_plans),
                missing_parameters=(
                    "sourceDatasourceId",
                    "targetDatasourceId",
                    "objectMappings",
                ),
            ),
        )
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=2,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source-catalog",
                    tool_name="datasource.source.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="源端唯一匹配。",
                    result={
                        "matchStatus": "EXACT",
                        "resolvedDatasourceId": 27,
                        "resolvedDatasourceName": "mysql2pgsql_test_0709_source",
                    },
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-catalog",
                    tool_name="datasource.target.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="目标端唯一匹配。",
                    result={
                        "matchStatus": "EXACT",
                        "resolvedDatasourceId": 28,
                        "resolvedDatasourceName": "mysql2pgsql_test_0709_target",
                    },
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 2},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            control_plane_feedback=feedback,
        )

        resolved = conversation["resolvedConfiguration"]
        self.assertEqual("RESOLVING_AUTONOMOUSLY", conversation["phase"])
        self.assertEqual([], conversation["missingParameters"])
        self.assertEqual(27, resolved["sourceDatasourceId"])
        self.assertEqual(28, resolved["targetDatasourceId"])
        self.assertEqual("USER_STATED_SAME_NAME_MAPPING", resolved["objectMappingSource"])
        self.assertEqual(
            [
                ("fs_test_customer_source", "public", "fs_test_customer_source"),
                ("fs_test_customer_target", "public", "fs_test_customer_target"),
            ],
            [
                (
                    item["sourceObjectName"],
                    item["targetSchemaName"],
                    item["targetObjectName"],
                )
                for item in resolved["objectMappings"]
            ],
        )

    def test_model_proposed_fields_still_require_user_default_confirmation(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把 customer 全量同步到 public.customer，同名字段默认映射。",
            variables={
                "dataSyncRequest": {
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "syncMode": "FULL",
                },
            },
        )
        draft = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="model proposed metadata-backed defaults",
            arguments={
                "sourceDatasourceId": 27,
                "targetDatasourceId": 28,
                "syncMode": "FULL",
                "objectMappings": [{
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "customer",
                    "fieldMappings": [{
                        "sourceField": "id",
                        "targetField": "id",
                        "syncEnabled": True,
                    }],
                }],
            },
            requires_human_approval=True,
        )
        plan = AgentPlan(
            request_id="request-model-field-defaults",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=(draft,),
            requires_human_approval=True,
            response_summary="已依据真实元数据补齐同名字段。",
            intent_analysis=IntentAnalysis(
                summary="识别到全量同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("sync.task.draft.save",),
                missing_parameters=(),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate((draft,)),
            control_plane_ingested=True,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(
            ["mappingDefaultsConfirmation"],
            conversation["missingParameters"],
        )
        self.assertEqual(
            "MODEL_PROPOSED_METADATA_FIELDS",
            conversation["resolvedConfiguration"]["fieldMappingSource"],
        )
        self.assertFalse(conversation["canExecute"])

    def test_follow_up_correction_overrides_only_explicitly_changed_fields(self) -> None:
        previous_mappings = [
            {
                "sourceObjectName": "fs_test_customer_source",
                "targetSchemaName": "public",
                "targetObjectName": "fs_test_customer_source",
                "fieldMappings": [{
                    "sourceField": "id",
                    "targetField": "id",
                    "syncEnabled": True,
                }],
            },
            {
                "sourceObjectName": "fs_test_customer_target",
                "targetSchemaName": "public",
                "targetObjectName": "fs_test_customer_target",
                "fieldMappings": [{
                    "sourceField": "id",
                    "targetField": "id",
                    "syncEnabled": True,
                }],
            },
        ]
        corrected_mappings = [
            {
                **previous_mappings[0],
                "whereCondition": "status = 1",
            },
            previous_mappings[1],
        ]
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="创建客户双表全量同步任务。",
            variables={
                "conversationMode": "CLARIFICATION_OR_CORRECTION",
                "latestUserMessage": (
                    "任务名称改为客户双表全量同步，并给 fs_test_customer_source "
                    "增加 WHERE 条件：status = 1。"
                ),
                "dataSyncRequest": {
                    "taskName": "Agent 创建的数据同步任务",
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": previous_mappings,
                },
            },
        )
        draft = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="apply the user's incremental correction",
            arguments={
                "taskName": "客户双表全量同步",
                "sourceDatasourceId": 27,
                "targetDatasourceId": 28,
                "syncMode": "FULL",
                "writeStrategy": "INSERT",
                "objectMappings": corrected_mappings,
            },
            requires_human_approval=True,
        )
        plan = AgentPlan(
            request_id="request-follow-up-correction",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=(draft,),
            requires_human_approval=True,
            response_summary="已应用任务名称和 WHERE 条件修改。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务纠偏。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("sync.task.draft.save",),
                missing_parameters=(),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate((draft,)),
            control_plane_ingested=True,
        )

        resolved = conversation["resolvedConfiguration"]
        self.assertEqual("READY_FOR_CONFIRMATION", conversation["phase"])
        self.assertEqual("客户双表全量同步", resolved["taskName"])
        self.assertEqual("status = 1", resolved["objectMappings"][0]["whereCondition"])
        self.assertEqual(
            "fs_test_customer_target",
            resolved["objectMappings"][1]["sourceObjectName"],
        )
        self.assertEqual(27, resolved["sourceDatasourceId"])
        self.assertEqual(28, resolved["targetDatasourceId"])
        self.assertIn(
            "已按你的要求将任务名称从“Agent 创建的数据同步任务”修改为“客户双表全量同步”",
            conversation["assistantMessage"],
        )

    def test_complete_request_with_late_catalog_ambiguity_asks_for_datasource_not_mapping(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective=(
                "Create a FULL task from E2E MySQL source to pg-warehouse and map "
                "customer to public.customer."
            ),
        )
        tool_plans = (
            ToolPlan(tool_name="datasource.source.catalog.search", reason="resolve source"),
            ToolPlan(tool_name="datasource.target.metadata.read", reason="read exact target"),
        )
        plan = AgentPlan(
            request_id="request-late-ambiguous-source",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="源数据源名称存在多个候选，请用户明确选择。",
            intent_analysis=IntentAnalysis(
                summary="识别到完整数据同步描述。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("sync.task.draft.save",),
                # 规则基线不会把自然语言中的名称和映射伪装成可信 ID/
                # 结构化配置；真实工具反馈会逐项消解这些占位缺参。
                missing_parameters=(
                    "sourceDatasourceId",
                    "targetDatasourceId",
                    "objectMappings",
                ),
            ),
        )
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=3,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source",
                    tool_name="datasource.source.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="源端存在多个候选。",
                    result={
                        "matchStatus": "AMBIGUOUS",
                        "candidates": [
                            {
                                "datasourceId": 17,
                                "name": "E2E MySQL source 1",
                                "type": "MYSQL",
                                "usagePurpose": "SOURCE",
                            },
                            {
                                "datasourceId": 19,
                                "name": "E2E MySQL source 2",
                                "type": "MYSQL",
                                "usagePurpose": "SOURCE",
                            },
                        ],
                    },
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-catalog",
                    tool_name="datasource.target.catalog.search",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="目标端唯一匹配。",
                    result={"matchStatus": "EXACT", "resolvedDatasourceId": 28},
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-metadata",
                    tool_name="datasource.target.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="目标元数据读取成功。",
                    result={"summary": {"objects": []}},
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 3},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            control_plane_feedback=feedback,
            autonomous_resolution_stopped=True,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(["sourceDatasourceId"], conversation["missingParameters"])
        self.assertEqual(2, len(conversation["clarificationQuestions"][0]["candidates"]))

    def test_failed_source_connection_returns_exact_datasource_recovery_question(self) -> None:
        """A terminal probe failure must become a usable human continuation point."""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把客户表从 MySQL 全量同步到 PostgreSQL",
            variables={
                "dataSyncRequest": {
                    "sourceDatasourceId": 23,
                    "targetDatasourceId": 24,
                    "syncMode": "FULL",
                }
            },
        )
        tool_plan = ToolPlan(
            tool_name="datasource.source.connection.test",
            reason="verify source",
            arguments={"datasourceId": 23},
        )
        plan = AgentPlan(
            request_id="request-source-connection-failed",
            selected_route=None,
            state_trace=("execute_tools",),
            tool_plans=(tool_plan,),
            requires_human_approval=False,
            response_summary="源端连接测试失败：当前数据源不可访问，请重新选择或修复连接。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("datasource.source.connection.test",),
            ),
        )
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-source-connection",
                tool_name="datasource.source.connection.test",
                status=ToolExecutionFeedbackStatus.FAILED,
                summary="源端连接测试失败。",
                error_code="DATASOURCE_CONNECTION_TEST_FAILED",
                error_message="连接超时",
            ),),
            missing_tool_call_ids=(),
            status_counts={"failed": 1},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate((tool_plan,)),
            control_plane_ingested=True,
            control_plane_feedback=feedback,
            autonomous_resolution_stopped=True,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(["sourceDatasourceId"], conversation["missingParameters"])
        self.assertIn("连接测试失败", conversation["assistantMessage"])

    def test_connector_type_only_clarification_explains_that_instance_is_still_required(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="将 MySQL 的客户表全量同步到 PostgreSQL public schema 的同名表",
        )
        tool_plans = (
            ToolPlan(
                tool_name="datasource.source.catalog.search",
                reason="filter source by connector type",
                arguments={"datasourceType": "MYSQL"},
            ),
        )
        plan = AgentPlan(
            request_id="request-type-candidates",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="已按数据库类型筛选授权数据源。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("datasource.source.catalog.search",),
                missing_parameters=("sourceDatasourceId",),
            ),
        )
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-source-type",
                tool_name="datasource.source.catalog.search",
                status=ToolExecutionFeedbackStatus.SUCCEEDED,
                summary="按 MYSQL 类型返回源端候选。",
                result={
                    "matchStatus": "TYPE_CANDIDATES",
                    "matchBasis": "CONNECTOR_TYPE_ONLY",
                    "requestedDatasourceType": "MYSQL",
                    "candidates": [{
                        "datasourceId": 23,
                        "name": "customer-production-source",
                        "type": "MYSQL",
                        "usagePurpose": "SOURCE",
                    }],
                },
            ),),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 1},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            control_plane_feedback=feedback,
        )

        question = conversation["clarificationQuestions"][0]
        self.assertEqual(
            "DATASOURCE_CONNECTOR_TYPE_REQUIRES_INSTANCE_SELECTION",
            question["reasonCode"],
        )
        self.assertEqual("MYSQL", question["requestedDatasourceType"])
        self.assertIn("数据库类型 MYSQL", question["question"])
        self.assertIn("自然语言补充/纠正", question["question"])
        self.assertEqual(23, question["candidates"][0]["datasourceId"])
        self.assertFalse(conversation["canExecute"])

    def test_metadata_resolution_stop_only_returns_object_mapping_to_user(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="同步客户数据，但没有说明表之间如何对应",
        )
        tool_plans = (
            ToolPlan(
                tool_name="datasource.source.metadata.read",
                reason="read source metadata",
                arguments={"datasourceId": 27},
            ),
            ToolPlan(
                tool_name="datasource.target.metadata.read",
                reason="read target metadata",
                arguments={"datasourceId": 28},
            ),
        )
        plan = AgentPlan(
            request_id="request-mapping-clarification",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="元数据已读取，但映射不唯一。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=tuple(item.tool_name for item in tool_plans),
                missing_parameters=(
                    "sourceDatasourceId",
                    "targetDatasourceId",
                    "objectMappings",
                ),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            autonomous_resolution_stopped=True,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(["objectMappings"], conversation["missingParameters"])
        self.assertFalse(conversation["canExecute"])
        self.assertIn("映射不唯一", conversation["assistantMessage"])
        self.assertEqual(
            "OBJECT_MAPPING_EDITOR",
            conversation["clarificationQuestions"][0]["inputType"],
        )

    def test_invalid_metadata_with_no_declared_missing_fields_requires_mapping_repair(self) -> None:
        """完整描述中的错误字段不能被误判成已生成可执行草稿。"""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective=(
                "创建全量任务，把 mysql-source.customer 同步到 "
                "pg-target.public.customer，并映射 customer_code 字段"
            ),
        )
        tool_plans = (
            ToolPlan(
                tool_name="datasource.source.metadata.read",
                reason="read source metadata",
                arguments={"connectionTestRef": {"toolCode": "datasource.source.connection.test"}},
            ),
            ToolPlan(
                tool_name="datasource.target.metadata.read",
                reason="read target metadata",
                arguments={"connectionTestRef": {"toolCode": "datasource.target.connection.test"}},
            ),
        )
        repair_summary = (
            "源表和目标表都不存在 customer_code 字段。请选择：忽略该字段、改为 phone，"
            "或先在四张表中新增 customer_code 后重新读取元数据。"
        )
        plan = AgentPlan(
            request_id="request-invalid-field-repair",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary=repair_summary,
            intent_analysis=IntentAnalysis(
                summary="识别到完整但字段配置错误的数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=tuple(item.tool_name for item in tool_plans),
                missing_parameters=(),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(tool_plans),
            control_plane_ingested=True,
            autonomous_resolution_stopped=True,
        )

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual("ANSWER_CLARIFICATIONS", conversation["nextAction"])
        self.assertEqual(["objectMappings"], conversation["missingParameters"])
        self.assertFalse(conversation["canExecute"])
        self.assertIn(repair_summary, conversation["assistantMessage"])
        question = conversation["clarificationQuestions"][0]
        self.assertEqual("OBJECT_MAPPING_EDITOR", question["inputType"])
        self.assertEqual("MODEL_CONFIGURATION_REPAIR_REQUIRED", question["reasonCode"])
        self.assertEqual(repair_summary, question["repairGuidance"])

    def test_legacy_real_time_name_is_reported_as_cdc_streaming(self) -> None:
        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="101",
                actor_id="1001",
                objective="实时同步客户表",
                variables={
                    "dataSyncRequest": {
                        "sourceDatasourceId": 23,
                        "targetDatasourceId": 24,
                        "syncMode": "REAL_TIME",
                        "writeStrategy": "INSERT",
                        "objectMappings": [{
                            "sourceObjectName": "customer",
                            "targetObjectName": "customer",
                        }],
                    }
                },
            ),
            build_default_orchestrator(),
            plan_ingestion_client=CountingPlanIngestionClient(),
        )

        intent = response["agentConversation"]["structuredIntent"]

        self.assertEqual("CDC_STREAMING", intent["syncMode"])
        self.assertEqual("UPDATE", intent["writeStrategy"])

    def test_scheduled_mode_adds_frequency_and_start_time_questions(self) -> None:
        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="101",
                actor_id="1001",
                objective="创建一个定期全量任务，把客户表每天同步到 PostgreSQL",
            ),
            build_default_orchestrator(),
        )

        conversation = response["agentConversation"]

        self.assertEqual("WAITING_CLARIFICATION", conversation["phase"])
        self.assertEqual(
            ["sourceDatasourceId", "scheduleFrequency", "scheduleStartTime"],
            conversation["missingParameters"],
        )
        questions = {item["parameterName"]: item for item in conversation["clarificationQuestions"]}
        self.assertEqual("SCHEDULE_FREQUENCY_SELECT", questions["scheduleFrequency"]["inputType"])
        self.assertEqual("DATETIME", questions["scheduleStartTime"]["inputType"])

    def test_agent_generated_sql_is_returned_as_authorized_confirmation_preview(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="创建 SQL 语句同步任务",
            variables={"dataSyncRequest": {"syncMode": "CUSTOM_SQL_QUERY"}},
        )
        draft = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="save generated SQL draft",
            arguments={
                "syncMode": "CUSTOM_SQL_QUERY",
                "customSqlText": "SELECT id, name AS customer_name FROM customer",
                "objectMappings": [{"targetSchemaName": "public", "targetObjectName": "customer"}],
            },
        )
        plan = AgentPlan(
            request_id="request-generated-sql",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=(draft,),
            requires_human_approval=True,
            response_summary="已基于真实元数据生成只读 SQL。",
            intent_analysis=IntentAnalysis(
                summary="识别到 SQL 同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("sync.task.draft.save",),
                missing_parameters=("sourceDatasourceId", "targetDatasourceId", "objectMappings"),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate((draft,)),
            control_plane_ingested=False,
        )

        self.assertEqual(["customSqlConfirmation"], conversation["missingParameters"])
        question = conversation["clarificationQuestions"][0]
        self.assertEqual("SQL_CONFIRMATION", question["inputType"])
        self.assertTrue(question["sensitive"])
        self.assertEqual(
            "SELECT id, name AS customer_name FROM customer",
            question["configurationPreview"]["customSqlText"],
        )
        self.assertTrue(question["configurationPreview"]["generatedByAgent"])

    def test_missing_target_table_returns_create_or_select_decision(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把客户源表全量同步到目标端 public.customer_archive",
        )
        metadata_plans = (
            ToolPlan(tool_name="datasource.source.metadata.read", reason="read source metadata"),
            ToolPlan(tool_name="datasource.target.metadata.read", reason="read target metadata"),
        )
        plan = AgentPlan(
            request_id="request-target-table-missing",
            selected_route=None,
            state_trace=("invoke_model_intent",),
            tool_plans=metadata_plans,
            requires_human_approval=False,
            response_summary="目标表不存在：public.customer_archive。",
            intent_analysis=IntentAnalysis(
                summary="识别到数据同步任务。",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("sync.task.draft.save",),
                missing_parameters=("sourceDatasourceId", "targetDatasourceId", "objectMappings"),
            ),
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(metadata_plans),
            control_plane_ingested=True,
            autonomous_resolution_stopped=True,
        )

        self.assertEqual(["targetTableResolution"], conversation["missingParameters"])
        question = conversation["clarificationQuestions"][0]
        self.assertEqual("TARGET_TABLE_RESOLUTION", question["inputType"])
        self.assertEqual(
            ["CREATE_FROM_SOURCE", "SELECT_EXISTING"],
            [item["value"] for item in question["options"]],
        )

    def test_real_provider_is_reported_as_model_assisted_with_deterministic_fallback(self) -> None:
        """真实 Provider 已启用时，会话诊断不能继续误报 RESERVED。"""

        plan = AgentPlan(
            request_id="request-model-assisted",
            selected_route=ModelRoute(
                workload=WorkloadType.AGENT_REASONING,
                provider_name="managed-agent-router",
                provider_type=ProviderType.OPENAI_COMPATIBLE,
                model_name="managed-agent-model",
                endpoint="https://model-gateway.example.com/v1",
            ),
            state_trace=(),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="",
            model_invocation_summary={
                "selectedProviderName": "managed-agent-router",
                "selectedModelName": "managed-agent-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "latencyMs": 842,
                "promptTokens": 120,
                "completionTokens": 36,
                "totalTokens": 156,
            },
        )

        summary = build_intent_resolver_summary(plan)

        self.assertEqual("MODEL_ASSISTED_WITH_DETERMINISTIC_FALLBACK", summary["mode"])
        self.assertEqual("managed-agent-router", summary["modelProvider"])
        self.assertEqual("managed-agent-model", summary["modelName"])
        self.assertTrue(summary["providerUsedForCurrentTurn"])
        self.assertTrue(summary["providerInvokedForCurrentTurn"])
        self.assertEqual(842, summary["latencyMs"])
        self.assertEqual(156, summary["totalTokens"])
        self.assertTrue(summary["deterministicFallbackAvailable"])
        self.assertNotIn("endpoint", summary)

    def test_configured_route_without_successful_invocation_is_not_reported_as_model_used(self) -> None:
        """仅配置模型路由不等于本轮真的调用成功，前端必须能识别明确降级。"""

        plan = AgentPlan(
            request_id="request-model-failed",
            selected_route=ModelRoute(
                workload=WorkloadType.AGENT_REASONING,
                provider_name="managed-agent-router",
                provider_type=ProviderType.OPENAI_COMPATIBLE,
                model_name="managed-agent-model",
                endpoint="https://model-gateway.example.com/v1",
            ),
            state_trace=(),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="",
            model_invocation_summary={
                "providerInvoked": True,
                "providerSucceeded": False,
                "resultErrorCode": "MODEL_PROVIDER_HTTP_503",
            },
        )

        summary = build_intent_resolver_summary(plan)

        self.assertEqual("MODEL_FAILED_WITH_DETERMINISTIC_FALLBACK", summary["mode"])
        self.assertTrue(summary["providerInvokedForCurrentTurn"])
        self.assertFalse(summary["providerUsedForCurrentTurn"])
        self.assertEqual("MODEL_PROVIDER_HTTP_503", summary["fallbackReasonCode"])

    def test_no_executable_plan_reports_real_model_timeout_instead_of_model_disabled(self) -> None:
        """已调用真实 Provider 时，空计划提示必须说明真实失败原因。"""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="分析当前项目中的同步任务",
        )
        plan = AgentPlan(
            request_id="request-model-timeout",
            selected_route=ModelRoute(
                workload=WorkloadType.AGENT_REASONING,
                provider_name="managed-agent-router",
                provider_type=ProviderType.OPENAI_COMPATIBLE,
                model_name="gpt-5.6-sol",
                endpoint="https://model-gateway.example.com/v1",
            ),
            state_trace=(),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="",
            model_invocation_summary={
                "providerInvoked": True,
                "providerSucceeded": False,
                "resultErrorCode": "MODEL_PROVIDER_TIMEOUT",
            },
        )

        conversation = build_agent_conversation_response(
            request,
            plan,
            ToolExecutionReadinessService().evaluate(()),
            control_plane_ingested=False,
        )

        self.assertEqual("NO_EXECUTABLE_PLAN", conversation["phase"])
        self.assertIn("真实模型本轮调用超时", conversation["assistantMessage"])
        self.assertNotIn("模型接口已预留", conversation["assistantMessage"])
        self.assertNotIn("默认未启用", conversation["assistantMessage"])


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
