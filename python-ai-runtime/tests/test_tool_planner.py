import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.context import ContextBlock, ContextSourceType
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelToolCall,
    ToolExecutionMode,
    ToolParameterIssueAction,
    ToolPlan,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis, IntentRiskTag
from datasmart_ai_runtime.domain.skills import AgentSkillPlan, AgentSkillSelection
from datasmart_ai_runtime.services.context_builder import DefaultContextBuilder
from datasmart_ai_runtime.services.intent_analyzer import RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class ToolPlannerTest(unittest.TestCase):
    def test_failed_sync_starts_with_durable_diagnosis_instead_of_task_draft(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="任务 26 的执行 28 失败了，请诊断根因并检索案例，修复前先等我确认",
            variables={
                "taskId": 26,
                "executionId": 28,
                "diagnoseSyncExecution": True,
            },
        )
        context_blocks = DefaultContextBuilder().build(request)
        intent_analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(("sync.execution.diagnose",), tuple(plan.tool_name for plan in plans))
        self.assertEqual({"taskId": 26, "executionId": 28}, plans[0].arguments)
        self.assertFalse(plans[0].requires_human_approval)

    def test_free_text_sync_uses_read_only_catalog_tools_before_any_draft(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="Migrate data from MySQL to PostgreSQL",
        )
        context_blocks = DefaultContextBuilder().build(request)
        intent_analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(
            (
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
            ),
            tuple(plan.tool_name for plan in plans),
        )
        self.assertEqual({"datasourceType": "MYSQL"}, plans[0].arguments)
        self.assertEqual({"datasourceType": "POSTGRESQL"}, plans[1].arguments)
        visible_tools = ToolPlanner(default_tool_registry()).model_visible_tools(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )
        self.assertEqual(
            {
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
            },
            {tool.name for tool in visible_tools},
        )

    def test_explicit_datasource_names_build_read_only_catalog_search_plans(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "创建全量任务：使用源端数据源 mysql2pgsql_test_0709_source，"
                "同步到目标端数据源 mysql2pgsql_test_0709_target 的 public schema。"
            ),
        )
        context_blocks = DefaultContextBuilder().build(request)
        intent_analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(
            (
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
            ),
            tuple(plan.tool_name for plan in plans),
        )
        self.assertEqual("mysql2pgsql_test_0709_source", plans[0].arguments["keyword"])
        self.assertEqual("mysql2pgsql_test_0709_target", plans[1].arguments["keyword"])
        self.assertTrue(all(plan.governance_hints["readOnly"] for plan in plans))
        self.assertFalse(any(plan.requires_human_approval for plan in plans))

    def test_model_enhanced_free_text_does_not_expand_to_unresolved_wizard_dag(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "Create a full task with source datasource mysql-source, "
                "target datasource pg-target, and map customer to public.customer."
            ),
        )
        intent_analysis = IntentAnalysis(
            summary="Model recognized a complete synchronization request.",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=(
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
                "datasource.source.connection.test",
                "datasource.target.connection.test",
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
                "sync.task.draft.save",
                "sync.task.precheck",
                "sync.task.publish",
                "sync.task.run",
                "sync.execution.status",
            ),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        self.assertEqual(
            (
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
            ),
            tuple(plan.tool_name for plan in plans),
        )
        self.assertTrue(all(plan.parameter_validation.can_execute for plan in plans))

    def test_partial_frontend_sync_shell_never_builds_tools_with_null_datasource_ids(self) -> None:
        """Descriptive form defaults are not evidence that both resources exist."""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="创建一个全量同步任务",
            variables={
                "dataSyncRequest": {
                    "taskDescription": "创建一个全量同步任务",
                    "groupCode": "DEFAULT",
                    "groupName": "默认分组",
                    "sourceDatasourceId": None,
                }
            },
        )
        intent_analysis = IntentAnalysis(
            summary="The model recognized a data synchronization request.",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=(
                "datasource.source.connection.test",
                "datasource.target.connection.test",
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
                "sync.task.draft.save",
            ),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        self.assertFalse(any(plan.tool_name.endswith("connection.test") for plan in plans))
        self.assertFalse(any(plan.tool_name.endswith("metadata.read") for plan in plans))
        self.assertFalse(any(plan.tool_name == "sync.task.draft.save" for plan in plans))
        self.assertFalse(any(plan.arguments.get("datasourceId") is None for plan in plans))

    def test_quoted_datasource_name_with_spaces_can_be_resolved_safely(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective='将源端数据源 "MySQL 订单源" 全量同步到目标端数据源 “PG 报表库”。',
        )
        context_blocks = DefaultContextBuilder().build(request)
        intent_analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(
            ("MySQL 订单源", "PG 报表库"),
            tuple(plan.arguments["keyword"] for plan in plans),
        )

    def test_generic_database_types_create_type_filtered_catalog_searches(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="请把 MySQL 的数据迁移到 PostgreSQL",
        )
        context_blocks = DefaultContextBuilder().build(request)
        intent_analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(
            (
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
            ),
            tuple(plan.tool_name for plan in plans),
        )
        self.assertEqual({"datasourceType": "MYSQL"}, plans[0].arguments)
        self.assertEqual({"datasourceType": "POSTGRESQL"}, plans[1].arguments)
        self.assertNotIn("keyword", plans[0].arguments)
        self.assertNotIn("keyword", plans[1].arguments)

    def test_model_catalog_calls_cannot_turn_connector_types_into_instance_names(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "将 MySQL 中的 fs_test_customer_source 和 fs_test_customer_target "
                "全量同步到 PostgreSQL public schema 的同名表"
            ),
        )
        calls = (
            ModelToolCall(
                call_id="source-call",
                name="datasource_source_catalog_search",
                arguments='{"keyword":"MySQL"}',
            ),
            ModelToolCall(
                call_id="target-call",
                name="datasource_target_catalog_search",
                arguments='{"keyword":"PostgreSQL"}',
            ),
        )

        normalized = ToolPlanner.normalize_datasource_catalog_tool_calls(request, calls)

        self.assertEqual('{"datasourceType":"MYSQL"}', normalized[0].arguments)
        self.assertEqual('{"datasourceType":"POSTGRESQL"}', normalized[1].arguments)

    def test_latest_correction_overrides_prior_explicit_datasource_name(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "源端数据源 mysql-old-source，全量同步到目标端数据源 pg-target"
            ),
            variables={"latestUserMessage": "源端数据源改为 mysql-customer-source"},
        )
        intent_analysis = RuleBasedIntentAnalyzer().analyze(
            request,
            DefaultContextBuilder().build(request),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        source = next(plan for plan in plans if plan.tool_name == "datasource.source.catalog.search")
        target = next(plan for plan in plans if plan.tool_name == "datasource.target.catalog.search")
        self.assertEqual("mysql-customer-source", source.arguments["keyword"])
        self.assertEqual("pg-target", target.arguments["keyword"])

    def test_negative_correction_uses_replacement_datasource_name(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "将 MySQL 中的两张客户表全量同步到 PostgreSQL public schema"
            ),
            variables={
                "latestUserMessage": (
                    "纠正一下：目标数据源不是 pgsql2mysql_test_0709_target，"
                    "而是 mysql2pgsql_test_0709_target。"
                    "源数据源 mysql2pgsql_test_0709_source 保持不变。"
                )
            },
        )
        intent_analysis = RuleBasedIntentAnalyzer().analyze(
            request,
            DefaultContextBuilder().build(request),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        source = next(plan for plan in plans if plan.tool_name == "datasource.source.catalog.search")
        target = next(plan for plan in plans if plan.tool_name == "datasource.target.catalog.search")
        self.assertEqual("mysql2pgsql_test_0709_source", source.arguments["keyword"])
        self.assertEqual("mysql2pgsql_test_0709_target", target.arguments["keyword"])

    def test_from_to_correction_uses_new_datasource_name(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="将 PostgreSQL 数据全量同步到 MySQL",
            variables={
                "latestUserMessage": (
                    "源端数据源从 pg-old-source 改成 pg-customer-source，"
                    "目标端数据源改成 mysql-customer-target"
                )
            },
        )
        intent_analysis = RuleBasedIntentAnalyzer().analyze(
            request,
            DefaultContextBuilder().build(request),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        source = next(plan for plan in plans if plan.tool_name == "datasource.source.catalog.search")
        target = next(plan for plan in plans if plan.tool_name == "datasource.target.catalog.search")
        self.assertEqual("pg-customer-source", source.arguments["keyword"])
        self.assertEqual("mysql-customer-target", target.arguments["keyword"])

    def test_latest_correction_keeps_connector_types_in_user_text_order(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective=(
                "将 MySQL 中的 fs_test_customer_source 和 fs_test_customer_target "
                "全量同步到 PostgreSQL public schema 的同名表"
            ),
            variables={
                "latestUserMessage": (
                    "MySQL 和 PostgreSQL 只是数据库类型，"
                    "源端数据源改为 mysql2pgsql_test_0709_source，"
                    "目标端数据源改为 mysql2pgsql_test_0709_target"
                )
            },
        )
        intent_analysis = RuleBasedIntentAnalyzer().analyze(
            request,
            DefaultContextBuilder().build(request),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        source = next(plan for plan in plans if plan.tool_name == "datasource.source.catalog.search")
        target = next(plan for plan in plans if plan.tool_name == "datasource.target.catalog.search")
        self.assertEqual(
            {
                "keyword": "mysql2pgsql_test_0709_source",
                "datasourceType": "MYSQL",
            },
            source.arguments,
        )
        self.assertEqual(
            {
                "keyword": "mysql2pgsql_test_0709_target",
                "datasourceType": "POSTGRESQL",
            },
            target.arguments,
        )

    def test_instance_name_connector_substrings_do_not_override_declared_source_type(self) -> None:
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
                    "请继续自动核对并完成配置：仍使用源数据源 "
                    "mysql2pgsql_test_0709_source 和目标数据源 "
                    "mysql2pgsql_test_0709_target，将 fs_test_customer_source、"
                    "fs_test_customer_target 分别映射到 PostgreSQL public schema "
                    "下的同名表，其他配置保持不变。"
                )
            },
        )
        intent_analysis = RuleBasedIntentAnalyzer().analyze(
            request,
            DefaultContextBuilder().build(request),
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        source = next(plan for plan in plans if plan.tool_name == "datasource.source.catalog.search")
        target = next(plan for plan in plans if plan.tool_name == "datasource.target.catalog.search")
        self.assertEqual(
            {
                "keyword": "mysql2pgsql_test_0709_source",
                "datasourceType": "MYSQL",
            },
            source.arguments,
        )
        self.assertEqual(
            {
                "keyword": "mysql2pgsql_test_0709_target",
                "datasourceType": "POSTGRESQL",
            },
            target.arguments,
        )

    def test_follow_up_frontier_keeps_other_branch_from_durable_resource_ledger(self) -> None:
        """A split Java Run must not hide the target branch after reading source metadata."""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="继续完成当前数据同步任务",
        )
        source_metadata_plan = ToolPlan(
            tool_name="datasource.source.metadata.read",
            reason="read one source table",
            governance_hints={
                "agentLoopResourceRefs": {
                    "datasource.target.connection.test": {
                        "toolCode": "datasource.target.connection.test",
                        "auditId": "audit-target-connection",
                        "runId": "run-connections",
                    },
                    # A malformed entry must not expand model visibility.
                    "permission.policy.write": {
                        "toolCode": "permission.policy.write",
                        "auditId": "",
                        "runId": "run-forged",
                    },
                }
            },
        )
        intent = IntentAnalysis(
            summary="continue sync",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=(),
        )

        visible = ToolPlanner(default_tool_registry()).model_visible_follow_up_tools(
            request=request,
            intent_analysis=intent,
            previous_tool_plans=(source_metadata_plan,),
        )

        self.assertEqual(
            {
                "datasource.target.metadata.read",
                "datasource.target-table.create.preview",
                "sync.task.draft.save",
            },
            {tool.name for tool in visible},
        )

    def test_follow_up_frontier_exposes_cdc_probe_only_for_realtime_mode(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="create realtime sync",
            variables={"dataSyncRequest": {"syncMode": "CDC_STREAMING"}},
        )
        metadata_plan = ToolPlan(
            tool_name="datasource.source.metadata.read",
            reason="source metadata",
        )

        visible = ToolPlanner(default_tool_registry()).model_visible_follow_up_tools(
            request=request,
            intent_analysis=IntentAnalysis(
                summary="realtime sync",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
            ),
            previous_tool_plans=(metadata_plan,),
        )

        self.assertIn("sync.cdc.readiness.check", {tool.name for tool in visible})

    def test_follow_up_frontier_recognizes_explicit_realtime_natural_language(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="请把 customer 表实时同步到 PostgreSQL。",
        )
        metadata_plan = ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        )

        visible = ToolPlanner(default_tool_registry()).model_visible_follow_up_tools(
            request=request,
            intent_analysis=IntentAnalysis(
                summary="realtime sync",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
            ),
            previous_tool_plans=(metadata_plan,),
        )

        self.assertIn("sync.cdc.readiness.check", {tool.name for tool in visible})

    def test_cdc_probe_mapping_contract_does_not_require_secret_approval(self) -> None:
        tool = next(
            item for item in default_tool_registry()
            if item.name == "sync.cdc.readiness.check"
        )

        self.assertFalse(tool.input_schema["objectMappings"]["sensitive"])
        self.assertEqual((), tool.sensitive_fields)
        self.assertTrue(tool.read_only)
        self.assertTrue(tool.idempotent)

    def test_sync_model_visibility_hides_direction_neutral_metadata_alias(self) -> None:
        """Sync mappings require role-specific evidence even when an old Skill lists the alias."""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="创建数据同步任务",
        )
        intent = IntentAnalysis(
            summary="sync",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=(
                "datasource.metadata.read",
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
            ),
        )

        visible = ToolPlanner(default_tool_registry()).model_visible_tools(
            request=request,
            intent_analysis=intent,
        )

        self.assertNotIn("datasource.metadata.read", {tool.name for tool in visible})
        self.assertIn("datasource.source.metadata.read", {tool.name for tool in visible})
        self.assertIn("datasource.target.metadata.read", {tool.name for tool in visible})

    def test_full_sync_assistant_builds_nine_node_governed_dag_without_credentials(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把 MySQL 两张客户表全量同步到 PostgreSQL public schema 的同名表",
            variables={
                "dataSyncRequest": {
                    "taskName": "客户表全量同步",
                    "sourceDatasourceId": 23,
                    "targetDatasourceId": 24,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [
                        {
                            "objectKey": "customers",
                            "sourceObjectName": "fs_test_customer_source",
                            "targetSchemaName": "public",
                            "targetObjectName": "fs_test_customer_source",
                        },
                        {
                            "objectKey": "customer-target",
                            "sourceObjectName": "fs_test_customer_target",
                            "targetSchemaName": "public",
                            "targetObjectName": "fs_test_customer_target",
                        },
                    ],
                }
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        self.assertEqual(
            (
                "datasource.source.connection.test",
                "datasource.target.connection.test",
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
                "sync.task.draft.save",
                "sync.task.precheck",
                "sync.task.publish",
                "sync.task.run",
                "sync.execution.status",
            ),
            tuple(plan.tool_name for plan in plans),
        )
        by_name = {plan.tool_name: plan for plan in plans}
        self.assertEqual(
            ["fs_test_customer_source", "fs_test_customer_target"],
            by_name["datasource.source.metadata.read"].arguments["tableNames"],
        )
        self.assertEqual(
            ["fs_test_customer_source", "fs_test_customer_target"],
            by_name["datasource.target.metadata.read"].arguments["tableNames"],
        )
        self.assertEqual(
            "public",
            by_name["datasource.target.metadata.read"].arguments["schemaPattern"],
        )
        self.assertEqual(
            ("datasource-source-metadata-read", "datasource-target-metadata-read"),
            by_name["sync.task.draft.save"].governance_hints["dependsOn"],
        )
        self.assertEqual(
            ("sync-task-draft-save", "sync-task-precheck"),
            by_name["sync.task.publish"].governance_hints["dependsOn"],
        )
        serialized = str(tuple(plan.arguments for plan in plans)).lower()
        self.assertNotIn("password", serialized)
        self.assertNotIn("jdbcurl", serialized)
        self.assertNotIn("credential", serialized)

    def test_scheduled_sync_publishes_with_schedule_without_immediate_run(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="每天凌晨定期全量同步客户表",
            variables={
                "dataSyncRequest": {
                    "taskName": "客户表定期全量",
                    "sourceDatasourceId": 23,
                    "targetDatasourceId": 24,
                    "syncMode": "SCHEDULED_FULL",
                    "scheduleConfig": '{"cron":"0 0 2 * * ?","timezone":"Asia/Shanghai"}',
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{"sourceField": "id", "targetField": "id"}],
                    }],
                }
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        self.assertEqual(
            (
                "datasource.source.connection.test",
                "datasource.target.connection.test",
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
                "sync.task.draft.save",
                "sync.task.precheck",
                "sync.task.publish",
            ),
            tuple(plan.tool_name for plan in plans),
        )
        by_name = {plan.tool_name: plan for plan in plans}
        self.assertEqual("SCHEDULED_FULL", by_name["sync.task.draft.save"].arguments["syncMode"])
        self.assertTrue(by_name["sync.task.publish"].arguments["enableSchedule"])
        self.assertNotIn("sync.task.run", by_name)

    def test_legacy_real_time_mode_is_normalized_and_never_uses_offline_run(self) -> None:
        request = AgentRequest(
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
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{"sourceField": "id", "targetField": "id"}],
                    }],
                }
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)
        by_name = {plan.tool_name: plan for plan in plans}

        self.assertEqual("CDC_STREAMING", by_name["sync.task.draft.save"].arguments["syncMode"])
        self.assertEqual("UPDATE", by_name["sync.task.draft.save"].arguments["writeStrategy"])
        self.assertIn("sync.cdc.readiness.check", by_name)
        self.assertEqual(
            "sync.cdc.readiness.check",
            by_name["sync.task.draft.save"].arguments["cdcReadinessRef"]["fromTool"],
        )
        self.assertNotIn("sync.task.run", by_name)

    def test_custom_sql_preserves_target_only_mapping_and_sql_text(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="使用 SQL 查询结果同步到报表表",
            variables={
                "dataSyncRequest": {
                    "sourceDatasourceId": 23,
                    "targetDatasourceId": 24,
                    "syncMode": "CUSTOM_SQL_QUERY",
                    "customSqlText": "select id, name as customer_name from customer",
                    "objectMappings": [{
                        "targetSchemaName": "public",
                        "targetObjectName": "customer_report",
                        "fieldMappings": [
                            {"sourceField": "id", "targetField": "id"},
                            {"sourceField": "customer_name", "targetField": "name"},
                        ],
                    }],
                }
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)
        draft = next(plan for plan in plans if plan.tool_name == "sync.task.draft.save")

        self.assertEqual("CUSTOM_SQL_QUERY", draft.arguments["syncMode"])
        self.assertEqual(
            "select id, name as customer_name from customer",
            draft.arguments["customSqlText"],
        )
        self.assertIsNone(draft.arguments["objectMappings"][0].get("sourceObjectName"))

    def test_uses_intent_candidate_tools_even_when_objective_has_no_quality_keyword(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请根据这批客户主数据给我一个检查方案",
            variables={},
        )
        context_blocks = (
            ContextBlock(
                source_type=ContextSourceType.DATASOURCE_METADATA,
                title="数据源元数据",
                content="客户主数据表元数据",
                metadata={"datasourceId": "ds-from-context"},
            ),
            ContextBlock(
                source_type=ContextSourceType.QUALITY_RULE_CASE,
                title="质量规则案例",
                content="客户手机号完整性规则案例",
                metadata={"businessGoal": "客户手机号完整性校验"},
            ),
        )
        intent_analysis = IntentAnalysis(
            summary="识别为数据质量规则建议场景",
            governance_domains=(GovernanceDomain.DATA_QUALITY,),
            candidate_tools=("quality.rule.suggest",),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION,),
            confidence=0.82,
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(
            ("datasource.metadata.read", "quality.rule.suggest"),
            tuple(plan.tool_name for plan in plans),
        )
        self.assertEqual("ds-from-context", plans[0].arguments["datasourceId"])
        self.assertEqual("ds-from-context", plans[1].arguments["datasourceId"])
        self.assertEqual("客户手机号完整性校验", plans[1].arguments["businessGoal"])
        self.assertTrue(plans[1].parameter_validation.can_execute)
        self.assertEqual((), plans[1].parameter_validation.issues)

    def test_task_plan_receives_intent_risk_tags_and_missing_parameters(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请生成执行方案",
            variables={},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为任务草案场景",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(IntentRiskTag.STATE_CHANGE, IntentRiskTag.APPROVAL_REQUIRED),
            missing_parameters=("datasourceId",),
            confidence=0.7,
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        self.assertEqual("task.create.draft", plans[0].tool_name)
        self.assertTrue(plans[0].requires_human_approval)
        self.assertEqual(("state_change", "approval_required"), plans[0].arguments["payload"]["intentRiskTags"])
        self.assertEqual(("datasourceId",), plans[0].arguments["payload"]["missingParameters"])

    def test_quality_rule_missing_datasource_can_only_create_draft(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请给我生成客户主数据质量规则",
            variables={"businessGoal": "客户主数据完整性校验"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = next(plan for plan in plans if plan.tool_name == "quality.rule.suggest")
        self.assertFalse(plan.parameter_validation.can_execute)
        self.assertTrue(plan.parameter_validation.can_create_draft)
        self.assertEqual("datasourceId", plan.parameter_validation.issues[0].parameter_name)
        self.assertEqual(ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT, plan.parameter_validation.issues[0].action)

    def test_workspace_file_read_plan_uses_digest_instead_of_raw_path(self) -> None:
        """文件读取计划只能携带低敏路径摘要，不能把真实相对路径暴露给 `/agent/plans`。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请读取 workspace 里的 README 文件",
            variables={"workspaceFilePath": "docs/private-note.md", "sessionId": "session-a"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = next(plan for plan in plans if plan.tool_name == "workspace.file.read")
        serialized = str(plan.arguments)
        self.assertEqual(ToolExecutionMode.SYNC, plan.execution_mode)
        self.assertFalse(plan.requires_human_approval)
        self.assertIn("filePathRef", plan.arguments)
        self.assertIn("filePathDigest", plan.arguments)
        self.assertEqual("agent:workspace-file:read", next(tool for tool in default_tool_registry() if tool.name == "workspace.file.read").required_permissions[0])
        self.assertNotIn("docs/private-note.md", serialized)
        self.assertTrue(plan.parameter_validation.can_execute)
        self.assertEqual((), plan.parameter_validation.issues)

    def test_workspace_file_write_plan_requires_approval_and_hides_content(self) -> None:
        """文件写入计划必须进入审批，并且只携带 contentRef，不携带正文。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请创建文件保存治理报告",
            variables={
                "workspaceFilePath": "outputs/report.md",
                "workspaceFileContent": "这里是不能进入计划响应的报告正文",
                "sessionId": "session-a",
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = next(plan for plan in plans if plan.tool_name == "workspace.file.write")
        serialized = str(plan.arguments)
        self.assertEqual(ToolExecutionMode.APPROVAL_REQUIRED, plan.execution_mode)
        self.assertTrue(plan.requires_human_approval)
        self.assertIn("contentRef", plan.arguments)
        self.assertIn("contentByteCount", plan.arguments)
        self.assertNotIn("outputs/report.md", serialized)
        self.assertNotIn("报告正文", serialized)
        self.assertTrue(plan.parameter_validation.can_execute)

    def test_workspace_file_write_without_content_waits_for_content_reference(self) -> None:
        """只说写文件但没有内容引用时，计划应保留但不能执行。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请写文件到 workspace",
            variables={"workspaceFilePath": "outputs/report.md"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = next(plan for plan in plans if plan.tool_name == "workspace.file.write")
        self.assertFalse(plan.parameter_validation.can_execute)
        self.assertEqual("contentRef", plan.parameter_validation.issues[0].parameter_name)
        self.assertEqual(ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT, plan.parameter_validation.issues[0].action)

    def test_web_search_plan_uses_query_reference_and_governance_policies(self) -> None:
        """网页搜索计划只能携带低敏查询引用和治理策略。

        web-search 是外部网络能力，不能把用户原始查询、URL、SQL 或敏感业务词直接写入 `/agent/plans`。
        Planner 这里只生成搜索草案，真实 Provider 执行必须继续经过网络权限、allowlist、限流和引用校验。
        """

        raw_query = "Qwen3.7 Agent tool calling latest release"
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请联网搜索最新资料并给出引用来源",
            variables={"searchQuery": raw_query, "sessionId": "session-web-search"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = next(plan for plan in plans if plan.tool_name == "web.search.query")
        serialized = str(plan.arguments)
        self.assertEqual(ToolExecutionMode.DRAFT_ONLY, plan.execution_mode)
        self.assertFalse(plan.requires_human_approval)
        self.assertTrue(plan.parameter_validation.can_execute)
        self.assertEqual("LOW_SENSITIVE_SEARCH_GOVERNANCE_METADATA_ONLY", plan.arguments["payloadPolicy"])
        self.assertIn("searchQueryRef", plan.arguments)
        self.assertIn("providerPolicy", plan.arguments)
        self.assertIn("rateLimitPolicy", plan.arguments)
        self.assertEqual("webSearchResults", plan.governance_hints["resultAlias"])
        self.assertTrue(plan.governance_hints["webSearchGoverned"])
        self.assertEqual("CONTROLLED_PROVIDER_ONLY", plan.governance_hints["externalNetworkAccess"])
        self.assertNotIn(raw_query, serialized)
        self.assertNotIn("最新资料", serialized)

    def test_governance_rag_plan_uses_query_ref_and_evidence_policy(self) -> None:
        """治理 RAG 计划只能携带 queryRef 和证据策略，不能携带原始问题。"""

        raw_question = "质量规则为什么需要先读取元数据证据"
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请从治理知识库解释质量规则为什么需要元数据证据",
            variables={"knowledgeQuery": raw_question, "sessionId": "session-rag-plan"},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为治理知识 RAG 问答",
            governance_domains=(GovernanceDomain.KNOWLEDGE_QA,),
            candidate_tools=("knowledge.rag.query",),
            risk_tags=(IntentRiskTag.READ_ONLY,),
            confidence=0.88,
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request, intent_analysis=intent_analysis)

        plan = next(plan for plan in plans if plan.tool_name == "knowledge.rag.query")
        serialized = str(plan.arguments)
        self.assertEqual(ToolExecutionMode.SYNC, plan.execution_mode)
        self.assertFalse(plan.requires_human_approval)
        self.assertTrue(plan.parameter_validation.can_execute)
        self.assertEqual("LOW_SENSITIVE_RAG_QUERY_REFERENCE_ONLY", plan.arguments["payloadPolicy"])
        self.assertIn("queryRef", plan.arguments)
        self.assertIn("scopePolicy", plan.arguments)
        self.assertIn("evidencePolicy", plan.arguments)
        self.assertEqual("ragEvidence", plan.governance_hints["resultAlias"])
        self.assertTrue(plan.arguments["evidencePolicy"]["failClosedWhenNoEvidence"])
        self.assertTrue(plan.arguments["evidencePolicy"]["langGraphCheckpointRequired"])
        self.assertNotIn(raw_question, serialized)
        self.assertNotIn("元数据证据", serialized)

    def test_task_plan_carries_high_risk_intent_tags(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请导出所有项目手机号异常样本并创建任务",
            variables={"createTask": True},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为高风险导出任务",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(
                IntentRiskTag.DATA_EXPORT,
                IntentRiskTag.SENSITIVE_DATA,
                IntentRiskTag.CROSS_SCOPE,
                IntentRiskTag.APPROVAL_REQUIRED,
            ),
            missing_parameters=("exportFormat",),
            confidence=0.86,
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        task_plan = next(plan for plan in plans if plan.tool_name == "task.create.draft")
        payload = task_plan.arguments["payload"]
        self.assertEqual(
            ("data_export", "sensitive_data", "cross_scope", "approval_required"),
            payload["intentRiskTags"],
        )
        self.assertEqual(("exportFormat",), payload["missingParameters"])

    def test_quality_remediation_task_draft_uses_low_sensitive_scope_contract(self) -> None:
        """质量异常治理任务草案只应携带低敏范围和 dry-run 控制字段。

        这个测试把 Java data-quality 已完成的 `/quality-rules/remediation-tasks` 契约接入 Agent 规划层：
        - Planner 只生成 `quality.remediation.task.draft`，不再额外生成泛化 `task.create.draft`；
        - 参数使用 `remediationScope` 收口，便于后续来自质量报告、异常工作台或告警中心的入口复用；
        - 不把用户 objective、异常样本、SQL、prompt 或模型输出塞进工具参数，避免规划响应成为敏感内容扩散点。
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请把 77 号质量报告里的高危异常创建治理任务草案，先人工复核",
            variables={
                "reportId": 77,
                "severity": "HIGH",
                "anomalyType": "FORMAT_INVALID",
                "fieldName": "phone",
                "createRemediationTask": True,
                "priority": "HIGH",
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        self.assertEqual(("quality.remediation.task.draft",), tuple(plan.tool_name for plan in plans))
        plan = plans[0]
        self.assertEqual(ToolExecutionMode.DRAFT_ONLY, plan.execution_mode)
        self.assertFalse(plan.requires_human_approval)
        self.assertTrue(plan.arguments["dryRun"])
        self.assertEqual("LOW_SENSITIVE_AGGREGATION_ONLY", plan.arguments["payloadPolicy"])
        self.assertEqual("HIGH", plan.arguments["priority"])
        self.assertEqual("remediationTaskDraft", plan.governance_hints["resultAlias"])
        self.assertEqual("/quality-rules/remediation-tasks", plan.governance_hints["targetEndpoint"])
        self.assertEqual(("remediationScope", "reason", "recommendation"), plan.governance_hints["sensitiveFields"])
        self.assertTrue(plan.parameter_validation.can_execute)
        self.assertEqual((), plan.parameter_validation.issues)

        scope = plan.arguments["remediationScope"]
        self.assertEqual(77, scope["reportId"])
        self.assertEqual("HIGH", scope["severity"])
        self.assertEqual("FORMAT_INVALID", scope["anomalyType"])
        self.assertEqual("phone", scope["fieldName"])
        self.assertNotIn("objective", plan.arguments)
        self.assertNotIn("sample", str(plan.arguments).lower())
        self.assertNotIn("select * from", str(plan.arguments).lower())

    def test_quality_remediation_task_without_scope_waits_for_context_or_user_selection(self) -> None:
        """没有任何异常定位范围时，治理任务草案不能假装可执行。

        真实商业场景里，用户可能只说“帮我创建异常治理任务”，但没有说明是哪份报告、哪条规则或哪批异常。
        Planner 此时仍可以保留工具意图，但 readiness 会基于 `remediationScope` 缺失进入澄清/上下文补齐链路。
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请创建质量异常治理任务草案",
            variables={"createRemediationTask": True},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = plans[0]
        self.assertEqual("quality.remediation.task.draft", plan.tool_name)
        self.assertFalse(plan.parameter_validation.can_execute)
        self.assertEqual("remediationScope", plan.parameter_validation.issues[0].parameter_name)
        self.assertEqual(ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT, plan.parameter_validation.issues[0].action)

    def test_tool_plan_exposes_governance_hints_from_descriptor_contract(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        metadata_plan = next(plan for plan in plans if plan.tool_name == "datasource.metadata.read")
        self.assertTrue(metadata_plan.governance_hints["tenantScoped"])
        self.assertTrue(metadata_plan.governance_hints["projectScoped"])
        self.assertEqual(("datasourceId",), metadata_plan.governance_hints["sensitiveFields"])
        self.assertEqual("semantic", metadata_plan.governance_hints["memoryWritePolicy"])
        self.assertEqual("project_safe", metadata_plan.governance_hints["cachePolicy"])

    def test_model_visible_tools_merge_intent_skill_and_plan_candidates(self) -> None:
        """模型可见工具应来自意图、Skill 和规则计划的并集。

        这条测试保护的是“暴露给模型的工具集合”与“后续规则式工具计划”之间的一致性。真实 Agent
        里，模型需要提前看到可用工具 schema 才能提出 tool_calls；但工具集合又不能是全量平台工具，
        必须由意图和 Skill 裁剪。
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请为订单表生成质量规则并创建任务草稿",
            variables={"datasourceId": "ds-order", "businessGoal": "订单质量校验", "createTask": True},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为质量规则与任务草案场景",
            governance_domains=(GovernanceDomain.DATA_QUALITY, GovernanceDomain.TASK_MANAGEMENT),
            candidate_tools=("quality.rule.suggest",),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION, IntentRiskTag.APPROVAL_REQUIRED),
            confidence=0.9,
        )
        skill_plan = AgentSkillPlan(
            selected_skills=(
                AgentSkillSelection(
                    skill_code="governed.task.creation",
                    display_name="受控任务创建 Skill",
                    domain=GovernanceDomain.TASK_MANAGEMENT,
                    score=0.92,
                    reason="测试场景",
                    required_tools=("task.create.draft", "task.draft.persist"),
                    approval_policy="HUMAN_APPROVAL_REQUIRED",
                ),
            )
        )

        visible_tools = ToolPlanner(default_tool_registry()).model_visible_tools(
            request=request,
            intent_analysis=intent_analysis,
            skill_plan=skill_plan,
        )

        self.assertEqual(
            (
                "quality.rule.suggest",
                "task.create.draft",
                "task.draft.persist",
                "datasource.metadata.read",
            ),
            tuple(tool.name for tool in visible_tools),
        )

    def test_multi_step_governance_chain_uses_explicit_output_references(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请为订单表生成质量规则并创建任务草稿",
            variables={
                "datasourceId": "ds-order",
                "businessGoal": "订单主键唯一性和金额有效性校验",
                "createTask": True,
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        self.assertEqual(
            (
                "datasource.metadata.read",
                "quality.rule.suggest",
                "task.create.draft",
                "task.draft.persist",
            ),
            tuple(plan.tool_name for plan in plans),
        )

        quality_plan = next(plan for plan in plans if plan.tool_name == "quality.rule.suggest")
        metadata_ref = quality_plan.arguments["metadataRef"]
        self.assertEqual("quality-rule-suggest", quality_plan.governance_hints["planNodeId"])
        self.assertEqual(("datasource-metadata-read",), quality_plan.governance_hints["dependsOn"])
        self.assertEqual(("datasource.metadata.read",), quality_plan.governance_hints["dependsOnTools"])
        self.assertEqual("after-datasource-metadata-read", quality_plan.governance_hints["parallelGroup"])
        self.assertEqual("suggestion", quality_plan.governance_hints["resultAlias"])
        self.assertEqual(
            "datasource.metadata.read",
            metadata_ref["fromTool"],
        )
        self.assertEqual("metadata", metadata_ref["path"])
        self.assertEqual("LATEST_SUCCESS_IN_RUN", metadata_ref["referenceMode"])
        self.assertEqual("tool_output", metadata_ref["resourceReference"]["kind"])
        self.assertEqual("workspace://tool-output/datasource.metadata.read?path=metadata", metadata_ref["resourceReference"]["uri"])

        create_draft_plan = next(plan for plan in plans if plan.tool_name == "task.create.draft")
        self.assertEqual(("quality-rule-suggest",), create_draft_plan.governance_hints["dependsOn"])
        self.assertEqual("taskDraft", create_draft_plan.governance_hints["resultAlias"])
        self.assertEqual("DATA_QUALITY_SCAN", create_draft_plan.arguments["taskType"])
        suggestion_ref = create_draft_plan.arguments["suggestionRef"]
        self.assertEqual(
            "quality.rule.suggest",
            suggestion_ref["resourceReference"]["toolCode"],
        )
        self.assertEqual("suggestion", suggestion_ref["resourceReference"]["jsonPath"])

        persist_plan = next(plan for plan in plans if plan.tool_name == "task.draft.persist")
        self.assertTrue(persist_plan.requires_human_approval)
        self.assertEqual(("task-create-draft",), persist_plan.governance_hints["dependsOn"])
        self.assertEqual("MANUAL_REVIEW", persist_plan.governance_hints["failurePolicy"])
        task_draft_ref = persist_plan.arguments["taskDraftRef"]
        self.assertEqual(
            "task.create.draft",
            task_draft_ref["resourceReference"]["toolCode"],
        )
        self.assertEqual("taskDraft", task_draft_ref["resourceReference"]["jsonPath"])


if __name__ == "__main__":
    unittest.main()
