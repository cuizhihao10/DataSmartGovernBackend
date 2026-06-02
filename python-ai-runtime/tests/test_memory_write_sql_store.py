import os
import sqlite3
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory.memory_write_governance import (
    AgentMemoryWriteGovernanceService,
    approve_memory_write_candidate,
)
from datasmart_ai_runtime.services.memory.memory_write_sql_store import SqlAgentMemoryWriteCandidateStore


class AgentMemoryWriteSqlStoreTest(unittest.TestCase):
    """长期记忆候选 SQL store 测试。

    该测试独立于通用治理服务测试文件，是为了遵守单文件尽量低于 500 行的项目规范。
    这里使用标准库 sqlite3 验证关系型持久化语义，不代表生产环境选择 SQLite；
    生产环境仍按 MySQL 迁移脚本建表，并由部署配置选择 MySQL 驱动或 Java memory-service。
    """

    def test_sql_store_persists_candidate_and_decision_audit(self) -> None:
        """关系型 store 应同时持久化候选快照和审批审计。

        验证点：
        - 候选可以跨 service 实例重新读取；
        - approve/reject 会推进版本号；
        - 决策动作会写入审计表，保留前后状态、操作者和原因。
        """

        connection = sqlite3.connect(":memory:")
        connection.row_factory = sqlite3.Row
        self._create_schema(connection)
        store = SqlAgentMemoryWriteCandidateStore(connection)
        service = AgentMemoryWriteGovernanceService(store=store)
        report = service.propose(
            request=self._request(),
            plan=self._plan(
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="生成质量规则草案",
                    governance_hints={"memoryWritePolicy": "episodic"},
                )
            ),
        )

        candidate_id = report.candidates[0].candidate_id
        reloaded = AgentMemoryWriteGovernanceService(store=store).get(candidate_id)
        self.assertIsNotNone(reloaded)
        self.assertEqual(candidate_id, reloaded.candidate_id)
        self.assertEqual(1, reloaded.candidate_version)
        self.assertEqual("tenant:tenant-a:project:project-a", reloaded.workspace_key)
        self.assertEqual("memory:tenant:tenant-a:project:project-a", reloaded.memory_namespace)
        self.assertTrue(reloaded.idempotency_key)

        approved = approve_memory_write_candidate(
            service,
            candidate_id=candidate_id,
            operator_id="project-owner-a",
            reason="候选摘要已脱敏，可以进入项目经验库。",
        )
        audits = store.list_decision_audits(candidate_id)

        self.assertEqual(AgentMemoryWriteCandidateStatus.APPROVED, approved.status)
        self.assertEqual(2, approved.candidate_version)
        self.assertEqual(1, len(audits))
        self.assertEqual("draft", audits[0]["previous_status"])
        self.assertEqual("approved", audits[0]["next_status"])
        self.assertEqual("project-owner-a", audits[0]["operator_id"])

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请基于历史治理经验继续规划本次任务",
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
    def _create_schema(connection: sqlite3.Connection) -> None:
        """创建测试用关系型表结构。

        测试表只覆盖 Python store 读写需要的字段；生产 MySQL 脚本会额外补充索引、字符集、注释和时间列默认值。
        """

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
                workspace_key TEXT,
                memory_namespace TEXT,
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


if __name__ == "__main__":
    unittest.main()
