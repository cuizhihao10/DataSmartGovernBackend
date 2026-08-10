# Codex Migration Handoff - DataSmart Govern Project

Date: 2026-08-09
Source Codex task: 019d91c8-6aae-7752-b736-02bc904ffc9a
Project root: D:/Desktop/DataSmart-Govern
Backend repository: D:/Desktop/DataSmart-Govern/DataSmartGovernBackend
Frontend repository: D:/Desktop/DataSmart-Govern/DataSmartGovernFrontend

## Migration safety

The source task is not recoverable through normal resume. It repeatedly fails with:

`stream disconnected before completion: [ObjectParam] [input[306].namespace] [unknown_parameter] Unknown parameter: 'input[306].namespace'.`

The failure is in Codex conversation/tool-call replay, not in the repository. Do not fork or resume the complete malformed transcript as the primary migration path. Do not copy raw Responses API items, tool-call metadata, or any `namespace` field from the old rollout into a new prompt. Use this handoff plus the current files and tests as the new source of truth.

The original rollout remains preserved locally and must not be deleted or rewritten:

`C:/Users/Cui/.codex/sessions/2026/04/15/rollout-2026-04-15T23-35-24-019d91c8-6aae-7752-b736-02bc904ffc9a.jsonl`

## Project and repository state

- The new Codex task must use the saved `DataSmart-Govern` project and the local project directory `D:/Desktop/DataSmart-Govern`.
- This is a whole-project migration, not a backend-only migration. Inspect and continue both repositories and their API/WebSocket contract:
  - Backend: `D:/Desktop/DataSmart-Govern/DataSmartGovernBackend`.
  - Frontend: `D:/Desktop/DataSmart-Govern/DataSmartGovernFrontend`.
- The backend repository is on `master`, at `c75d37dd (fix(agent): restore actionable failed sessions)`.
- The frontend repository is on `master`, at `fad37d6 (fix(agent): close completed task workflows)`.
- Both worktrees are intentionally dirty and contain user/previous-agent work. Never reset, checkout, clean, or discard either worktree.
- Backend changes span agent-runtime, data-sync, gateway, permission-admin, platform-common, python-ai-runtime, scripts, deployment configuration, README, and operational documentation.
- Frontend changes span AgentAssistant, AgentConsole, DataSync, API endpoints, domain types, labels, global styles, agent components/features, scripts, package metadata, and README.
- New or recently added work includes six Specialist Agent contracts/registries/adapters/coordinators, Specialist turn-fact migrations, approval dual-subject scope migrations, post-bridge finalization, and six-agent integration tests.
- The repository is not a simple demo. Preserve the Java/Python boundary, Kafka decoupling, permission gates, audit facts, workspace isolation, idempotency, and observability contracts.

## Documentation inventory

The new task must read all Markdown documents under `D:/Desktop/DataSmart-Govern/DataSmartGovernBackend/docs/`, not only this handoff. These documents cover migrations, RAG, runtime packaging, production hardening, local E2E, failure drills, capacity, backup/restore, gateway/Keycloak, durable LangGraph state, MCP, final convergence, product roadmap, and learning/interview context. Also read:

- `D:/Desktop/DataSmart-Govern/DataSmartGovernBackend/README.md`
- `D:/Desktop/DataSmart-Govern/DataSmartGovernBackend/python-ai-runtime/README.md`
- `D:/Desktop/DataSmart-Govern/DataSmartGovernFrontend/README.md`
- `D:/Desktop/DataSmart-Govern/DataSmartGovernFrontend/docs/docker-compose.md`

Read the complete documents progressively where needed, but do not ignore any document merely because the current code change starts in one module. Use the final convergence, local E2E, product roadmap, and frontend README as cross-cutting context before declaring the project migrated.

## Fixed architecture constraints

- JDK 21.
- Spring Boot 3.5.11.
- Spring Cloud 2023.0.3 and Spring Cloud Alibaba 23.0.1.2.
- MyBatis-Plus for Java persistence.
- Kafka for asynchronous Java/Python coordination; use gRPC only for an explicit direct service contract.
- PostgreSQL/pgvector is the target system of record and Agent-memory store; MySQL remains phased compatibility infrastructure.
- Redis, Kafka, Neo4j, MinIO, Prometheus, and Grafana remain part of the platform stack.
- Keep the OpenClaw-style multi-agent architecture and LangGraph-style orchestration boundaries.
- The migration scope includes frontend implementation as well as backend implementation. Preserve the existing React/TypeScript frontend work and verify its API/WebSocket contracts against the backend. Do not replace frontend architecture or discard existing UI changes.
- Do not place business logic in the wrong module or write generated output under `target/`.
- For new code, preserve permissions, auditability, retries, timeouts, idempotency, and structured observability.

