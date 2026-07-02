"""工具动作 checkpoint API 测试的低敏执行图夹具。

夹具故意包含应被 API 脱敏的伪参数和 SQL，用于证明响应不会泄露这些字段；它不连接真实服务或数据库。
"""


def graph_run_for_resume() -> dict[str, object]:
    """生成等待 proposal evidence 的执行前图摘要。"""

    return {
        "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-graph-runner.v1",
        "previewOnly": True,
        "executionBoundary": "PRE_EXECUTION_GRAPH_RUNNER_ONLY",
        "stepCount": 1,
        "truncatedCount": 0,
        "statusCounts": {"WAITING_COMMAND_PROPOSAL_EVIDENCE": 1},
        "steps": [{
            "nodeType": "TOOL_ACTION_COMMAND_PROPOSAL",
            "templateId": "template-api-001",
            "toolName": "datasource.metadata.read",
            "decision": "ready",
            "outboxPreflightCandidate": True,
            "payloadPolicy": "REFERENCE_ONLY",
            "stepStatus": "WAITING_COMMAND_PROPOSAL_EVIDENCE",
            "proposalSubmission": {
                "submissionState": "VALIDATION_FAILED",
                "missingEvidence": ["PAYLOAD_REFERENCE_REQUIRED"],
                "requestPayload": {
                    "graphId": "graph-api",
                    "arguments": {"datasourceId": "ds-api-secret"},
                },
            },
            "nextAction": "COMPLETE_GRAPH_PAYLOAD_POLICY_OR_APPROVAL_EVIDENCE_THEN_RESUME",
        }],
        "sideEffectBoundary": {
            "toolExecuted": False,
            "outboxWritten": False,
            "workerDispatched": False,
            "approvalCreated": False,
            "checkpointPersisted": False,
        },
        "resumeRequirements": ["GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE"],
        "arguments": {"datasourceId": "ds-api-secret"},
        "sql": "select * from hidden_table",
    }


def graph_run_for_human_resume() -> dict[str, object]:
    """生成需要审批、澄清和 payload 引用共同补齐的恢复场景。"""

    data = dict(graph_run_for_resume())
    data["statusCounts"] = {
        "WAITING_APPROVAL_FACT": 1,
        "WAITING_CLARIFICATION_FACT": 1,
        "WAITING_COMMAND_PROPOSAL_EVIDENCE": 1,
    }
    data["resumeRequirements"] = [
        "GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE",
        "APPROVAL_CONFIRMATION_FACT",
        "CLARIFICATION_FACT",
    ]
    return data
