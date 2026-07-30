import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.sync_configuration_corrections import (
    apply_explicit_sync_corrections,
)


class SyncConfigurationCorrectionsTest(unittest.TestCase):
    def test_task_name_and_where_are_applied_without_losing_other_mappings(self) -> None:
        payload = {
            "taskName": "全量传输任务",
            "sourceDatasourceId": 27,
            "targetDatasourceId": 28,
            "syncMode": "FULL",
            "writeStrategy": "INSERT",
            "objectMappings": [
                {
                    "sourceObjectName": "fs_test_customer_source",
                    "targetSchemaName": "public",
                    "targetObjectName": "fs_test_customer_source",
                },
                {
                    "sourceObjectName": "fs_test_customer_target",
                    "targetSchemaName": "public",
                    "targetObjectName": "fs_test_customer_target",
                },
            ],
        }

        corrected = apply_explicit_sync_corrections(
            payload,
            "任务名称改为客户双表全量同步，并给 fs_test_customer_source "
            "增加 WHERE 条件：status = 1。",
        )

        self.assertEqual("客户双表全量同步", corrected["taskName"])
        self.assertEqual("status = 1", corrected["objectMappings"][0]["whereCondition"])
        self.assertNotIn("whereCondition", corrected["objectMappings"][1])
        self.assertEqual(27, corrected["sourceDatasourceId"])
        self.assertEqual(28, corrected["targetDatasourceId"])

    def test_explicit_mapping_target_and_where_removal_are_supported(self) -> None:
        payload = {
            "objectMappings": [
                {
                    "sourceObjectName": "customer",
                    "targetSchemaName": "public",
                    "targetObjectName": "customer",
                    "whereCondition": "status = 1",
                },
            ],
        }

        corrected = apply_explicit_sync_corrections(
            payload,
            "把 customer 映射到 archive.customer_history，并对 customer 删除 WHERE 条件。",
        )

        mapping = corrected["objectMappings"][0]
        self.assertEqual("archive", mapping["targetSchemaName"])
        self.assertEqual("customer_history", mapping["targetObjectName"])
        self.assertNotIn("whereCondition", mapping)

    def test_natural_language_can_confirm_same_name_fields_and_empty_where_defaults(self) -> None:
        payload = {
            "objectMappings": [{
                "sourceObjectName": "customer",
                "targetSchemaName": "public",
                "targetObjectName": "customer",
                "whereCondition": "",
                "fieldMappings": [{
                    "sourceField": "id",
                    "targetField": "id",
                    "syncEnabled": True,
                }],
            }],
        }

        corrected = apply_explicit_sync_corrections(
            payload,
            "接受默认同名字段映射，不需要 WHERE 条件，按默认配置继续。",
        )

        self.assertTrue(corrected["mappingDefaultsConfirmed"])

    def test_task_name_correction_supports_action_first_wording_and_stops_before_execute(self) -> None:
        payload = {
            "taskName": "Agent 创建的数据同步任务",
            "sourceDatasourceId": 27,
            "targetDatasourceId": 28,
            "objectMappings": [{"sourceObjectName": "customer", "targetObjectName": "customer"}],
        }

        variants = (
            "修改任务的名称为agent全量测试_0731_test_mysql2pgsql，然后执行任务",
            "把任务名称修改为agent全量测试_0731_test_mysql2pgsql后再自动执行任务",
            "任务改名为agent全量测试_0731_test_mysql2pgsql，再运行任务",
            "rename task to agent全量测试_0731_test_mysql2pgsql, then run it",
        )
        for message in variants:
            with self.subTest(message=message):
                corrected = apply_explicit_sync_corrections(payload, message)
                self.assertEqual(
                    "agent全量测试_0731_test_mysql2pgsql",
                    corrected["taskName"],
                )
                self.assertEqual(27, corrected["sourceDatasourceId"])
                self.assertEqual(payload["objectMappings"], corrected["objectMappings"])


if __name__ == "__main__":
    unittest.main()
