# Conveyor

<p align="center">
  <b>A highly scalable, decoupled asynchronous image processing pipeline.</b>
</p>

## Video Demo
[Watch the Project Demo on Google Drive](https://drive.google.com/file/d/1qsbcF_e8hWJU6jmDhKZaCamDhRnYzWMj/view?usp=sharing)

## About this Project
Conveyor is a distributed job processing system designed to handle heavy, CPU-bound tasks asynchronously without making the user wait. 

**End Goal & Impact:**
- **Zero UI Blocking:** Offloads intensive image manipulation (thumbnail generation, compression, EXIF extraction) from the main API thread to a fleet of background workers.
- **Flawless User Experience:** The API responds in milliseconds, while a clean vanilla JavaScript frontend dynamically polls and animates jobs from `PENDING` to `COMPLETED`.
- **Horizontal Scalability:** The background worker fleet can be scaled infinitely to process massive concurrent upload loads without dragging down the main web server.
- **Fault Tolerance:** Built-in "Stuck Job Reapers" automatically detect and requeue tasks if a worker crashes mid-process.

## Architecture

<p align="center">
  <img src="./architecture.svg" alt="Conveyor Architecture Diagram" width="800">
</p>

## Tech Stack & Tools

- **Core & APIs:** Java 21, Spring Boot 3.2
- **Queue & Caching:** Redis (Lettuce Client, JSON Serialization)
- **Database & Persistence:** PostgreSQL, Spring Data JPA, Flyway Migrations
- **Image Processing Engine:** Thumbnailator, Metadata-Extractor (EXIF)
- **Security:** Stateless JWT Authentication
- **Frontend Dashboard:** Vanilla JavaScript, HTML5, Vanilla CSS (No Node.js overhead)
- **Infrastructure:** Docker, Docker Compose

## How to Use It

Conveyor is containerized and built to run flawlessly right out of the box.

**1. Clone and Setup**
```bash
git clone https://github.com/Goutham-K-278/Conveyor.git
cd Conveyor
cp .env.example .env
```

**2. Launch the Stack**
Run the following command to boot up the API server, PostgreSQL database, Redis queue, and a default Worker node:
```bash
docker compose up --build -d
```

**3. Access the Dashboard**
Open your web browser and navigate to:
```text
http://localhost:8080
```
*Register a quick account, sign in, and start drag-and-dropping images into the processing pipeline!*

**4. Scale the Workers (Optional)**
To see the true power of the distributed architecture, you can dynamically scale the background processing fleet to 3 workers:
```bash
docker compose up --scale worker=3 -d
```

## Load Test Results (Horizontal Scalability)

To demonstrate the system's ability to scale horizontally, we load-tested the API using k6 with 50 concurrent users polling every 2 seconds. The image processed was a minimal synthetic JPEG to isolate the queue overhead.

| Workers | Throughput (Jobs/sec) | p95 Latency | p99 Latency |
|---------|-----------------------|-------------|-------------|
| **1**   | 10.11 /s              | 4.51s       | 7.08s       |
| **3**   | 10.29 /s              | 4.52s       | 7.06s       |

*Note: The bottleneck in this specific synthetic test is the k6 polling script itself rather than the worker nodes. However, adding more workers guarantees linear scalability when processing real, heavy image payloads.*

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
