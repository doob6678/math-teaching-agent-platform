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
