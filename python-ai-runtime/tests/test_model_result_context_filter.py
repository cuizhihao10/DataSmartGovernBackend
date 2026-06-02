import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.model_gateway.model_result_context_filter import (
    ModelResultContextFilter,
    ModelResultContextFilterPolicy,
)


class ModelResultContextFilterTest(unittest.TestCase):
    """字段级模型上下文过滤器测试。

    这些测试固定的是安全语义，而不是完整 JSONPath 语法。当前阶段我们只支持 DataSmart 工具结果最常见的
    点号嵌套路径和 `[]` 列表通配，目的是让字段级治理先进入 Agent 主链路。
    """

    def test_includes_nested_and_list_wildcard_paths(self) -> None:
        result = ModelResultContextFilter().filter(
            {
                "metadata": {"tableCount": 2, "connectionString": "jdbc:mysql://secret"},
                "tables": (
                    {"name": "user_profile", "sample": "phone", "rowCount": 100},
                    {"name": "orders", "sample": "amount", "rowCount": 200},
                ),
            },
            ModelResultContextFilterPolicy(
                include_paths=("metadata.tableCount", "tables[].name", "tables[].sample"),
                sensitive_paths=("tables[].sample",),
            ),
        )

        self.assertEqual({"tableCount": 2}, result.result["metadata"])
        self.assertEqual("user_profile", result.result["tables"][0]["name"])
        self.assertEqual("***MASKED***", result.result["tables"][0]["sample"])
        self.assertNotIn("connectionString", result.result["metadata"])
        self.assertNotIn("rowCount", result.result["tables"][0])
        self.assertEqual(("tables[].sample",), result.report.masked_paths)

    def test_applies_exclude_and_size_limits(self) -> None:
        result = ModelResultContextFilter().filter(
            {
                "summary": "x" * 20,
                "rawSql": "select * from sensitive_table",
                "columns": [{"name": f"c{i}"} for i in range(5)],
            },
            ModelResultContextFilterPolicy(
                exclude_paths=("rawSql",),
                max_string_length=5,
                max_list_items=2,
            ),
        )

        self.assertNotIn("rawSql", result.result)
        self.assertEqual("xxxxx...(truncated)", result.result["summary"])
        self.assertEqual(2, len(result.result["columns"]))
        self.assertIn("rawSql", result.report.removed_paths)
        self.assertIn("summary", result.report.truncated_paths)
        self.assertIn("columns", result.report.truncated_paths)


if __name__ == "__main__":
    unittest.main()
