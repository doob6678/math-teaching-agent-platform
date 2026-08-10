# 2026-08-06 Handout Implementation Verification

## Scope

This record covers the Python handout production-convergence changes made on 2026-08-06. Every value below comes
from a completed local command or a captured artifact. No provider result, token count, price, pass, or load value
has been inferred.

## Implemented changes

- All browser multi-agent-writing entry methods now create and read the sole `/api/teaching/tasks` workflow.
  `workflowId` remains a display compatibility alias for `taskId`; the synchronous legacy method no longer opens
  `/api/agents/writing`.
- The writing panel no longer presents page-header/footer mutation controls. Teaching task publication metadata is
  fixed at task creation, and the previous non-empty defaults caused Java to reject every export request.
- Python usage aggregation now keeps unknown provider prices as `estimatedCost=-1` and `costKnown=false`. Runtime
  telemetry does not make a priced provider run appear unknown, and failed provider attempts no longer persist a
  fabricated zero currency cost.
- Python graph node metrics now carry UTC start/finish timestamps and cached prompt tokens; the ledger normalizes
  ISO-8601 timestamps to MySQL-safe UTC datetimes before writing. Java Worker telemetry records queue wait, retry,
  completed/failed status and ACK latency in `handout_run_metrics` without writing provider usage rows.
- New Java contract tests cover the Python-default route and Java queue/ACK metric projection. These tests are
  boundary evidence only and are not counted as provider acceptance runs.
- `scripts/local/run-handout-baseline.sh` now captures read-only Compose, health, CUDA, CPU/RSS/GPU, virtual-memory,
  Docker-stat, Git revision, command result, and SHA-256 evidence files. It does not mutate networking, services,
  database schema, or dependencies.
- A real Linux-container database probe completed through the restricted `ai_runtime` account: one
  `handout_run_metrics` row and one `handout_node_metrics` row were written, `cached_prompt_tokens=1`, and
  `cost_known=0`. The probe made zero provider calls and used a dedicated `db-contract-*` run identifier.

## Dependency and load preflight

Dependency report: `docs/dependency-audit-2026-08-06.md`.

| Metric | Observed value |
| --- | ---: |
| Dependency audit calls | 18 |
| Dependency audit successes | 15 |
| Dependency audit non-destructive probe failures | 3 |
| Dependency audit elapsed | 341.62 s |
| Provider calls / provider successes / provider failures | 0 / 0 / 0 |
| Provider tokens | 0 |
| GPU | NVIDIA GeForce RTX 5060 Laptop GPU, 8,151 MiB |
| WSL memory at audit sample | 15 GiB total, 12 GiB available |

Fresh baseline artifact: `output/acceptance/baseline-20260806T085728Z/`.

| Baseline command group | Result |
| --- | --- |
| Compose config, Compose process list, `nvidia-smi`, `free`, `vmstat`, Docker stats | passed |
| AI worker health | passed: `200` |
| Frontend health | passed: `200` |
| Backend health | failed: connection reset by peer |
| Milvus health | failed: service not ready |

The snapshot shows all Compose services were externally restarted and still in their startup windows. This condition
also appears in the container logs as clean shutdown/start cycles; it is not classified as an application or model
failure. No Terra run was submitted because the Java control plane was unavailable, so real-provider counters remain
zero and the 30-run canary/fault matrix gate is not satisfied.

## Test results

| Command | Result | Measured command elapsed |
| --- | --- | ---: |
| `ai-worker-python/.venv/Scripts/python.exe -m pytest tests -q` | 64 passed, 5 subtests passed | 11.42 s test time (16.1 s command) |
| `ai-worker-python/.venv/Scripts/python.exe -m pytest tests/test_handout_runtime.py tests/test_usage.py -q` | 13 passed, 5 subtests passed | 4.52 s test time (8.2 s command) |
| `frontend npm test -- --run` | 21 files, 101 tests passed | 1.04 s test time (2.23 s command) |
| `ai-worker-python/.venv/Scripts/python.exe -m pytest tests -q` | 65 passed, 5 subtests passed | 12.28 s test time |
| `backend-java mvn -Dtest=MultiAgentWritingPythonOnlyContractTest,HandoutRunMetricsTest,PythonTeachingHandoutClientTest test` | 3 passed | 16.53 s command |
| `backend-java mvn -DskipTests compile` | build success, 594 source files compiled | 14.66 s command |
| Linux Worker -> MySQL restricted metrics probe | 1 run row + 1 node row written; cached token and unknown cost verified | command completed |
| `backend-java mvn.cmd -DskipTests=false -Dtest=TeachingWorkflowServiceTest,MultiAgentWritingControllerTest test` | controller: 6 passed; teaching workflow: 17 failed, 1 error | 28.8 s command |

The Java failures pre-existed this UI/accounting change and include task status, evidence mapping, trace count, and
rendered handout assertions. They block the required authenticated HTTP contract and publication acceptance. The full
failure output remains in the command execution record; no failure was suppressed or reclassified.

## Release gate status

