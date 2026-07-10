import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest, WorkloadType


class AgentRequestContractTest(unittest.TestCase):
    def test_preferred_workload_string_is_normalized_at_json_boundary(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="Synchronize selected tables.",
            preferred_workload="agent_reasoning",
        )

        self.assertIs(WorkloadType.AGENT_REASONING, request.preferred_workload)

    def test_unsupported_preferred_workload_has_clear_validation_error(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported preferred_workload"):
            AgentRequest(
                tenant_id="10",
                project_id="101",
                actor_id="1001",
                objective="Synchronize selected tables.",
                preferred_workload="unknown_workload",
            )


if __name__ == "__main__":
    unittest.main()
