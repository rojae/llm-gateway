# Response Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Store and show correctly associated Claude and Codex answer text while preserving subscription authentication and existing notifications.

**Architecture:** Extend the existing authenticated OTLP normalizer/store/query path. Use native Claude response events and a bounded, durable Codex notify adapter that sends completed question/answer pairs with exact turn IDs. Add protected response detail views without changing cost accounting.

**Tech Stack:** Java 21/Spring WebFlux/JDBC/PostgreSQL/Flyway; Python 3.11+ standard library; Next.js/React/TypeScript.

**Spec:** `docs/superpowers/specs/2026-10-08-response-monitoring-design.md`

## Global Constraints

- Feature branch is created from `main`; never use dev/develop. Preserve the earlier uncommitted pricing fix.
- Preserve existing OAuth/model/hook/notification configuration. No arbitrary code execution from telemetry input.
- Response size cap 65,536 UTF-16 code units; prompt cap remains 16,384. No surrogate splitting.
- Never infer association by timestamps/text. Never count answer text events as token usage.
- Existing content:read/audit/retention policies apply. No plaintext content in ordinary metadata APIs or logs.
- Fresh native data only; no import of prior user transcripts.

## Review Focus

1. Identical IDs from different owners, sources, or sessions must not cross-link or deduplicate one another's responses.
2. Out-of-order delivery and purge replay must not lose valid text or resurrect deleted content.
3. Codex notify opt-in must prevent native prompt duplicates without changing existing clients.
4. Notification forwarding must continue during collector/network/queue failures and must never recurse.
5. Plain text containing markup, long Unicode and empty/redacted bodies must display safely and accurately.

### Task 1: Backend response contract, storage and access

**Files:** `MonitoringNormalizer.java`, `MonitoringStore.java`, `MonitoringQueries.java`, `MonitoringController.java`, `TelemetryController.java`, new `db/migration/V8__monitoring_responses.sql`, focused normalizer/storage/contract tests.

**Produces:** Spec Response shape in question detail and paginated session responses; native Claude + custom `codex.turn_response`; validated optional `X-LLM-Gateway-Codex-Question-Source` header.

- [x] Add failing normalizer and real-DB contract tests for both payloads, explicit association, duplicate/out-of-order delivery, owner/session boundaries, invalid fields and truncation.
- [x] Implement migration and normalization/storage without altering prior migration files or cost aggregation.
- [x] Add/verify protected detail, metadata redaction, audit rollback, response retention and no resurrection after purge.
- [x] Run focused Java tests and hand back interface details and evidence. Root owns the full suite and runtime restart.

### Task 2: Codex notify adapter

**Files:** new `scripts/codex-response-monitor.py`, `scripts/test_codex_response_monitor.py`.

**Consumes:** active `$CODEX_HOME/config.toml` OTel endpoint/headers; private sidecar with original notify command and spool path. **Produces:** spec `codex.turn_response` OTLP event with same thread/turn identifiers; forwards original notify payload exactly.

- [x] Add failing tests for exact payload shape, multiple inputs, empty answer, invalid identifiers, unsupported notification, original notifier failure, recursion, endpoint constraints, queue ACK/partialSuccess/offline/concurrency and capacity.
- [x] Implement standard-library adapter with private atomic bounded spool and bounded network/forwarding timeouts. Never persist collector tokens in spool.
- [x] Run Python tests and document concrete invocation/sidecar format for root configuration and docs.

### Task 3: Answer views

**Files:** `llm-gateway-admin/app/admin/model.ts`, `monitoring.tsx`, response component and scoped stylesheet, focused browser test.

**Consumes:** exact Response and endpoint contracts from spec. **Produces:** answer list beneath question detail and session answer tab with explicit unlinked states, no raw HTML execution.

- [x] Add a browser fixture/check for multiple/empty/purged/permission-limited/unlinked/truncated answers, malicious markup and responsive layout.
- [x] Implement views preserving existing question list, costs and pagination. Refresh active answer views when new telemetry arrives.
- [x] Run frontend type check/build and browser fixture after integration; report evidence.

### Task 4: Integration, local setup and operational validation

**Files:** `docs/remote-monitoring.md`, relevant collector setup snippets if needed, private user settings/backups and ignored verification artifacts.

- [x] Independently review tasks and resolve findings.
- [x] Run all Java tests with disposable PostgreSQL/Redis, all script tests, frontend checks/build and browser verification.
- [x] Back up local DB/config/runtime; rebuild and restart local stack with migration.
- [x] Enable Claude response text and install Codex notify chain plus exporter header with semantic checks that preserve all other settings.
- [x] Run native synthetic questions and verify their exact response text, association, no duplicates and protected access through DB/API/UI. Retry queued delivery through `--flush` if needed.
- [x] Update docs with setup, final-only Codex limitation, queue/retry/privacy/restart instructions and exact verification outcomes.