| Gate | Status |
| --- | --- |
| Python unit/runtime suite | passed |
| Frontend teaching-task convergence suite | passed |
| Java focused workflow suite | blocked by 17 failures and 1 error |
| Python/Java metrics and Python-only boundary contracts | passed (65 Python tests, 3 Java tests) |
| Stable Java/Milvus Compose health | blocked by repeated external restart cycle |
| Real Terra handout calls | not run; 0 calls, 0 tokens |
| 30/30 canary, fault matrix, PDF/PNG manifests | not satisfied |

No cost figure is reported because no real provider request was made in this verification window.

## Follow-up Metrics Evidence

The durable MySQL summary was rerun on 2026-08-06 after the metric aggregation update, using the restricted
`ai_runtime` credentials inside the Linux AI Worker. This was a read-only query against one real completed handout
row, not a generated acceptance fixture.

| Metric | Observed value |
| --- | ---: |
| Completed handout run rows | 1 |
| Node metric rows | 1 |
| Durable usage-event rows | 55 |
| External Provider calls | 15 |
| External Provider successes / failures | 12 / 3 |
| External Provider total tokens | 106,743 |
| Runtime node audit events | 40 |
| Known cost attempts | 0 |
| System load samples on the completed row | 0 |

The legacy production `ai_usage_event` table does not yet contain `cached_prompt_tokens`. The aggregation script
detects that column at runtime, emits it as missing rather than zero, and continues to report the real token totals
and unknown prices. The completed row also has no queue, lease, ACK, PDF, or load evidence, so all five are reported
as missing. This proves the report's non-fabricating behavior but does not satisfy the stable end-to-end metrics gate.

Latest local verification after the metrics changes:

| Command | Result |
| --- | --- |
| `ai-worker-python/.venv/Scripts/python.exe -m pytest tests -q` | 65 passed, 5 subtests passed (12.70 s) |
| `frontend npm test -- --run` | 21 files, 101 tests passed (2.35 s) |
| `backend-java mvn -Dtest=HandoutRunMetricsTest,SqlInjectionGuardContractTest test` | 3 passed (18.31 s) |
| `backend-java mvn test` | 740 run; 35 failures, 15 errors, 8 skipped (218 s) |

The full Java failure set remains a release blocker. It includes the known teaching Python-migration fixture gap,
the absence of XeLaTeX in the Windows test runtime, and unrelated existing assertions; no failed item is counted as
accepted handout evidence.

## Follow-up Contract And Publication-Gate Verification

The compatibility boundary now has a service-level contract test backed by the real in-memory teaching task store and
outbox implementation. It creates one teaching task, then verifies that legacy create/read/resume/artifact/traces and
Markdown export all return that same task ID. The resume transition creates a second outbox event for the same task;
it does not create a retired agent-writing workflow row. This test does not call a provider or fabricate a handout.

The Java publication gate now rejects teacher-only answer and scoring labels in the final student LaTeX body before
any XeLaTeX process can start. The gate checks both a `\\paragraph{答案与评分点}` block and plain `参考解析` text.

| Command | Result | Measured command elapsed |
| --- | --- | ---: |
| `backend-java mvn.cmd -Dtest=HandoutPublicationGateTest test` | 3 passed | 18.78 s |
| `backend-java mvn.cmd -Dtest=HandoutTaskFacadeContractTest test` | 2 passed | 15.29 s |
| `backend-java mvn.cmd -Dtest=HandoutPublicationGateTest,HandoutTaskFacadeContractTest,MultiAgentWritingPythonOnlyContractTest,FailClosedJavaAiGatewayTest,HandoutRunMetricsTest,SqlInjectionGuardContractTest test` | 10 passed | 13.90 s |

Linux Compose was sampled again immediately before this record. Every application and dependency container reported
`Up 5 seconds (health: starting)`, including MySQL, Redis, RabbitMQ, Milvus, `ai-worker`, backend, and frontend. The
uniform five-second age confirms the known external restart cycle rather than a stable end-to-end state. Therefore this
follow-up made zero Terra/Provider calls, consumed zero Provider tokens, and records no cost or model-load result.

## Linux Full-Suite Recheck

The full Java suite was rerun with the locally installed WSL Maven 3.9.11 and OpenJDK 21. This is separate from the
Windows result above and is the authoritative Linux result for this recheck. It made no Provider request, consumed no
Provider tokens, and did not use a local CPU model.

| Command | Result | Measured command elapsed |
| --- | --- | ---: |
| `WSL: backend-java mvn -DskipTests=false test` | 744 run; 30 failures, 21 errors, 15 skipped | 133 s |

The errors are reproducible in three distinct groups: 17 PDF/batch-export tests fail closed because XeLaTeX cannot
produce a PDF in WSL; three controller export tests correctly reject empty CREATED task bodies; and the remaining
legacy Draft/teaching fixtures expect Java provider execution or synchronous generated output. These failures block
Task 5 Step 4, Task 7 PDF/PNG compilation, and Task 8 production acceptance. They are not reclassified as successful
Python handout runs.
