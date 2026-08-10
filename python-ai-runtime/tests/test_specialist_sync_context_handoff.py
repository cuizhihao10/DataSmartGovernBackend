"""DATA_SYNC_AGENT 瞬时配置 handoff 的安全与完整性测试。"""

from datasmart_ai_runtime.api.agent.plan_response import _specialist_base_context
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest


def _empty_plan() -> AgentPlan:
    """构造不含工具副作用的最小计划，只用于验证专业 Agent 上下文边界。"""

    return AgentPlan(
        request_id="request-specialist-sync-context",
        selected_route=None,
        state_trace=(),
        tool_plans=(),
        requires_human_approval=False,
        response_summary="test",
    )


def test_specialist_context_keeps_reviewed_mapping_where_sql_and_schedule() -> None:
    """用户已经在高级配置中确认的同步字段必须完整交给同步规划 Agent。"""

    request = AgentRequest(
        tenant_id="10",
        project_id="101",
        actor_id="1001",
        objective="创建一个定期 SQL 同步任务",
        variables={
            "dataSyncRequest": {
                "taskName": "customer-sync",
                "sourceDatasourceId": 27,
                "targetDatasourceId": 28,
                "syncMode": "SCHEDULED_FULL",
                "writeStrategy": "UPDATE",
                "scheduleConfig": '{"cron":"0 0 * * * ?"}',
                "customSqlText": "select id, name from customer where enabled = 1",
                "objectMappings": [{
                    "sourceSchemaName": "sales",
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "customer",
                    "whereCondition": "enabled = 1 OR created_at >= CURRENT_DATE",
                    "fieldMappings": [{
                        "sourceField": "id",
                        "sourceType": "BIGINT",
                        "targetField": "id",
                        "targetType": "BIGINT",
                        "primaryKey": True,
                        "syncEnabled": True,
                    }],
                }],
            },
        },
    )

    context = _specialist_base_context(request, _empty_plan())

    configuration = context["dataSyncRequest"]
    assert configuration["taskName"] == "customer-sync"
    assert configuration["scheduleConfig"] == '{"cron":"0 0 * * * ?"}'
    assert configuration["customSqlText"].startswith("select id")
    assert configuration["objectMappings"][0]["whereCondition"].startswith("enabled = 1 OR")
    assert configuration["objectMappings"][0]["fieldMappings"][0]["primaryKey"] is True


def test_specialist_context_rejects_credentials_and_unknown_nested_fields() -> None:
    """同步业务配置可以传递，但密码、Token、JDBC 地址和未知嵌套对象必须被删除。"""

    request = AgentRequest(
        tenant_id="10",
        project_id="101",
        actor_id="1001",
        objective="同步 customer 表",
        variables={
            "dataSyncRequest": {
                "taskName": "customer-sync",
                "password": "must-not-leak",
                "accessToken": "must-not-leak",
                "jdbcUrl": "jdbc:mysql://secret-host/db",
                "internalControl": {"bypassApproval": True},
                "objectMappings": [{
                    "sourceObjectName": "customer",
                    "targetObjectName": "customer",
                    "credential": "must-not-leak",
                    "fieldMappings": [{
                        "sourceField": "id",
                        "targetField": "id",
                        "secretTransform": "must-not-leak",
                    }],
                }],
            },
        },
    )

    configuration = _specialist_base_context(request, _empty_plan())["dataSyncRequest"]

    assert "password" not in configuration
    assert "accessToken" not in configuration
    assert "jdbcUrl" not in configuration
    assert "internalControl" not in configuration
    assert "credential" not in configuration["objectMappings"][0]
    assert "secretTransform" not in configuration["objectMappings"][0]["fieldMappings"][0]
