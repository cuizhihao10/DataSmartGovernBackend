import re
import unittest
from pathlib import Path


class PrometheusAlertRuleContractTest(unittest.TestCase):
    """验证 Python AI Runtime 告警规则的最小生产契约。

    这组测试不追求替代 Prometheus 自身的 `promtool check rules`，因为本地学习环境未必安装
    Prometheus 工具链；它的目标是把项目内最容易回归的规则约束固定下来：

    1. Prometheus 必须能抓取 Python Runtime 的 `/agent/metrics`，否则所有告警都没有数据源；
    2. 长期记忆物化链路的关键告警名称必须稳定，便于 Alertmanager、Grafana 和文档引用；
    3. 规则表达式不能把 tenantId、candidateId、traceId 等业务主键写入 Prometheus 选择器，
       因为这些高基数字段会让时序数量随租户、候选和请求数爆炸；
    4. dry-run-only 告警必须使用聚合后的向量匹配逻辑，避免 `dry_run=true/false`
       两组标签天然不相等导致告警永远无法触发。
    """

    REPO_ROOT = Path(__file__).resolve().parents[2]
    ALERT_RULES = REPO_ROOT / "docker" / "prometheus" / "rules" / "python-ai-runtime-alerts.yml"
    PROMETHEUS_CONFIG = REPO_ROOT / "docker" / "prometheus" / "prometheus.yml"

    def _read_alert_rules(self) -> str:
        """读取告警规则文件。

        规则文件使用中文注释和中文 runbook，必须按 UTF-8 读取；如果有人在 Windows 环境下误用
        ANSI/GBK 保存，测试会直接失败，避免注释在仓库中变成乱码并降低学习参考价值。
        """

        return self.ALERT_RULES.read_text(encoding="utf-8")

    def test_python_runtime_scrape_job_is_configured(self) -> None:
        """Python Runtime 指标抓取 job 必须存在且路径固定为 `/agent/metrics`。

        Java 服务使用 `/actuator/prometheus`，但 Python Runtime 不是 Spring Boot 应用，因此本项目
        明确把 AI Runtime 指标暴露在轻量业务端点 `/agent/metrics`。这里固定 job 名和路径，
        是为了让告警规则中的 `up{job="python-ai-runtime"}` 有稳定数据来源。
        """

        prometheus_config = self.PROMETHEUS_CONFIG.read_text(encoding="utf-8")

        self.assertIn("job_name: 'python-ai-runtime'", prometheus_config)
        self.assertIn("metrics_path: '/agent/metrics'", prometheus_config)
        self.assertIn("host.docker.internal:8090", prometheus_config)
        self.assertIn("/etc/prometheus/rules/*.yml", prometheus_config)

    def test_memory_materialization_alert_names_are_stable(self) -> None:
        """长期记忆物化的核心告警名称需要稳定。

        告警名称会被 Alertmanager 路由、Grafana 面板、运维手册和客户侧二次集成引用。
        如果未来确实要重命名，应同步调整文档、测试和下游路由，而不是静默改掉。
        """

        rules = self._read_alert_rules()
        expected_alerts = [
            "PythonAiRuntimeMetricsDown",
            "PythonAiRuntimeMetricsTargetMissing",
            "PythonAiMemoryMaterializationDlqIncreasing",
            "PythonAiMemoryMaterializationFinalizeError",
            "PythonAiMemoryMaterializationFailureRatioHigh",
            "PythonAiMemoryMaterializationRetryCooldownBacklog",
            "PythonAiMemoryMaterializationWorkerNoSuccessfulRun",
            "PythonAiMemoryMaterializationRequeueSurge",
            "PythonAiMemoryMaterializationDryRunOnly",
        ]

        for alert_name in expected_alerts:
            self.assertIn(f"- alert: {alert_name}", rules)

    def test_alert_expressions_do_not_select_high_cardinality_business_labels(self) -> None:
        """Prometheus 选择器不能使用业务主键类高基数字段。

        这里检查的是 `{label="value"}` 选择器中的标签名，而不是中文注释或 runbook 文本。
        注释可以说明为什么禁止 tenant/project/candidate/trace 等字段；真正危险的是把这些字段
        放进指标选择器或规则标签，导致每个租户、候选、trace 都生成独立时序。
        """

        rules = self._read_alert_rules()
        forbidden_selector = re.compile(
            r"\{[^}\n]*(tenantId|projectId|candidateId|leaseId|requestId|runId|sessionId|traceId|workspaceKey)\s*="
        )

        self.assertIsNone(forbidden_selector.search(rules))

    def test_dry_run_only_rule_uses_aggregate_unless_matching(self) -> None:
        """dry-run-only 告警必须避免 `dry_run=true/false` 直接向量匹配。

        如果写成 `increase(...{dry_run="true"}) and increase(...{dry_run="false"}) == 0`，
        Prometheus 会默认按完整标签集合匹配；由于两边的 `dry_run` 标签值不同，表达式很容易
        永远没有结果。本项目采用 `sum(...) unless sum(...) > 0`，先聚合再比较，表达
        “dry-run 很多，但真实重排没有发生”的业务含义。
        """

        rules = self._read_alert_rules()

        self.assertIn('sum(increase(datasmart_ai_memory_materialization_requeues_total{dry_run="true"}[1h])) > 20', rules)
        self.assertIn("unless", rules)
        self.assertIn('sum(increase(datasmart_ai_memory_materialization_requeues_total{dry_run="false"}[1h])) > 0', rules)


if __name__ == "__main__":
    unittest.main()
