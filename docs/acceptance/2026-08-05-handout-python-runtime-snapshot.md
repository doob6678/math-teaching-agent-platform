# Handout Python Runtime Snapshot (2026-08-05)

## Scope

This is an observed Linux WSL Compose snapshot, not a simulated result and not a release-canary approval.
It records the current runtime and the existing real Luna handout artifact without starting another billable model
request. The source artifact is
`output/acceptance/python-langgraph-handout/run-real-luna-20260804-final-v6`.

## Runtime Health

Observed on 2026-08-05 (Asia/Shanghai):

| Component | Evidence | Result |
| --- | --- | --- |
| MySQL | `mysqladmin ping` inside the Compose service | `mysqld is alive` |
| Redis | authenticated `redis-cli ping` inside the Compose service | `PONG` |
| RabbitMQ | `rabbitmq-diagnostics -q ping` inside the Compose service | `Ping succeeded` |
| Milvus | Compose health status | `healthy` |
| Backend | Compose health status | `healthy` |
| AI worker | `GET http://127.0.0.1:8092/health` | `UP` |
| Frontend | Compose health status | `healthy` |

Milvus' HTTP `/healthz` returned `404` in the current image, and backend `/health` is not an application route;
neither response is counted as a successful health check.

## Host Load

The checked host was an NVIDIA GeForce RTX 5060 Laptop GPU with 8,151 MiB VRAM. At snapshot time the GPU was
0% utilized with 2,879 MiB allocated; GPU temperature was 55 C and reported power was 5.05 W. Host memory was
15 GiB total, 5.0 GiB used, 7.7 GiB free, and 10 GiB available. The second `vmstat` sample reported 91% CPU idle.

## Existing Real Luna Result

The parser reported one completed workflow and no failed workflows:

| Metric | Observed value |
| --- | ---: |
| Completed workflows | 1 |
| Failed workflows | 0 |
| Provider successes | 3 |
| Provider failures | 0 |
| Elapsed time | 59,985 ms |
| Prompt tokens | 25,818 |
| Completion tokens | 7,182 |
| Total tokens | 33,000 |
| Cost known | false |
| Estimated cost | null |

The retained run includes GPU samples but does not include queue wait, lease wait, ACK latency, PDF time, CPU/RSS
samples, or configured price data. Those fields remain explicitly incomplete and block Task 6 and Task 8 acceptance.

## Task 4 Two-Worker Contract Verification

Observed on 2026-08-05 in the real WSL Compose network. Two isolated `ai-worker` containers mounted the current
Python source read-only and invoked the same durable teaching-task `runId` concurrently. Each used the existing
OpenAI-compatible provider key and the explicit `gpt-5.6-terra` fallback only after the configured Luna model
returned HTTP 403. No dependency, DNS, IP address, or schema change was made for this verification.

| Assertion | Observed result |
| --- | --- |
| Checkpoint | exactly one MySQL row, `COMPLETED`, three writer documents |
| Duplicate graph execution | no duplicate writer call; both workers returned the same completed package |
| Provider success rows | 3, one each for teacher, student, and lecture writer |
| Durable attempt numbers | `1`, `101`, `201`; separate stable slots prevent the concurrent writers sharing one usage key |
| Provider failures for the successful Terra run | 0 |
| Java context requests | 1 |
| End-to-end elapsed | 26,189 ms and 26,207 ms for the two concurrent callers |
| Graph elapsed | 26,119 ms |
| Prompt tokens | 23,190 |
| Completion tokens | 2,404 |
| Total tokens | 25,594 |
| Cost accounting | unknown, persisted as `-1`; not converted to zero |

Successful node durations were resource curation 1,819 ms, student writer 10,858 ms, teacher writer 21,751 ms,
and lecture writer 24,032 ms. The graph's two system samples reported process CPU 1.318% then 0.948%; GPU 0%
utilization with 3,183 MiB of 8,151 MiB VRAM allocated. Immediately after the run, the host had 15 GiB memory
total, 5.3 GiB used, 9 GiB available; the persistent worker used 1.774 GiB RSS-equivalent container memory.

The first two-worker Luna attempt was recorded separately and was not counted as success: the old backend image
lacked the internal broker route and returned HTTP 404 before provider work. After deploying the compiled backend
JAR, the next Luna attempt reached the provider but all three writers received `HTTP_403_bad_response_status_code`;
each failed usage row has zero prompt, completion, and total tokens. The failed checkpoint remained `FAILED` and was
not reused as a completed result.

Focused verification after the implementation change passed: `PythonHandoutClientTest` 1/1, Python handout contract
tests 10/10, and the non-handout teaching-draft rejection contract 1/1. The broader server test batch has one
unrelated failure: `test_tokenize_returns_real_encoder_counts` returned 503 because its isolated test container
could not initialize the local tokenizer encoder; it is not included in the handout contract pass count.
