import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.model_gateway.model_public_output import sanitize_public_model_output


class PublicModelOutputTest(unittest.TestCase):
    def test_masks_secret_assignments_and_preserves_public_lines(self) -> None:
        output = sanitize_public_model_output("第一步读取元数据。\napi_key=secret-value\n第二步核对映射。")

        self.assertEqual("第一步读取元数据。\napi_key=[已隐藏]\n第二步核对映射。", output.content)
        self.assertNotIn("secret-value", output.content)
        self.assertFalse(output.truncated)

    def test_truncates_abnormally_large_provider_output(self) -> None:
        output = sanitize_public_model_output("x" * 20, max_chars=8)

        self.assertEqual("xxxxxxxx…", output.content)
        self.assertEqual(20, output.original_length)
        self.assertTrue(output.truncated)


if __name__ == "__main__":
    unittest.main()
