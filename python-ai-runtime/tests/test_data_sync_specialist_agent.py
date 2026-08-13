from __future__ import annotations

import unittest
from dataclasses import replace
from typing import Any, Mapping

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnRequest,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialists.data_sync_agent import (
    DataSyncSpecialistAgent,
    SyncMetadataDiscoveryError,
    SyncMetadataDiscoveryRequest,
    SyncMetadataDiscoveryResult,
    SyncPlanningModelInput,
    SyncPlanningModelOutput,
)


class _PlanningModel:
    """测试模型只记录低敏输入并返回预置建议，不执行任何外部动作。"""

    def __init__(self, output: SyncPlanningModelOutput | Mapping[str, Any]) -> None:
        self.output = output
        self.requests: list[SyncPlanningModelInput] = []

    def plan(self, request: SyncPlanningModelInput) -> SyncPlanningModelOutput | Mapping[str, Any]:
        """模拟一次独立 Provider 规划调用，便于断言专业 Agent 的输入边界。"""

        self.requests.append(request)
        return self.output


class _FailingPlanningModel:
    """带稳定 Provider 原因码的模型替身，用于验证错误事实不会被吞掉。"""

    def plan(self, request: SyncPlanningModelInput) -> SyncPlanningModelOutput:
        """模拟一次超时，异常正文故意包含不应回显的传输细节。"""

        error = RuntimeError("https://provider.invalid/v1/responses Authorization=secret")
        error.reason_code = "MODEL_TIMEOUT"
        error.reason_source = "MODEL_PROVIDER_TRANSPORT"
        raise error


class _MetadataTool:
    """双端元数据工具替身，记录结构化请求并返回真实形态的低敏摘要。"""

    def __init__(self, *, fail_side: str | None = None) -> None:
        self.fail_side = fail_side
        self.requests: list[SyncMetadataDiscoveryRequest] = []

    def discover(self, request: SyncMetadataDiscoveryRequest) -> SyncMetadataDiscoveryResult:
        """模拟 data-sync 元数据发现，不读取自然语言、不执行写操作。"""

        self.requests.append(request)
        if request.side.lower() == self.fail_side:
            raise SyncMetadataDiscoveryError("SYNC_METADATA_HTTP_TIMEOUT")
        schema = None if request.side == "SOURCE" else "public"
        table_names = request.table_names or ("customer",)
        return SyncMetadataDiscoveryResult(
            datasource_id=request.datasource_id,
            side=request.side,
            connector_type=request.connector_type or ("MYSQL" if request.side == "SOURCE" else "POSTGRESQL"),
            metadata={
                "datasourceId": request.datasource_id,
                "objects": [
                    _metadata(schema, table_name)["objects"][0]
                    for table_name in table_names
                ],
            },
            object_count=len(table_names),
            field_count=2 * len(table_names),
            evidence_reference=f"sync-metadata://{request.side.lower()}",
        )


def _metadata(schema_name: str | None, table_name: str) -> dict[str, Any]:
    """构造两端结构相同的最小真实元数据摘要。"""

    return {
        "objects": [
            {
                "schemaName": schema_name,
                "tableName": table_name,
                "columns": [
                    {"columnName": "id", "dataTypeName": "BIGINT"},
                    {"columnName": "name", "dataTypeName": "VARCHAR"},
                ],
            }
        ]
    }


def _request(
    *,
    context: Mapping[str, Any] | None = None,
    allowed_tools: tuple[str, ...] = (
        "datasource.source.metadata.read",
        "datasource.target.metadata.read",
        "sync.task.publish",
    ),
) -> SpecialistTurnRequest:
    """创建具备双主体审计范围的 DATA_SYNC_AGENT 委派请求。"""

    structured_context = {
        # 真实运行时这两个字段来自当前页面已确认的数据源选择或 DATASOURCE_AGENT，
        # 不再把模型返回的数字当作业务事实。
        "sourceDatasourceId": 27,
        "targetDatasourceId": 28,
        "sourceConnectorType": "MYSQL",
        "targetConnectorType": "POSTGRESQL",
    }
    if context is not None:
        structured_context.update(context)

    return SpecialistTurnRequest(
        turn_id="turn-sync-1",
        session_id="session-sync-1",
        run_id="run-sync-1",
        role=AgentSessionRole.DATA_SYNC_AGENT,
        objective="规划 customer 从 MySQL 到 PostgreSQL 的同步任务",
        scope=SpecialistDelegationScope(
            tenant_id="1",
            application_id="datasmart",
            project_id="101",
            actor_id="user-1",
            delegation_id="delegation-sync-1",
            allowed_tool_names=allowed_tools,
        ),
        context_summary=structured_context,
        evidence_references=("agent-evidence://metadata/customer",),
    )