## Last reliable development state

The last long successful turn completed a real success-path validation before the replay failure began:

- Created sync task `89`.
- Created execution `2085`.
- Used two real tables and eight records.
- Read/write result was `8/8`; failures were `0`.
- Durable execution logs and PRECHECK/MONITOR facts were produced.
- The earlier Data Sync Specialist model failure did not reproduce in the historical validation after rebuilding the Runtime image. A fresh validation on 2026-08-09 rebuilt the latest image successfully, but the configured `xckjj.com` provider returned HTTP 401 for application-equivalent requests carrying the Runtime `User-Agent: DataSmart-AI-Runtime/1.0`; raw/default clients can instead be rejected by the provider WAF with HTTP 403. The Runtime correctly persisted the application-level failure as `DATA_SYNC_SPECIALIST_MODEL_FAILED`. This is an external provider credential/authorization block, not a Java/Python compilation failure.

The same turn found and fixed a Recovery-path defect:

- Recovery had valid RAG references and two low-risk suggestions.
- A second-stage re-submission of the same read-only plan was incorrectly marked `REJECTED` by duplicate protection.
- The intended behavior is to reuse an already completed identical read-only diagnostic/preview fact without executing a side effect twice.
- The real duplicate node was identified as `sync.execution.diagnose`, not the recovery preview itself.
- The fix was applied in the Specialist bridge/bootstrap path and the focused regression suite reported 61 passing tests.

## Known unfinished work

- The Recovery black-box rerun for execution `76/1805` still stops at `RECOVERY_PLANNING_MODEL_FAILED`: the current `xckjj.com` provider returned application-level HTTP 401 during the 2026-08-09 validation, replacing the earlier transient `503 Service Unavailable` observation. A request without the Runtime User-Agent can be rejected earlier by the WAF with HTTP 403, but that is not the current application failure. This remains a provider credential/authorization failure, not a repository code failure; no business side effect was reported.
- Three Specialist implementation tasks were being run with `gpt-5.6-terra`; the DataSmart Runtime itself remained configured as `gpt-5.6-sol` with `xhigh`. Do not silently change the Runtime model to Terra and do not fall back to Luna without an explicit reason.
- RAG persistence, native controlled tool selection, and dual-subject approval audit work had produced files in the shared worktree, but the final agent review and integrated test verification were not completed.
- One RAG task was re-dispatched after an upstream Terra connection failure. The dispatch layer later became unreliable, so its result must be verified from files and tests rather than assumed complete.
- The final convergence work was interrupted before a complete review, full backend regression, six-agent success/recovery E2E validation, documentation pass, commit, or push.

## Required first actions in the new task

1. Read this handoff, every Markdown file under the backend `docs/` directory, both repository READMEs, and the current implementations before changing code.
2. Run `git status --short --branch` independently in both `DataSmartGovernBackend` and `DataSmartGovernFrontend`; preserve every existing modification in both repositories.
3. Inspect the backend diff and all new Specialist files, then inspect the frontend diff, new agent components/features, API endpoints, domain types, and scripts. Treat all of them as existing work to review, not disposable generated output.
4. Check whether the current Docker/Python Runtime environment is healthy before interpreting provider or E2E failures as code failures.
5. Run focused backend Java/Python tests and frontend typecheck/build/lint tests where available, then verify API/WebSocket contract alignment.
6. Run the broader backend tests, frontend checks, and the success/recovery six-agent E2E only after focused checks and environment readiness pass.
7. Classify every failure as code, contract, environment, or upstream-provider failure; record the evidence in the project docs when useful.
8. Continue from the actual two-repository worktree state. Do not recreate already-present implementations and do not use destructive Git recovery commands.

## Completion criteria

The migration is complete when the new task has:

- validated the existing dirty worktree without discarding it;
- validated both the backend and frontend worktrees and their cross-repository contracts;
- reviewed and integrated the Specialist, approval, RAG, gateway, data-sync, and Python runtime changes;
- reviewed the frontend AgentAssistant, AgentConsole, DataSync, API/types, and agent feature changes;
- passed the focused regression suites;
- run or clearly classified the six-agent success and Recovery scenarios;
- recorded provider/environment blockers separately from code defects;
- updated the relevant convergence documentation;
- and left the user with the exact remaining implementation or verification steps.

## Context policy for this task

This file is a factual handoff, not an instruction to trust old assistant claims blindly. Verify claims against source code, Git diff, tests, runtime logs, and durable database facts. Keep the old Codex rollout read-only. The new task should use ordinary user prompts and tool calls only; never include raw historical tool-call objects or tool namespaces in its model-visible input.
