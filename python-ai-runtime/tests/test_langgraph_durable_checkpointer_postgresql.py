"""PostgreSQL LangGraph checkpointer 的数据库字段和事务边界回归。"""

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_execution import (
    LangGraphCheckpointEvent,
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    PostgresLangGraphCheckpointStore,
)


class _Cursor:
    def __init__(self, connection) -> None:
        self.connection = connection
        self.rows = []

    def execute(self, sql, params=()) -> None:
        self.connection.statements.append((sql, tuple(params)))
        if self.connection.fail_next:
            self.connection.fail_next = False
            raise RuntimeError("synthetic database failure")

    def fetchone(self):
        return self.rows[0] if self.rows else None

    def fetchall(self):
        return tuple(self.rows)

    def close(self) -> None:
        return None


class _Connection:
    def __init__(self) -> None:
        self.statements = []
        self.commit_count = 0
        self.rollback_count = 0
        self.fail_next = False

    def cursor(self):
        return _Cursor(self)

    def commit(self) -> None:
        self.commit_count += 1

    def rollback(self) -> None:
        self.rollback_count += 1


class PostgresLangGraphCheckpointerContractTest(unittest.TestCase):
    """确保 Runtime 不把领域层的长低敏名称直接写爆 PostgreSQL。"""

    def test_long_checkpoint_and_event_fields_are_bounded_with_digest(self) -> None:
        connection = _Connection()
        store = PostgresLangGraphCheckpointStore(connection)
        checkpoint = LangGraphDurableCheckpoint(
            checkpoint_id="checkpoint-" + "c" * 220,
            thread_id="thread-" + "t" * 220,
            graph_name="graph-" + "g" * 220,
            graph_version="version-" + "v" * 120,
            node_name="node-" + "n" * 220,
            status=LangGraphCheckpointStatus.RUNNING,
            state={"safe": True},
        )

        normalized_checkpoint = store.save_checkpoint(checkpoint)
        self.assertLessEqual(len(normalized_checkpoint.checkpoint_id), 160)
        self.assertLessEqual(len(normalized_checkpoint.thread_id), 160)
        self.assertLessEqual(len(normalized_checkpoint.node_name), 128)
        self.assertIn("~", normalized_checkpoint.node_name)

        event = store.append_event(
            LangGraphCheckpointEvent(
                event_id="event-" + "e" * 220,
                checkpoint_id=checkpoint.checkpoint_id,
                thread_id=checkpoint.thread_id,
                event_type="event-" + "a" * 220,
                node_name="node-" + "n" * 220,
                edge_name="edge-" + "d" * 220,
                sequence_number=1,
            )
        )
        self.assertLessEqual(len(event.event_id), 160)
        self.assertLessEqual(len(event.event_type), 96)
        self.assertLessEqual(len(event.node_name), 128)
        self.assertLessEqual(len(event.edge_name), 128)
        self.assertEqual(2, connection.commit_count)

        checkpoint_params = connection.statements[0][1]
        event_params = connection.statements[1][1]
        self.assertTrue(all(len(value) <= limit for value, limit in zip(
            checkpoint_params[:2], (160, 160)
        )))
        self.assertTrue(len(event_params[6]) <= 96)
        self.assertTrue(len(event_params[7]) <= 128)
        self.assertTrue(len(event_params[8]) <= 128)

    def test_database_error_rolls_back_before_exception_reaches_retry_boundary(self) -> None:
        connection = _Connection()
        connection.fail_next = True
        store = PostgresLangGraphCheckpointStore(connection)

        with self.assertRaises(RuntimeError):
            store.append_event(
                LangGraphCheckpointEvent(
                    event_id="event-1",
                    checkpoint_id="checkpoint-1",
                    thread_id="thread-1",
                    event_type="checkpoint_saved",
                    sequence_number=1,
                )
            )

        self.assertEqual(1, connection.rollback_count)


if __name__ == "__main__":
    unittest.main()
