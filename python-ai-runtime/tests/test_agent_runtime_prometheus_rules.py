import re
import unittest
from pathlib import Path


class AgentRuntimePrometheusRuleContractTest(unittest.TestCase):
    """验证 Java agent-runtime Prometheus 抓取和告警规则的最小契约。

    这些测试不替代 `promtool check rules`。它们的价值是固定本项目自己的生产约束：

    1. agent-runtime 必须有独立 scrape job，避免和 Python AI Runtime 共用 8090；
    2. pre-check 与 Skill 可见性索引关键告警名称需要稳定，便于 Alertmanager、Grafana 和中文 runbook 引用；
    3. 规则表达式不能选择 commandId、tenantId、runId、traceId 等高基数业务标签；
    4. DEFERRED、索引失败率和 fallback 查询比例告警必须用聚合后的 rate 表达，避免按实例、Store 或业务维度误判。
    """

    REPO_ROOT = Path(__file__).resolve().parents[2]
    ALERT_RULES = REPO_ROOT / "docker" / "prometheus" / "rules" / "agent-runtime-alerts.yml"
    PROMETHEUS_CONFIG = REPO_ROOT / "docker" / "prometheus" / "prometheus.yml"

    def _read_alert_rules(self) -> str:
        """按 UTF-8 读取规则文件，保护中文注释和 runbook 不被 Windows 编码破坏。"""

        return self.ALERT_RULES.read_text(encoding="utf-8")

    def test_agent_runtime_scrape_job_is_configured(self) -> None:
        """agent-runtime 必须使用独立端口和 Actuator Prometheus 路径。"""

        prometheus_config = self.PROMETHEUS_CONFIG.read_text(encoding="utf-8")

        self.assertIn("job_name: 'agent-runtime'", prometheus_config)
        self.assertIn("host.docker.internal:8091", prometheus_config)
        self.assertIn("metrics_path: '/actuator/prometheus'", prometheus_config)
        self.assertIn("/etc/prometheus/rules/*.yml", prometheus_config)

    def test_precheck_alert_names_are_stable(self) -> None:
        """pre-check 核心告警名称需要稳定。"""

        rules = self._read_alert_rules()
        expected_alerts = [
            "AgentRuntimeMetricsDown",
            "AgentRuntimeMetricsTargetMissing",
            "AgentRuntimeAsyncCommandPreCheckBlockedDetected",
            "AgentRuntimeAsyncCommandPreCheckDeferredRatioHigh",
            "AgentRuntimeAsyncCommandConfirmationExpired",
            "AgentRuntimeAsyncCommandRuntimeProtectionDeferred",
            "AgentRuntimeAsyncCommandPolicyItemMissing",
            "AgentRuntimeAsyncCommandPreCheckUnknownIssueCode",
        ]

        for alert_name in expected_alerts:
            self.assertIn(f"- alert: {alert_name}", rules)

    def test_skill_visibility_index_alert_names_are_stable(self) -> None:
        """Skill 可见性索引告警名称需要稳定，避免管理台和 runbook 引用漂移。"""

        rules = self._read_alert_rules()
        expected_alerts = [
            "AgentRuntimeSkillVisibilityIndexMaterializationFailureDetected",
            "AgentRuntimeSkillVisibilityIndexMaterializationFailureRatioHigh",
            "AgentRuntimeSkillVisibilityIndexFallbackQueryRatioHigh",
            "AgentRuntimeSkillVisibilityIndexDedicatedQueryFailureDetected",
            "AgentRuntimeSkillVisibilityIndexManifestBindingUnhealthy",
        ]

        for alert_name in expected_alerts:
            self.assertIn(f"- alert: {alert_name}", rules)

    def test_alert_expressions_do_not_select_high_cardinality_business_labels(self) -> None:
        """规则选择器不能使用高基数字段作为标签。"""

        rules = self._read_alert_rules()
        forbidden_selector = re.compile(
            r"\{[^}\n]*(tenantId|projectId|actorId|commandId|outboxId|requestId|runId|sessionId|traceId|workspaceId|manifestFingerprint)\s*="
        )

        self.assertIsNone(forbidden_selector.search(rules))

    def test_deferred_ratio_rule_uses_aggregate_rate(self) -> None:
        """DEFERRED 比例告警应先聚合 rate，再计算比例。"""

        rules = self._read_alert_rules()

        self.assertIn('sum(rate(datasmart_agent_runtime_async_command_precheck_verdict_total{decision="DEFERRED"}[15m]))', rules)
        self.assertIn("clamp_min(sum(rate(datasmart_agent_runtime_async_command_precheck_verdict_total[15m])), 1)", rules)
        self.assertIn("> 0.2", rules)

    def test_skill_visibility_index_rules_use_aggregate_rate_and_low_cardinality_labels(self) -> None:
        """Skill 可见性索引告警应使用聚合指标，并只选择低基数枚举标签。"""

        rules = self._read_alert_rules()

        self.assertIn(
            'sum(rate(datasmart_agent_runtime_skill_visibility_index_materialization_total{outcome="failed"}[30m]))',
            rules,
        )
        self.assertIn(
            'clamp_min(sum(rate(datasmart_agent_runtime_skill_visibility_index_materialization_total{outcome=~"materialized|duplicate|failed"}[30m])), 1)',
            rules,
        )
        self.assertIn(
            'sum(rate(datasmart_agent_runtime_skill_visibility_index_query_total{source="fallback",result="success"}[30m]))',
            rules,
        )
        self.assertIn(
            'clamp_min(sum(rate(datasmart_agent_runtime_skill_visibility_index_query_total{result="success"}[30m])), 1)',
            rules,
        )
        self.assertIn(
            'outcome=~"materialized|duplicate",bindingStatus=~"UNKNOWN|UNBOUND_UNKNOWN|DIAGNOSTICS_UNAVAILABLE|REMOTE_UNAVAILABLE_FALLBACK|LOCAL_DEFAULT_OR_FALLBACK|OTHER"',
            rules,
        )


if __name__ == "__main__":
    unittest.main()