def _full_configuration() -> dict[str, Any]:
    """返回不包含字段映射的完整全量建议，以验证本地同名字段推断。"""

    return {
        "taskName": "customer-full",
        "sourceDatasourceId": 27,
        "targetDatasourceId": 28,
        "syncMode": "FULL",
        "writeStrategy": "INSERT",
        "objectMappings": [
            {
                "sourceObjectName": "customer",
                "targetSchemaName": "public",
                "targetObjectName": "customer",
            }
        ],
    }


class DataSyncSpecialistAgentTest(unittest.TestCase):
    """验证 DATA_SYNC_AGENT 的规划边界、模式规则、元数据事实和副作用治理。"""

    def test_completes_full_draft_and_marks_default_same_name_field_mappings(self) -> None:
        model = _PlanningModel(
            SyncPlanningModelOutput(
                configuration=_full_configuration(),
                public_summary="已规划全量同步。",
                invocation_summary={
                    "providerInvoked": True,
                    "providerSucceeded": True,
                    "providerName": "openai-compatible",
                    "modelName": "test-sync-planner",
                    "latencyMs": 18,
                    "rawPrompt": "不得进入低敏摘要",
                },
            )
        )
        events: list[Mapping[str, Any]] = []
        context = {
            "sourceMetadata": _metadata(None, "customer"),
            "targetMetadata": _metadata("public", "customer"),
            "password": "must-not-reach-model",
        }

        result = DataSyncSpecialistAgent(model).execute(_request(context=context), events.append)

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(AgentSessionRole.DATA_SYNC_AGENT, result.role)
        self.assertEqual("FULL", result.structured_output["syncMode"])
        self.assertEqual("NO_FILTER", result.structured_output["objectMappings"][0]["whereMode"])
        fields = result.structured_output["objectMappings"][0]["fieldMappings"]
        self.assertEqual(["id", "name"], [item["sourceField"] for item in fields])
        self.assertTrue(all(item["inferred"] for item in fields))
        self.assertTrue(all(item["inferenceSource"] == "SAME_NAME_METADATA_DEFAULT" for item in fields))
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertNotIn("rawPrompt", result.model_invocation_summary)
        self.assertEqual("[REDACTED]", model.requests[0].context["password"])
        self.assertNotIn("sync.task.publish", model.requests[0].allowed_tool_names)
        self.assertEqual("CONFIGURATION_DRAFT_COMPLETED", events[-1]["action"])
        self.assertTrue(all(event["payloadPolicy"] == "LOW_SENSITIVE_SPECIALIST_EVENT_ONLY" for event in events))

    def test_completes_explicit_table_request_when_model_omits_object_mappings(self) -> None:
        """Explicit names must survive an incomplete model draft when metadata verifies both sides."""

        table_names = ("fs_test_customer_source", "fs_test_customer_target")
        source_metadata = {
            "objects": [
                {**_metadata(None, table_name)["objects"][0], "tableName": table_name}
                for table_name in table_names
            ]
        }
        target_metadata = {
            "objects": [
                {**_metadata("public", table_name)["objects"][0], "tableName": table_name}
                for table_name in table_names
            ]
        }
        configuration = _full_configuration()
        configuration["objectMappings"] = [
            {
                "sourceObjectName": "",
                "targetObjectName": "",
                "fieldMappings": [
                    {"sourceField": "", "targetField": ""},
                    {"sourceField": "", "targetField": ""},
                ],
            }
        ]
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        request = replace(
            _request(context={"sourceMetadata": source_metadata, "targetMetadata": target_metadata}),
            objective=(
                "将 MySQL 中的 fs_test_customer_source 和 fs_test_customer_target "
                "全量同步到 PostgreSQL public schema 的同名表"
            ),
        )

        result = DataSyncSpecialistAgent(model).execute(request)

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        mappings = result.structured_output["objectMappings"]
        self.assertEqual(list(table_names), [item["sourceObjectName"] for item in mappings])
        self.assertEqual(list(table_names), [item["targetObjectName"] for item in mappings])
        self.assertTrue(all(item["targetSchemaName"] == "public" for item in mappings))
        self.assertTrue(all(item["whereMode"] == "NO_FILTER" for item in mappings))
        self.assertTrue(all(item["fieldMappings"] for item in mappings))
        self.assertEqual(
            "USER_EXPLICIT_TABLES_VERIFIED_BY_METADATA",
            result.structured_output["objectMappingsSource"],
        )

    def test_explicit_tables_are_discovered_before_planning(self) -> None:
        """多表自然语言目标必须先发起精确元数据查询，再进行字段映射规划。"""

        table_names = ("fs_test_customer_source", "fs_test_customer_target")
        configuration = _full_configuration()
        configuration["objectMappings"] = [
            {
                "sourceObjectName": table_name,
                "targetSchemaName": "public",
                "targetObjectName": table_name,
            }
            for table_name in table_names
        ]
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        metadata_tool = _MetadataTool()
        request = replace(
            _request(),
            objective=(
                "将 MySQL 中的 fs_test_customer_source 和 fs_test_customer_target "
                "全量同步到 PostgreSQL public schema 的同名表"
            ),
        )

        result = DataSyncSpecialistAgent(model, metadata_discovery_tool=metadata_tool).execute(request)

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(2, len(metadata_tool.requests))
        source_request, target_request = metadata_tool.requests
        self.assertEqual(table_names, source_request.table_names)
        self.assertEqual(table_names, target_request.table_names)
        self.assertEqual("TABLE", source_request.filter_mode)
        self.assertEqual("SCHEMA_AND_TABLE", target_request.filter_mode)
        self.assertIsNone(source_request.schema_pattern)
        self.assertEqual("public", target_request.schema_pattern)

    def test_mysql_source_rejects_postgresql_schema_but_accepts_schema_less_object(self) -> None:
        """跨数据库映射不能把 PostgreSQL schema 误用成 MySQL 对象命名空间。

        MySQL JDBC 元数据把当前 database 暴露为 catalog，表对象本身通常没有 ``schemaName``；
        PostgreSQL 则使用真实 schema。若页面或 E2E 把目标端 schema 复制到源端，Agent 必须返回
        ``WAITING_FOR_INPUT``，不能仅凭表名猜中后继续创建任务。用户删除错误的源 schema 后，
        同一份真实元数据应当通过校验，并只在两端实际存在的字段交集上生成默认映射。
        """

        source_metadata = _metadata(None, "datasmart_e2e_platform_orders")
        target_metadata = _metadata("datasmart_e2e", "orders_platform_clean")
        configuration = _full_configuration()
        configuration["objectMappings"] = [
            {
                "sourceSchemaName": "datasmart_e2e",
                "sourceObjectName": "datasmart_e2e_platform_orders",
                "targetSchemaName": "datasmart_e2e",
                "targetObjectName": "orders_platform_clean",
            }
        ]

        invalid_result = DataSyncSpecialistAgent(
            _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        ).execute(
            _request(
                context={
                    "sourceMetadata": source_metadata,
                    "targetMetadata": target_metadata,
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, invalid_result.status)
        self.assertIn("sourceTableMetadata", invalid_result.required_input_fields)
        self.assertIn(
            "SOURCE_TABLE_METADATA_REQUIRED",
            invalid_result.structured_output["validationIssueCodes"],
        )

        configuration["objectMappings"][0]["sourceSchemaName"] = ""
        valid_result = DataSyncSpecialistAgent(
            _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        ).execute(
            _request(
                context={
                    "sourceMetadata": source_metadata,
                    "targetMetadata": target_metadata,
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, valid_result.status)
        self.assertIsNone(valid_result.structured_output["objectMappings"][0]["sourceSchemaName"])
        self.assertTrue(valid_result.structured_output["objectMappings"][0]["fieldMappings"])

    def test_realtime_without_write_strategy_defaults_to_merge_semantics(self) -> None:
        configuration = _full_configuration()
        configuration["syncMode"] = "REAL_TIME"
        configuration.pop("writeStrategy")
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        context = {
            "sourceMetadata": _metadata(None, "customer"),
            "targetMetadata": _metadata("public", "customer"),
        }

        result = DataSyncSpecialistAgent(model).execute(_request(context=context))

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("CDC_STREAMING", result.structured_output["syncMode"])
        self.assertEqual("UPDATE", result.structured_output["writeStrategy"])
        self.assertEqual("MERGE", result.structured_output["writeMode"])
        self.assertIsNone(result.structured_output["scheduleConfig"])

    def test_preserves_explicit_field_mapping_and_complex_where_expression(self) -> None:
        """显式配置应被保留，复杂 WHERE 只做文本传递而不被错误简化。

        WHERE 条件可能包含子查询、OR、括号和数据库函数。同步规划 Agent 的职责是把用户已经
        确认的表达式放入对应对象映射，而不是在这里用脆弱的正则表达式重写 SQL；真正的方言解析
        和可执行性检查应由后续预检查工具完成。
        """

        configuration = _full_configuration()
        configuration["objectMappings"][0].update(
            {
                "whereCondition": (
                    "status = 'READY' OR (id IN (SELECT customer_id FROM allow_list) "
                    "AND lower(name) LIKE 'a%')"
                ),
                "fieldMappings": [
                    {"sourceField": "id", "targetField": "id", "syncEnabled": True},
                    {"sourceField": "name", "targetField": "name", "syncEnabled": True},
                ],
            }
        )
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        mapping = result.structured_output["objectMappings"][0]
        self.assertEqual(configuration["objectMappings"][0]["whereCondition"], mapping["whereCondition"])
        self.assertEqual("EXPRESSION", mapping["whereMode"])
        self.assertEqual("EXPLICIT_MODEL_PROPOSAL", mapping["fieldMappingMode"])
        self.assertEqual(["id", "name"], [item["sourceField"] for item in mapping["fieldMappings"]])
        self.assertFalse(any(item["inferred"] for item in mapping["fieldMappings"]))

    def test_user_reviewed_baseline_wins_over_model_task_mode_sql_mapping_and_where_tampering(self) -> None:
        """模型试图覆盖用户审核结果时，最终草案必须逐项回到用户基线。

        这条测试故意让模型同时篡改任务名、同步模式、写入策略、SQL、目标表、字段目标列、
        字段开关和 WHERE。DATA_SYNC_AGENT 可以继续使用模型补充缺项，但不允许把这些已经
        由用户审核过的值替换掉；最终输出还要保留字段类型/主键等手工配置元数据。
        """

        baseline = {
            "taskName": "reviewed-customer-sql",
            # 即使 handoff 中存在用户页面传来的数字，也必须由结构化事实覆盖，不能把它当作
            # DATA_SYNC_AGENT 可以自行信任的数据源选择。
            "sourceDatasourceId": 9001,
            "targetDatasourceId": 9002,
            "syncMode": "CUSTOM_SQL_QUERY",
            "writeStrategy": "UPDATE",
            "customSqlText": "SELECT id, name FROM customer WHERE status = 'READY'",
            "objectMappings": [
                {
                    "objectKey": "customer-source-to-target",
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "customer",
                    "whereCondition": "status = 'READY' OR (id IN (SELECT customer_id FROM allow_list))",
                    "fieldMappings": [
                        {
                            "sourceField": "id",
                            "sourceType": "BIGINT",
                            "targetField": "id",
                            "targetType": "BIGINT",
                            "primaryKey": True,
                            "syncEnabled": True,
                            "transform": "identity",
                        },
                    ],
                },
            ],
        }
        model_configuration = {
            "taskName": "model-replaced-name",
            "syncMode": "FULL",
            "writeStrategy": "INSERT",
            "customSqlText": "SELECT id FROM another_table",
            "objectMappings": [
                {
                    "objectKey": "customer-source-to-target",
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "wrong_target",
                    "whereCondition": "1 = 1",
                    "fieldMappings": [
                        {
                            "sourceField": "id",
                            "targetField": "wrong_id",
                            "syncEnabled": False,
                        },
                    ],
                },
            ],
        }
        model = _PlanningModel(SyncPlanningModelOutput(configuration=model_configuration))

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                    "dataSyncRequest": baseline,
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        output = result.structured_output
        self.assertEqual("reviewed-customer-sql", output["taskName"])
        self.assertEqual(27, output["sourceDatasourceId"])
        self.assertEqual(28, output["targetDatasourceId"])
        self.assertEqual("CUSTOM_SQL_QUERY", output["syncMode"])
        self.assertEqual("UPDATE", output["writeStrategy"])
        self.assertEqual(baseline["customSqlText"], output["customSqlText"])
        mapping = output["objectMappings"][0]
        self.assertEqual("customer-source-to-target", mapping["objectKey"])
        self.assertEqual("customer", mapping["targetObjectName"])
        self.assertEqual(baseline["objectMappings"][0]["whereCondition"], mapping["whereCondition"])
        field = mapping["fieldMappings"][0]
        self.assertEqual("id", field["targetField"])
        self.assertTrue(field["syncEnabled"])
        self.assertTrue(field["primaryKey"])
        self.assertEqual("BIGINT", field["targetType"])
        self.assertEqual("identity", field["transform"])
        self.assertTrue(output["userReviewedBaselineApplied"])
        self.assertIn(
            "MODEL_CONFIGURATION_CONFLICT_WITH_USER_BASELINE",
            output["modelGovernanceIssueCodes"],
        )
        self.assertNotIn(
            "MODEL_CONFIGURATION_CONFLICT_WITH_USER_BASELINE",
            output["validationIssueCodes"],
        )
        self.assertTrue(output["userReviewedBaselineConflictFields"])

    def test_user_reviewed_baseline_is_restored_when_model_omits_reviewed_values(self) -> None:
        """模型只返回一个不完整建议时，用户已审核的 SQL、模式、映射和 WHERE 不能丢失。"""

        baseline = {
            "taskName": "reviewed-full-sync",
            "syncMode": "CUSTOM_SQL_QUERY",
            "writeStrategy": "INSERT",
            "customSqlText": "SELECT id, name FROM customer",
            "objectMappings": [
                {
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "customer",
                    # 空字符串是用户确认“没有 WHERE”，模型不能自行添加过滤条件。
                    "whereCondition": "",
                    "fieldMappings": [
                        {"sourceField": "id", "targetField": "id", "syncEnabled": True},
                        {"sourceField": "name", "targetField": "name", "syncEnabled": True},
                    ],
                },
            ],
        }
        model = _PlanningModel(
            SyncPlanningModelOutput(
                configuration={"taskName": "model-only-suggestion"},
            )
        )

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                    "dataSyncRequest": baseline,
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        output = result.structured_output
        self.assertEqual("reviewed-full-sync", output["taskName"])
        self.assertEqual("CUSTOM_SQL_QUERY", output["syncMode"])
        self.assertEqual("INSERT", output["writeStrategy"])
        self.assertEqual(baseline["customSqlText"], output["customSqlText"])
        mapping = output["objectMappings"][0]
        self.assertEqual("customer", mapping["sourceObjectName"])
        self.assertEqual("customer", mapping["targetObjectName"])
        self.assertEqual("NO_FILTER", mapping["whereMode"])
        self.assertEqual(["id", "name"], [field["sourceField"] for field in mapping["fieldMappings"]])
        self.assertTrue(output["userReviewedBaselineApplied"])
        self.assertIn("dataSyncRequest.customSqlText", output["userReviewedBaselineConflictFields"])
        self.assertIn("dataSyncRequest.objectMappings[0]", output["userReviewedBaselineConflictFields"])

    def test_user_reviewed_schedule_cannot_be_replaced_by_model(self) -> None:
        """定期任务的执行周期同样属于审核基线，模型不得改成另一个周期。"""

        schedule = {"cron": "0 0 * * *", "startTime": "2026-08-05T00:00:00+08:00"}
        baseline = {
            "taskName": "reviewed-scheduled-sync",
            "syncMode": "SCHEDULED_FULL",
            "writeStrategy": "INSERT",
            "scheduleConfig": schedule,
            "objectMappings": [
                {
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "customer",
                    "whereCondition": "status = 'READY'",
                },
            ],
        }
        model = _PlanningModel(
            SyncPlanningModelOutput(
                configuration={
                    "taskName": "model-schedule",
                    "syncMode": "SCHEDULED_FULL",
                    "writeStrategy": "INSERT",
                    "scheduleConfig": {"cron": "*/5 * * * *"},
                    "objectMappings": [
                        {
                            "sourceObjectName": "customer",
                            "targetSchemaName": "public",
                            "targetObjectName": "customer",
                            "whereCondition": "1 = 1",
                        },
                    ],
                }
            )
        )

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                    "dataSyncRequest": baseline,
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(schedule, result.structured_output["scheduleConfig"])
        self.assertEqual("status = 'READY'", result.structured_output["objectMappings"][0]["whereCondition"])
        self.assertIn("dataSyncRequest.scheduleConfig", result.structured_output["userReviewedBaselineConflictFields"])

    def test_scheduled_mode_keeps_schedule_and_does_not_leak_it_to_immediate_modes(self) -> None:
        """定期模式必须保留调度配置，而全量模式必须明确没有调度配置。"""

        scheduled = _full_configuration()
        scheduled["syncMode"] = "SCHEDULED_FULL"
        scheduled["scheduleConfig"] = {"cron": "0 0 * * *", "startTime": "2026-08-05T00:00:00+08:00"}
        model = _PlanningModel(SyncPlanningModelOutput(configuration=scheduled))

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(scheduled["scheduleConfig"], result.structured_output["scheduleConfig"])

        immediate = _full_configuration()
        immediate["scheduleConfig"] = {"cron": "0 0 * * *"}
        immediate_model = _PlanningModel(SyncPlanningModelOutput(configuration=immediate))
        immediate_result = DataSyncSpecialistAgent(immediate_model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )
        self.assertEqual(SpecialistTurnStatus.COMPLETED, immediate_result.status)
        self.assertIsNone(immediate_result.structured_output["scheduleConfig"])

    def test_waits_for_real_table_metadata_instead_of_trusting_model_field_claims(self) -> None:
        configuration = _full_configuration()
        configuration["objectMappings"][0]["fieldMappings"] = [
            {"sourceField": "id", "targetField": "id", "syncEnabled": True}
        ]
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(_request(context={}))

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIn("sourceTableMetadata", result.required_input_fields)
        self.assertIn("targetTableMetadata", result.required_input_fields)
        self.assertIn("SOURCE_TABLE_METADATA_REQUIRED", result.structured_output["validationIssueCodes"])
        self.assertFalse(result.structured_output["executed"])

    def test_sql_mode_without_sql_waits_for_exact_missing_field(self) -> None:
        configuration = _full_configuration()
        configuration["syncMode"] = "CUSTOM_SQL_QUERY"
        configuration["objectMappings"][0].pop("sourceObjectName")
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        context = {
            "sourceMetadata": _metadata(None, "sql_result"),
            "targetMetadata": _metadata("public", "customer"),
        }

        result = DataSyncSpecialistAgent(model).execute(_request(context=context))

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIn("customSqlText", result.required_input_fields)
        self.assertNotIn("objectMappings[0].sourceObjectName", result.required_input_fields)
        self.assertEqual("CUSTOM_SQL_QUERY", result.structured_output["syncMode"])

    def test_scheduled_mode_requires_schedule_configuration(self) -> None:
        configuration = _full_configuration()
        configuration["syncMode"] = "SCHEDULED_FULL"
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))
        context = {
            "sourceMetadata": _metadata(None, "customer"),
            "targetMetadata": _metadata("public", "customer"),
        }

        result = DataSyncSpecialistAgent(model).execute(_request(context=context))

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIn("scheduleConfig", result.required_input_fields)
        self.assertIn("SCHEDULE_CONFIG_REQUIRED", result.structured_output["validationIssueCodes"])

    def test_quarantines_nonbinding_publish_suggestion_without_discarding_valid_draft(self) -> None:
        """动作建议没有参数且不会执行时应隔离，不能误伤已验证的配置草案。"""

        model = _PlanningModel(
            SyncPlanningModelOutput(
                configuration=_full_configuration(),
                requested_tool_names=("sync.task.publish",),
                requested_actions=("PUBLISH_AND_RUN",),
                invocation_summary={"modelName": "test-sync-planner"},
            )
        )
        events: list[Mapping[str, Any]] = []

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            ),
            events.append,
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertIsNone(result.error_code)
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])
        self.assertEqual(1, result.structured_output["quarantinedToolSuggestionCount"])
        self.assertEqual(1, result.structured_output["quarantinedActionSuggestionCount"])
        self.assertIn(
            "MODEL_SIDE_EFFECT_SUGGESTIONS_QUARANTINED",
            result.structured_output["modelGovernanceIssueCodes"],
        )
        self.assertNotIn(
            "MODEL_SIDE_EFFECT_SUGGESTIONS_QUARANTINED",
            result.structured_output["validationIssueCodes"],
        )
        self.assertEqual("CONFIGURATION_DRAFT_COMPLETED", events[-1]["action"])

    def test_rejects_nested_model_forged_side_effect(self) -> None:
        """副作用字段即使藏在 dataSyncRequest 内，也必须在草案输出前被拒绝。"""

        configuration = _full_configuration()
        configuration["dataSyncRequest"] = {
            **configuration,
            "taskId": 9001,
            "publish": True,
        }
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(_request())

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("DATA_SYNC_SPECIALIST_SIDE_EFFECT_REJECTED", result.error_code)
        self.assertEqual(
            ("taskid", "publish"),
            result.structured_output["activeConfigurationControlFields"],
        )
        self.assertEqual(2, result.structured_output["quarantinedConfigurationFieldCount"])
        self.assertNotIn("9001", str(result.structured_output))
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])

    def test_quarantines_explicit_inactive_side_effect_markers(self) -> None:
        """模型明确声明“未执行”时应丢弃字段，而不是误伤已校验的业务草案。

        真实 Provider 经常为了强调遵守 system instruction，在 ``configuration`` 中额外返回
        ``persisted: false``、``published: false`` 或 ``executed: false``。这些标记不包含
        能力、ID 或命令，并且确定性白名单不会把它们复制到任务配置，因此可以低敏记账后继续。
        """

        configuration = _full_configuration()
        configuration.update(
            {
                "persisted": False,
                "published": False,
                "executed": "NOT_EXECUTED",
                "toolCalls": [],
            }
        )
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertIsNone(result.error_code)
        self.assertEqual(4, result.structured_output["quarantinedConfigurationFieldCount"])
        self.assertIn(
            "MODEL_INACTIVE_SIDE_EFFECT_FIELDS_QUARANTINED",
            result.structured_output["modelGovernanceIssueCodes"],
        )
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])

    def test_quarantines_inactive_execution_summary_without_blocking_valid_draft(self) -> None:
        """模型把“尚未执行”包装成 execution 摘要时，不应阻断合法同步草案。

        通用 Provider 有时会把 system instruction 的边界说明结构化为对象，而不是返回单个
        ``executed: false``。该摘要不具备任何业务能力，最终配置白名单也不会复制它；本测试
        确保这种兼容处理不会放宽真正的执行权限。
        """

        configuration = _full_configuration()
        configuration["execution"] = {
            "status": "NOT_STARTED",
            "taskId": None,
            "executionId": None,
            "executed": False,
            "summary": "planning only",
        }
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertIsNone(result.error_code)
        self.assertEqual(1, result.structured_output["quarantinedConfigurationFieldCount"])
        self.assertIn(
            "MODEL_INACTIVE_SIDE_EFFECT_FIELDS_QUARANTINED",
            result.structured_output["modelGovernanceIssueCodes"],
        )
        self.assertNotIn("execution", result.structured_output)
        self.assertFalse(result.structured_output["executed"])

    def test_rejects_active_execution_summary_even_when_nested(self) -> None:
        """execution 摘要包含运行状态或真实 ID 时，仍必须保持 fail-closed。"""

        configuration = _full_configuration()
        configuration["metadata"] = {
            "execution": {
                "status": "RUNNING",
                "executionId": "9001",
            }
        }
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(_request())

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("DATA_SYNC_SPECIALIST_SIDE_EFFECT_REJECTED", result.error_code)
        self.assertEqual(("execution",), result.structured_output["activeConfigurationControlFields"])
        self.assertNotIn("9001", str(result.structured_output))

    def test_discovers_both_metadata_sides_from_datasource_dependency_and_emits_activities(self) -> None:
        """无预加载元数据时，DATA_SYNC_AGENT 必须先读取双方真实结构再做同名映射。"""

        model = _PlanningModel(SyncPlanningModelOutput(configuration=_full_configuration()))
        metadata_tool = _MetadataTool()
        events: list[Mapping[str, Any]] = []
        context = {
            "sourceDatasourceId": None,
            "targetDatasourceId": None,
            "sourceConnectorType": None,
            "targetConnectorType": None,
            "dependencyResults": {
                "DATASOURCE_AGENT": {
                    "structuredOutput": {
                        "sourceDatasourceId": 27,
                        "targetDatasourceId": 28,
                        "resolutions": {
                            "source": {
                                "selectedDatasourceId": 27,
                                "candidates": [
                                    {"datasourceId": 27, "connectorType": "MYSQL"}
                                ],
                            },
                            "target": {
                                "selectedDatasourceId": 28,
                                "candidates": [
                                    {"datasourceId": 28, "connectorType": "POSTGRESQL"}
                                ],
                            },
                        },
                    }
                }
            },
        }

        result = DataSyncSpecialistAgent(model, metadata_discovery_tool=metadata_tool).execute(
            _request(context=context), events.append
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual([27, 28], [item.datasource_id for item in metadata_tool.requests])
        self.assertEqual(["SOURCE", "TARGET"], [item.side for item in metadata_tool.requests])
        self.assertTrue(all(item.include_columns for item in metadata_tool.requests))
        self.assertTrue(all(item.scope_level == "PROJECT" for item in metadata_tool.requests))
        self.assertTrue(all(item.authorized_project_id == "101" for item in metadata_tool.requests))
        self.assertEqual(
            ["SUCCEEDED", "SUCCEEDED"],
            [item.status for item in result.tool_activities],
        )
        self.assertEqual(
            ["METADATA_DISCOVERY_STARTED", "METADATA_DISCOVERY_COMPLETED",
             "METADATA_DISCOVERY_STARTED", "METADATA_DISCOVERY_COMPLETED"],
            [event["action"] for event in events if event.get("action", "").startswith("METADATA_DISCOVERY")],
        )
        fields = result.structured_output["objectMappings"][0]["fieldMappings"]
        self.assertEqual(["id", "name"], [item["sourceField"] for item in fields])

    def test_does_not_guess_datasource_ids_from_model_or_objective(self) -> None:
        """上下文没有 ID 时，模型返回的数字不能越过可信数据源边界。"""

        model_configuration = _full_configuration()
        model_configuration["sourceDatasourceId"] = 9001
        model_configuration["targetDatasourceId"] = 9002
        model = _PlanningModel(SyncPlanningModelOutput(configuration=model_configuration))
        metadata_tool = _MetadataTool()

        result = DataSyncSpecialistAgent(model, metadata_discovery_tool=metadata_tool).execute(
            _request(
                context={
                    "sourceDatasourceId": None,
                    "targetDatasourceId": None,
                    "sourceConnectorType": None,
                    "targetConnectorType": None,
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIn("sourceDatasourceId", result.required_input_fields)
        self.assertIn("targetDatasourceId", result.required_input_fields)
        self.assertIsNone(result.structured_output["sourceDatasourceId"])
        self.assertIsNone(result.structured_output["targetDatasourceId"])
        self.assertEqual([], metadata_tool.requests)

    def test_rejects_model_datasource_id_conflict_with_structured_fact(self) -> None:
        """模型数字与已授权数据源不一致时必须停在人工补参/纠偏，而不能自动替换事实。"""

        configuration = _full_configuration()
        configuration["sourceDatasourceId"] = 9001
        model = _PlanningModel(SyncPlanningModelOutput(configuration=configuration))

        result = DataSyncSpecialistAgent(model).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIn("SOURCE_DATASOURCE_ID_CONFLICT", result.structured_output["validationIssueCodes"])
        self.assertIn("sourceDatasourceId", result.required_input_fields)

    def test_metadata_tool_failure_is_stable_and_preserves_failed_activity(self) -> None:
        """元数据 HTTP 超时不能被吞成“映射为空”，必须返回稳定失败和工具活动。"""

        model = _PlanningModel(SyncPlanningModelOutput(configuration=_full_configuration()))
        metadata_tool = _MetadataTool(fail_side="source")
        events: list[Mapping[str, Any]] = []

        result = DataSyncSpecialistAgent(model, metadata_discovery_tool=metadata_tool).execute(
            _request(context={"sourceMetadata": None, "targetMetadata": None}), events.append
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("DATA_SYNC_METADATA_SYNC_METADATA_HTTP_TIMEOUT", result.error_code)
        self.assertEqual("FAILED", result.tool_activities[0].status)
        metadata_failure = next(
            event for event in events if event.get("action") == "METADATA_DISCOVERY_COMPLETED"
        )
        self.assertEqual("FAILED", metadata_failure["status"])

    def test_model_failure_preserves_low_sensitive_reason_code(self) -> None:
        """独立规划模型失败时应告诉用户失败类别，而不是只返回通用错误码。"""

        result = DataSyncSpecialistAgent(_FailingPlanningModel()).execute(
            _request(
                context={
                    "sourceMetadata": _metadata(None, "customer"),
                    "targetMetadata": _metadata("public", "customer"),
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("DATA_SYNC_SPECIALIST_MODEL_FAILED", result.error_code)
        self.assertEqual("MODEL_TIMEOUT", result.structured_output["modelFailureReasonCode"])
        self.assertEqual("MODEL_PROVIDER_TRANSPORT", result.structured_output["modelFailureSource"])
        self.assertNotIn("provider.invalid", str(result.structured_output))
        self.assertNotIn("secret", str(result.structured_output))


if __name__ == "__main__":
    unittest.main()
