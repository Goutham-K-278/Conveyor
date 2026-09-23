# Conveyor: Technical Deep Dive

## Deep Dive: Core Mechanisms & Fault Tolerance

Conveyor is built to guarantee job completion even in the face of distributed system failures.

### The Two-Layer Claiming Mechanism
To prevent multiple workers from processing the same job simultaneously, Conveyor implements a strict two-layer claiming strategy:
1. **Layer 1 (Redis `SETNX`):** The fastest worker acquires a distributed lock (`conveyor:lock:{jobId}`) with a 180s TTL. This acts as an ultra-fast O(1) gate.
2. **Layer 2 (Database Guard):** The winner executes an atomic `UPDATE jobs SET status='PROCESSING' WHERE id=? AND status='PENDING'`. If the database returns 0 rows updated, it means another worker beat it, and the lock is instantly released.

### Fixing Race Conditions
During early load testing, a race condition caused 60% of jobs to fail. The API published the job ID to Redis *before* the PostgreSQL transaction committed. Workers would wake up, query the DB, and find no job. 
**Fix:** The Redis publish logic is now wrapped in a `TransactionSynchronizationManager.afterCommit()` hook, ensuring workers only wake up *after* the DB row is fully visible.

### The Stuck Job Reaper
If a worker crashes (OOM, kill -9, hardware failure) mid-process, the job remains stuck in the `PROCESSING` state forever.
- A `@Scheduled` watchdog (the Reaper) wakes up every 30s.
- It identifies jobs stuck in `PROCESSING` for > 2 minutes.
- If attempts < 3, it resets the status to `PENDING` and requeues the job.
- If attempts >= 3, it marks the job as `FAILED` and pushes it to a **Dead-Letter Queue** for manual inspection.

### Idempotency Guarantee
The fault-tolerance mechanisms are strictly **idempotent**. If a job is partially processed and the worker crashes, the Reaper requeuing it is entirely safe. The new worker will simply overwrite the partially generated files (thumbnails/compressed images) with identical outputs. Database updates are atomic, guaranteeing no duplicate records.

## API Documentation & Observability

Conveyor provides built-in tools for seamless integration and monitoring:

- **Swagger / OpenAPI:** Once the application is running, navigate to `http://localhost:8080/swagger-ui/index.html` to explore and test the 6 documented REST API endpoints interactively.
- **Actuator Health:** Service health can be monitored via `GET /actuator/health`.
- **Actuator Metrics:** Real-time JVM and application metrics are exposed at `GET /actuator/metrics`.
- **Logging Strategy:** Concise, structured logging is enabled at the `INFO` level for application events, while noisy dependency logs (like Spring Security) are suppressed to `WARN`.

## Test Case Coverage

The system's most critical logic is heavily tested to simulate distributed failures:
- **`JobClaimerTest`**: Verifies that when the Redis `SETNX` lock is available, the claim succeeds. It also verifies that if the database guard returns 0 rows, the claim is aborted and the Redis lock is instantly deleted to prevent deadlocks.
- **`StuckJobReaperTest`**: Verifies that stuck jobs under the retry limit are successfully reset to `PENDING` and requeued, while jobs that exhaust the retry limit are permanently `FAILED` and correctly routed to the dead-letter queue.

---
> **Developer Note on GitHub Topics:** If you are hosting this repository on GitHub, ensure you add the following topics to boost discoverability: `spring-boot`, `redis`, `distributed-systems`, `docker`, `image-processing`.
