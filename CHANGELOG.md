# DataSmart Govern Backend Change Log

This file records user-visible features, bug fixes, security hardening, and
operational changes. Git remains the source of truth for file-level history;
this log provides the product and delivery summary.

## 2026-08-05

### Agent identity and authorization

- Added a dual-subject execution identity containing `userId`, `agentId`,
  `sessionId`, `runId`, and `delegationId` evidence.
- Enforced tenant, project, and actor ownership checks when listing, reading,
  continuing, pinning, archiving, or mutating Agent sessions.
- Added delegation checks before tool execution so an Agent can only invoke
  tools and resources granted by the current user delegation.
- Stripped Agent and internal-service headers supplied by untrusted clients at
  the Gateway before trusted context is rebuilt.

### Durable sessions and approvals

- Added PostgreSQL persistence for Agent sessions, delegations, tool bindings,
  runs, conversation messages, and approval confirmations.
- Added PostgreSQL persistence for permission-admin approval facts.
- Added trusted-service validation for approval-fact registration to prevent a
  normal client from forging an `APPROVED` fact.
- Added session history APIs for active/archived lists, details, pinning, and
  archiving; a continued conversation creates a new Run under the same session.
- Updated application Compose defaults to use durable JDBC stores rather than
  process-local memory for production-like local integration.

### Operations

- Reclaimed `63.46GB` of stale BuildKit cache without deleting images,
  containers, networks, or business data volumes.
- Added `scripts/docker-build-cache-maintenance.ps1`; report mode is the
  default and `-Prune` enforces a configurable cache ceiling (`10GB` by
  default).
- Added `docs/docker-build-cache-maintenance.md` with safe maintenance and
  emergency reset guidance.

### Validation

- Java 21 full affected-module reactor: `536` tests passed across Gateway,
  permission-admin, and agent-runtime; the focused change set also passed its
  `39` targeted tests.
- Python Runtime targeted regression: `35` tests passed.
- PowerShell BuildKit maintenance report, `-WhatIf`, and real prune modes
  passed; Docker reported Build Cache `0B` afterward.

## 2026-07-20 to 2026-07-31

### Agent and model runtime

- Connected the governed OpenAI-compatible Responses provider and exposed the
  real provider/model identity instead of a fixed placeholder.
- Added streaming planning progress, model exchanges, tool provenance,
  collapsible execution timelines, failure diagnosis, and reasoning cancel.
- Completed the governed native-tool loop, durable import dry-run/repair flow,
  RAG-assisted failure recovery, and post-tool result feedback.

### Data synchronization assistant

- Added progressive clarification for ambiguous data sources and incomplete
  synchronization requirements.
- Aligned Agent object/field mapping with the manual task wizard and removed
  the obsolete synchronization-template product path.
- Added editable configuration review, complete required-field validation,
  reviewed task submission, execution failure recovery, and duplicate task-name
  repair with user confirmation.

### Permissions and reliability

- Fixed delegated datasource access for connection tests and task creation.
- Recovered Agent planning across Runtime outages and delayed frontend serving
  until the Gateway is ready.
