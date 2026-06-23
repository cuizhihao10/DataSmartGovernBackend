import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    COMMAND_WORKER_LEASE_PAYLOAD_POLICY,
    CommandWorkerLeaseManager,
    CommandWorkerLeaseRequest,
    CommandWorkerLeaseState,
    InMemoryCommandWorkerLeaseStore,
)


class MutableClock:
    """可控时钟。

    租约测试不能依赖真实时间睡眠，否则用例会慢且不稳定。这个小替身让我们在毫秒级推进时间，从而验证过期抢占、
    续租延长和释放后重新领取等状态流转。
    """

    def __init__(self, now_ms: int = 1_000_000) -> None:
        self.now_ms = now_ms

    def __call__(self) -> int:
        return self.now_ms

    def advance(self, milliseconds: int) -> None:
        self.now_ms += milliseconds


class CommandWorkerLeaseTest(unittest.TestCase):
    """命令 worker 租约与 fencing token 合同测试。

    这组测试保护的是“真实命令执行前的并发护栏”，不是 shell runner 本身：
    - 同一 command 在租约有效期内只能由一个 worker 持有；
    - 同一 worker 重试领取要幂等返回原 token；
    - 不同 worker 不能看到对方 token；
    - 续租/释放必须同时匹配 worker 身份与 fencing token；
    - 过期或释放后重新领取必须提升 leaseVersion 并生成新 token；
    - 任何摘要都不能夹带命令行、stdout/stderr、SQL、prompt、payload、URL 或凭据。
    """

    def setUp(self) -> None:
        self.clock = MutableClock()
        self.manager = CommandWorkerLeaseManager(
            InMemoryCommandWorkerLeaseStore(clock_ms=self.clock)
        )

    def test_first_acquire_returns_fencing_token(self) -> None:
        """首次领取成功时，worker 应获得低敏 fencingToken 和 leaseVersion。"""

        result = self.manager.acquire(self._request())
        summary = result.to_summary()

        self.assertTrue(result.acquired)
        self.assertEqual(CommandWorkerLeaseState.ACQUIRED, result.state)
        self.assertEqual(COMMAND_WORKER_LEASE_PAYLOAD_POLICY, summary["payloadPolicy"])
        self.assertEqual(1, summary["leaseVersion"])
        self.assertIn("fencingToken", summary)
        self.assertTrue(summary["fencingToken"].startswith("cmd-lease:1:"))
        self.assertEqual(self.clock.now_ms + 60_000, summary["expiresAtMs"])

    def test_same_executor_acquire_is_idempotent(self) -> None:
        """同一个 worker 重试领取不应生成第二个 token，避免同一执行事实被误拆成两次。"""

        first = self.manager.acquire(self._request())
        second = self.manager.acquire(self._request())

        self.assertTrue(second.acquired)
        self.assertEqual(CommandWorkerLeaseState.ALREADY_HELD_BY_CALLER, second.state)
        self.assertEqual(first.fencing_token, second.fencing_token)
        self.assertEqual(first.lease_version, second.lease_version)

    def test_other_executor_is_blocked_without_token_leak(self) -> None:
        """不同 worker 抢同一 command 时必须被拒绝，且不能看到当前持有者的 token。"""

        first = self.manager.acquire(self._request(executor_id="worker-a"))
        blocked = self.manager.acquire(self._request(executor_id="worker-b"))
        summary = blocked.to_summary()

        self.assertTrue(first.acquired)
        self.assertFalse(blocked.acquired)
        self.assertEqual(CommandWorkerLeaseState.ALREADY_HELD_BY_OTHER, blocked.state)
        self.assertNotIn("fencingToken", summary)
        self.assertEqual(1, summary["leaseVersion"])

    def test_renew_extends_expiry_and_wrong_token_is_rejected(self) -> None:
        """续租必须带正确 token；错误 token 不能延长租约，也不能继续写回 receipt。"""

        acquired = self.manager.acquire(self._request(lease_ttl_seconds=10))
        self.clock.advance(5_000)

        rejected = self.manager.renew(self._request(lease_ttl_seconds=30), "cmd-lease:999:wrong")
        renewed = self.manager.renew(self._request(lease_ttl_seconds=30), acquired.fencing_token or "")

        self.assertFalse(rejected.acquired)
        self.assertEqual(CommandWorkerLeaseState.REJECTED, rejected.state)
        self.assertTrue(renewed.acquired)
        self.assertEqual(CommandWorkerLeaseState.RENEWED, renewed.state)
        self.assertEqual(acquired.fencing_token, renewed.fencing_token)
        self.assertEqual(self.clock.now_ms + 30_000, renewed.expires_at_ms)

    def test_release_allows_next_executor_to_acquire_new_version(self) -> None:
        """释放成功后，其他 worker 可以重新领取，并获得更高版本的新 token。"""

        acquired = self.manager.acquire(self._request(executor_id="worker-a"))
        released = self.manager.release(
            self._request(executor_id="worker-a"),
            acquired.fencing_token or "",
            release_reason="WORKER_FINISHED",
        )
        reacquired = self.manager.acquire(self._request(executor_id="worker-b"))

        self.assertEqual(CommandWorkerLeaseState.RELEASED, released.state)
        self.assertFalse(released.acquired)
        self.assertTrue(reacquired.acquired)
        self.assertEqual(CommandWorkerLeaseState.ACQUIRED, reacquired.state)
        self.assertEqual(2, reacquired.lease_version)
        self.assertNotEqual(acquired.fencing_token, reacquired.fencing_token)

    def test_expired_lease_can_be_acquired_by_another_executor(self) -> None:
        """租约过期后，新 worker 可抢占；旧 worker 的 token 续租会失败。"""

        old = self.manager.acquire(self._request(executor_id="worker-a", lease_ttl_seconds=2))
        self.clock.advance(2_001)

        expired_renew = self.manager.renew(
            self._request(executor_id="worker-a", lease_ttl_seconds=30),
            old.fencing_token or "",
        )
        new = self.manager.acquire(self._request(executor_id="worker-b"))

        self.assertEqual(CommandWorkerLeaseState.EXPIRED, expired_renew.state)
        self.assertFalse(expired_renew.acquired)
        self.assertTrue(new.acquired)
        self.assertEqual(2, new.lease_version)
        self.assertNotEqual(old.fencing_token, new.fencing_token)

    def test_sensitive_identifiers_and_release_reason_are_rejected(self) -> None:
        """租约合同不能被拿来承载 URL、输出、payload、prompt 或凭据片段。"""

        with self.assertRaises(ValueError):
            self.manager.acquire(self._request(executor_id="https://internal.example.local/worker"))

        acquired = self.manager.acquire(self._request())
        with self.assertRaises(ValueError):
            self.manager.release(
                self._request(),
                acquired.fencing_token or "",
                release_reason="stdout_SHOULD_NOT_ENTER_LEASE",
            )

    def test_summary_is_low_sensitive(self) -> None:
        """租约摘要只能包含低敏元数据，不能变成工具输入、输出或模型上下文导出。"""

        summary = self.manager.acquire(self._request()).to_summary()
        serialized = json.dumps(summary, ensure_ascii=False).lower()

        self.assertIn("cmd-lease:", serialized)
        self.assertNotIn("select * from", serialized)
        self.assertNotIn("stdout", serialized)
        self.assertNotIn("stderr", serialized)
        self.assertNotIn("prompt:", serialized)
        self.assertNotIn("payloadreference", serialized)
        self.assertNotIn("password", serialized)
        self.assertNotIn("https://", serialized)

    def _request(self, **overrides) -> CommandWorkerLeaseRequest:
        """生成默认低敏 command 租约请求。"""

        values = {
            "session_id": "session-command-001",
            "run_id": "run-command-001",
            "command_id": "cmd-worker-001",
            "executor_id": "python-command-worker-001",
            "tenant_id": 10,
            "project_id": 20,
            "actor_id": 30,
            "lease_ttl_seconds": 60,
        }
        values.update(overrides)
        return CommandWorkerLeaseRequest(**values)


if __name__ == "__main__":
    unittest.main()
