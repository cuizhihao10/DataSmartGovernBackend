import os
import sqlite3
import sys
import tempfile
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.memory import AgentMemoryPlan, AgentMemoryScope, AgentMemoryType
from datasmart_ai_runtime.services.memory_write_components import (
    AgentMemoryWriteStoreSettings,
    build_memory_write_store_runtime,
    memory_write_store_diagnostics,
    memory_write_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory_write_governance import AgentMemoryWriteGovernanceService


class AgentMemoryWriteComponentsTest(unittest.TestCase):
    """记忆写入候选 store 运行时组装测试。

    该测试关注“配置如何变成真实 store”，不重复覆盖候选审批状态机。
    这样可以把职责拆清楚：
    - `test_memory_write_governance.py` 证明业务规则正确；
    - `test_memory_write_sql_store.py` 证明 SQL store 读写正确；
    - 本文件证明 Runtime 启动时能安全选择 in-memory/sqlite/mysql/fallback。
    """

    def test_default_settings_use_in_memory_store_for_local_runtime(self) -> None:
        """默认配置必须零依赖启动。

        本地学习和普通单元测试不应要求 MySQL、SQLite 文件或第三方驱动。
        因此未配置环境变量时应使用内存 store，并在诊断中明确 persistent=false。
        """

        settings = memory_write_store_settings_from_env({})
        runtime = build_memory_write_store_runtime(settings)
        diagnostics = memory_write_store_diagnostics(runtime)

        self.assertEqual("in-memory", settings.store_type)
        self.assertFalse(runtime.persistent)
        self.assertEqual("InMemoryAgentMemoryWriteCandidateStore", diagnostics["implementation"])

    def test_sqlite_store_can_persist_candidates_across_service_instances(self) -> None:
        """SQLite 模式应能验证“跨服务实例恢复候选”的产品语义。

        这里使用 SQLite 文件而不是内存库，是为了模拟 Python Runtime 重建治理服务后，
        仍能从同一个关系型 store 读取上一轮生成的候选。
        """

        with tempfile.TemporaryDirectory() as temp_dir:
            sqlite_path = os.path.join(temp_dir, "memory-candidates.sqlite3")
            settings = AgentMemoryWriteStoreSettings(store_type="sqlite", sqlite_path=sqlite_path)
            connections: list[sqlite3.Connection] = []

            def connection_factory(config: AgentMemoryWriteStoreSettings) -> sqlite3.Connection:
                connection = self._sqlite_connection_with_schema(config)
                connections.append(connection)
                return connection

            runtime = build_memory_write_store_runtime(
                settings,
                connection_factory=connection_factory,
            )
            service = AgentMemoryWriteGovernanceService(store=runtime.store)
            report = service.propose(
                request=self._request(),
                plan=self._plan(
                    ToolPlan(
                        tool_name="quality.rule.suggest",
                        reason="生成质量规则候选",
                        governance_hints={"memoryWritePolicy": "episodic"},
                    )
                ),
            )
            reloaded = AgentMemoryWriteGovernanceService(store=runtime.store).get(report.candidates[0].candidate_id)
            diagnostics = memory_write_store_diagnostics(runtime)
            for connection in connections:
                connection.close()

        self.assertIsNotNone(reloaded)
        self.assertEqual("sqlite", diagnostics["configuredType"])
        self.assertTrue(diagnostics["persistent"])

    def test_mysql_connection_failure_can_fail_open_to_in_memory_with_diagnostics(self) -> None:
        """MySQL 不可用且 fail-open=true 时，应回退内存并留下原因。

        这是开发环境友好策略：没有启动 MySQL 或没有安装驱动时，Agent 主链仍能继续联调。
        但诊断必须明确 fallback=true，避免用户误以为候选已经持久化。
        """

        settings = AgentMemoryWriteStoreSettings(
            store_type="mysql",
            mysql_dsn="mysql://root:secret@localhost:3306/datasmart?charset=utf8mb4",
            fail_open=True,
        )
        runtime = build_memory_write_store_runtime(
            settings,
            connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟 MySQL 不可用")),
        )
        diagnostics = memory_write_store_diagnostics(runtime)

        self.assertFalse(runtime.persistent)
        self.assertTrue(diagnostics["fallback"])
        self.assertIn("模拟 MySQL 不可用", diagnostics["fallbackReason"])
        self.assertNotIn("secret", diagnostics["mysql"]["dsn"])

    def test_mysql_connection_failure_can_fast_fail_for_production(self) -> None:
        """生产环境可设置 fail-open=false，让持久化异常直接阻断启动。"""

        settings = AgentMemoryWriteStoreSettings(
            store_type="mysql",
            mysql_dsn="host=localhost;user=root;password=secret;database=datasmart",
            fail_open=False,
        )

        with self.assertRaises(RuntimeError):
            build_memory_write_store_runtime(
                settings,
                connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟生产 MySQL 连接失败")),
            )

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请生成长期记忆候选",
            variables={"sessionId": "session-a"},
        )

    @staticmethod
    def _plan(*tool_plans: ToolPlan) -> AgentPlan:
        return AgentPlan(
            request_id="request-a",
            selected_route=None,
            state_trace=("plan_tools", "plan_memory"),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="已生成工具计划。",
            memory_plan=AgentMemoryPlan(
                writable_memory_types=(AgentMemoryType.EPISODIC,),
                default_scope=AgentMemoryScope.PROJECT,
                retention_days=30,
            ),
        )

    @staticmethod
    def _sqlite_connection_with_schema(settings: AgentMemoryWriteStoreSettings) -> sqlite3.Connection:
        """创建测试 SQLite 连接并显式初始化 schema。"""

        connection = sqlite3.connect(settings.sqlite_path)
        connection.row_factory = sqlite3.Row
        connection.executescript(
            """
            CREATE TABLE agent_memory_write_candidate (
                candidate_id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                memory_type TEXT NOT NULL,
                scope TEXT NOT NULL,
                status TEXT NOT NULL,
                title TEXT NOT NULL,
                content_summary TEXT NOT NULL,
                source TEXT NOT NULL,
                source_tool_name TEXT,
                source_status TEXT,
                source_audit_id TEXT,
                source_run_id TEXT,
                output_ref TEXT,
                approval_required INTEGER NOT NULL,
                retention_days INTEGER NOT NULL,
                sensitivity_level TEXT NOT NULL,
                privacy_notes_json TEXT,
                candidate_version INTEGER NOT NULL,
                idempotency_key TEXT,
                created_at TEXT,
                decided_at TEXT,
                decided_by TEXT,
                decision_reason TEXT,
                attributes_json TEXT
            );
            CREATE TABLE agent_memory_write_candidate_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                candidate_id TEXT NOT NULL,
                tenant_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                operator_id TEXT NOT NULL,
                action TEXT NOT NULL,
                previous_status TEXT NOT NULL,
                next_status TEXT NOT NULL,
                reason TEXT NOT NULL,
                candidate_version INTEGER NOT NULL,
                decided_at TEXT,
                create_time TEXT
            );
            """
        )
        return connection


if __name__ == "__main__":
    unittest.main()
