"""图事实审批和受治理恢复的部署/E2E 静态合同回归。"""

from __future__ import annotations

import unittest
from pathlib import Path


class ConvergenceDeliveryContractTest(unittest.TestCase):
    """防止真实闭环在后续改动中退回宿主机修复或未受控基础设施配置。"""

    REPOSITORY_ROOT = Path(__file__).resolve().parents[2]

    def test_platform_e2e_never_repairs_business_rows_from_powershell(self) -> None:
        """故障脚本只能注入故障并观察结果，业务修复必须经过 Agent/Java 控制面。

        过去脚本使用 UPDATE 修改源表，再直接调用 retry/replay；这种做法即使最终数据正确，也无法证明
        模型决策、审批、幂等回执和 Java 副作用边界生效。本合同禁止这些具体回归点，并要求脚本委托给
        六 Agent Autopilot 黑盒链和 Java 目标结果断言。
        """

        script = (self.REPOSITORY_ROOT / "scripts" / "local-data-sync-platform-e2e.ps1").read_text(
            encoding="utf-8"
        )
        for forbidden in (
            "Repair-FailedShardSourceRows",
            "Repair-DirtySourceRow",
            "amount = ABS(amount)",
            "Repaired-Customer-7",
            "MANUAL_FIXED_AND_REPLAY",
        ):
            self.assertNotIn(forbidden, script)
        self.assertIn("Invoke-GovernedAutopilotRecoveryE2E", script)
        self.assertIn("local-six-agent-governed-e2e.ps1", script)
        self.assertIn("-EnableAutopilot", script)
        self.assertIn("Assert-GovernedAutopilotTargetResult", script)

    def test_compose_wires_durable_graph_fact_worker_and_neo4j(self) -> None:
        """本地完整部署必须具备 Kafka、PostgreSQL receipt、MinIO 和 Neo4j 四个真实边界。"""

        compose = (self.REPOSITORY_ROOT / "docker-compose.application.yml").read_text(encoding="utf-8")
        required = (
            "DATASMART_PERMISSION_GRAPH_FACT_EVENTS_DISPATCHER_ENABLED:",
            "DATASMART_GRAPH_FACT_WORKER_ENABLED:",
            "DATASMART_GRAPH_FACT_WORKER_TOPIC:",
            "DATASMART_GRAPH_FACT_RECEIPT_STORE: postgresql",
            "DATASMART_GRAPH_FACT_RECEIPT_POSTGRESQL_DSN:",
            "DATASMART_GRAPH_FACT_MINIO_ENDPOINT: http://minio:9000",
            "DATASMART_GRAPH_FACT_MINIO_BUCKET:",
            "DATASMART_GRAPH_RAG_PROVIDER:",
            "DATASMART_GRAPH_RAG_NEO4J_URI:",
            "PYTHON_RUNTIME_EXTRAS: api,rag,graph,kafka,redis,postgresql,object-store,mcp",
        )
        for text in required:
            self.assertIn(text, compose)

    def test_helm_injects_graph_fact_credentials_only_through_secret_env(self) -> None:
        """生产 values 只能声明 Secret key 引用，不能硬编码数据库、MinIO、审批或 Neo4j 凭据。"""

        values = (self.REPOSITORY_ROOT / "helm" / "datasmart-govern" / "values.yaml").read_text(
            encoding="utf-8"
        )
        required_secret_bindings = (
            "- name: DATASMART_GRAPH_FACT_RECEIPT_POSTGRESQL_DSN\n        key: graph-fact-receipt-postgresql-dsn",
            "- name: DATASMART_GRAPH_FACT_PERMISSION_SERVICE_TOKEN\n        key: agent-approval-fact-shared-token",
            "- name: DATASMART_GRAPH_FACT_MINIO_ACCESS_KEY\n        key: minio-access-key",
            "- name: DATASMART_GRAPH_FACT_MINIO_SECRET_KEY\n        key: minio-secret-key",
            "- name: DATASMART_GRAPH_RAG_NEO4J_PASSWORD\n        key: neo4j-password",
        )
        for binding in required_secret_bindings:
            self.assertIn(binding, values)
        self.assertIn('DATASMART_GRAPH_FACT_WORKER_ENABLED: "true"', values)
        self.assertIn("DATASMART_GRAPH_FACT_RECEIPT_STORE: postgresql", values)

    def test_container_package_gate_skips_test_compilation_but_not_main_build(self) -> None:
        """镜像打包门禁与全量测试门禁分离，避免 package 阶段错误依赖跨模块 test-jar。"""

        delivery_check = (self.REPOSITORY_ROOT / "scripts" / "containerized-delivery-check.ps1").read_text(
            encoding="utf-8"
        )
        self.assertIn('"-Dmaven.test.skip=true"', delivery_check)
        self.assertNotIn('"-DskipTests"', delivery_check)
        self.assertIn('"package"', delivery_check)
        self.assertIn('"BOOT-INF/"', delivery_check)

    def test_permission_outbox_dispatchers_do_not_compete_for_graph_events(self) -> None:
        """通用权限 dispatcher 必须排除图事件，保证专用 topic/重试配置拥有唯一发送职责。"""

        mapper = (
            self.REPOSITORY_ROOT
            / "permission-admin"
            / "src"
            / "main"
            / "java"
            / "com"
            / "czh"
            / "datasmart"
            / "govern"
            / "permission"
            / "mapper"
            / "PermissionEventOutboxMapper.java"
        ).read_text(encoding="utf-8")
        self.assertIn("event_type <> 'GRAPH_FACTS_APPROVED'", mapper)
        self.assertIn("event_type = 'GRAPH_FACTS_APPROVED'", mapper)

    def test_graph_event_id_has_forward_postgresql_migration_capacity(self) -> None:
        """审批 ID 加 SHA-256 的稳定 eventId 必须能进入存量 permission-admin 数据库。"""

        migration = (
            self.REPOSITORY_ROOT
            / "permission-admin"
            / "src"
            / "main"
            / "resources"
            / "db"
            / "migration"
            / "postgresql"
            / "permission-admin"
            / "V58__graph_fact_outbox_contract.sql"
        ).read_text(encoding="utf-8")
        self.assertIn("ALTER COLUMN event_id TYPE VARCHAR(256)", migration)

    def test_kafka_graph_worker_does_not_use_zero_consumer_timeout(self) -> None:
        """Kafka consumer 必须持续等待，不能因 timeout=0 静默退出后台线程。"""

        worker = (
            self.REPOSITORY_ROOT
            / "python-ai-runtime"
            / "src"
            / "datasmart_ai_runtime"
            / "services"
            / "rag"
            / "graph_approval_worker.py"
        ).read_text(encoding="utf-8")
        self.assertIn("consumer_timeout_ms=-1", worker)
        self.assertNotIn("consumer_timeout_ms=0", worker)

    def test_platform_agent_recovery_uses_agent_request_contract(self) -> None:
        """平台故障恢复脚本必须遵守 Python AgentRequest 的 snake_case 根级合同。

        applicationId 是业务图谱/RAG 的受信范围事实，应通过 Gateway 签名 Header 和 variables 传递；
        它不是 AgentRequest dataclass 字段。这个静态合同避免 E2E 在模型调用前因未知 camelCase 字段返回 400。
        """

        script = (self.REPOSITORY_ROOT / "scripts" / "local-data-sync-platform-e2e.ps1").read_text(
            encoding="utf-8"
        )
        self.assertIn("tenant_id = [string]$TenantId", script)
        self.assertIn("project_id = [string]$ProjectId", script)
        self.assertIn("actor_id = [string]$ActorId", script)
        self.assertIn('$agentHeaders["X-DataSmart-Project-Id"] = [string]$ProjectId', script)
        self.assertIn("$plan.model_interaction_summary", script)
        self.assertIn("$plan.tool_plans", script)
        self.assertIn("严格模式要求真实模型返回 SEARCH 或 SKIP", script)
        self.assertNotIn("            applicationId = $ApplicationId\n            projectId = $ProjectId\n            actorId = $ActorId\n            objective = $objective", script)


if __name__ == "__main__":
    unittest.main()
