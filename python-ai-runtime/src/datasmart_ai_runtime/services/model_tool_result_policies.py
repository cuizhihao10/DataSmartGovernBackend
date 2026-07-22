"""Low-sensitive result policies for model-driven tool continuations.

Java remains the durable source of truth for tool outputs.  A successful result is
not automatically safe for an LLM, so every tool that participates in an Agent
loop must explicitly declare the small set of fields that may be returned in the
next ``role=tool`` message.  File bodies, credentials, raw SQL, sample rows and
unbounded metadata are deliberately absent from these policies.
"""

from __future__ import annotations

from typing import Any


_MODEL_RESULT_INCLUDE_PATHS: dict[str, tuple[str, ...]] = {
    "datasource.source.connection.test": (
        "datasourceId",
        "success",
        "databaseProductName",
        "databaseProductVersion",
        "latencyMs",
    ),
    "datasource.target.connection.test": (
        "datasourceId",
        "success",
        "databaseProductName",
        "databaseProductVersion",
        "latencyMs",
    ),
    # The full metadata object is retained in Java for derived references.  The
    # model receives only the bounded summary and cannot inspect columns/samples.
    "datasource.source.metadata.read": ("datasourceId", "summary"),
    "datasource.target.metadata.read": ("datasourceId", "summary"),
    "sync.task.draft.save": (
        "taskId",
        "templateId",
        "state",
        "objectCount",
        "sourceDatasourceId",
        "targetDatasourceId",
    ),
    "sync.task.precheck": (
        "templateId",
        "precheckStatus",
        "canStartExecution",
        "issueCodes",
        "recommendedActions",
        "connectorCompatibilitySupported",
        "scopeContractValid",
        "fieldMappingDeclared",
    ),
    "sync.task.publish": ("taskId", "state", "message"),
    "sync.task.run": ("taskId", "state", "message"),
    "sync.execution.status": (
        "taskId",
        "executionFound",
        "executionId",
        "executionState",
        "recordsRead",
        "recordsWritten",
        "failedRecordCount",
        "terminal",
        "pollCount",
        "trackingTimedOut",
    ),
    "sync.task.import.dry-run": (
        "artifact.artifactRef",
        "artifact.parentArtifactRef",
        "artifact.versionNumber",
        "artifact.fileName",
        "artifact.fileFormat",
        "artifact.artifactState",
        "importResult.status",
        "importResult.message",
        "importResult.totalRows",
        "importResult.validRows",
        "importResult.conflictCount",
        "importResult.failedCount",
        "importResult.rows[].rowNumber",
        "importResult.rows[].name",
        "importResult.rows[].status",
        "importResult.rows[].message",
        "importResult.rows[].errorCode",
        "importResult.rows[].fieldName",
        "importResult.rows[].repairable",
        "importResult.rows[].suggestedAction",
        "confirmationDigest",
        "ragQuery",
        "repairRequired",
    ),
    "sync.task.import.rag.lookup": (
        "answer",
        "citations",
        "retrievalSummary",
        "modelSummary",
    ),
    "sync.task.import.repair.apply": (
        "artifactRef",
        "parentArtifactRef",
        "versionNumber",
        "fileName",
        "fileFormat",
        "artifactState",
    ),
    "sync.task.import.commit": (
        "artifact.artifactRef",
        "artifact.versionNumber",
        "artifact.artifactState",
        "importResult.status",
        "importResult.message",
        "importResult.createdCount",
        "importResult.draftCount",
        "importResult.queuedCount",
        "importResult.failedCount",
        "importResult.conflictCount",
    ),
}


def model_result_governance(tool_name: str) -> dict[str, Any]:
    """Return governance hints that allow only an explicit result projection."""

    include_paths = _MODEL_RESULT_INCLUDE_PATHS.get(tool_name)
    if not include_paths:
        return {}
    return {
        "outputContextPolicy": "model_summary_allowed",
        "modelContextIncludePaths": include_paths,
        "modelContextExcludePaths": (),
        "sensitiveResultPaths": (),
    }


__all__ = ["model_result_governance"]
