/**
 * Conveyor — k6 Quick Load Test (< 8 minutes)
 * ─────────────────────────────────────────────────────────────────────────────
 * A faster version of the full load test, designed to produce meaningful
 * benchmark numbers in under 8 minutes.
 *
 * Run with 1 worker (scale down in docker-compose first), then with 3 workers:
 *
 *   k6 run --env BASE_URL=http://localhost:8080 k6/load_test_quick.js
 *
 * ─── STAGES ──────────────────────────────────────────────────────────────────
 *   0–20s   : Ramp from 0 → 20 concurrent users
 *   20s–5m  : Hold at 20 users (steady-state — where real measurements happen)
 *   5m–5m30s: Ramp from 0 → 40 users (spike test)
 *   5m30s–7m: Hold at 40 users (peak load)
 *   7m–7m30s: Ramp down to 0
 *
 * ─── WHAT TO RECORD ──────────────────────────────────────────────────────────
 * | Workers | Jobs/sec | p95 latency | p99 latency | Success Rate |
 * |---------|----------|-------------|-------------|--------------|
 * |    1    |    ?     |      ?      |      ?      |      ?       |
 * |    3    |    ?     |      ?      |      ?      |      ?       |
 *
 * Filled-in results are in Conveyor.md — see the Benchmark Results section.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { FormData } from 'https://jslib.k6.io/formdata/0.0.2/index.js';
import encoding from 'k6/encoding';

// ── Custom Metrics ────────────────────────────────────────────────────────────
const jobCompletionTime   = new Trend('job_completion_milliseconds', true);
const totalJobsCreated    = new Counter('jobs_created_total');
const totalJobsCompleted  = new Counter('jobs_completed_total');
const totalJobsFailed     = new Counter('jobs_failed_total');
const jobSuccessRate      = new Rate('job_success_rate');

// ── Configuration ─────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_JOB_POLL_WAIT_SECONDS = 60;
const POLL_INTERVAL_SECONDS = 2;

export const options = {
    stages: [
        { duration: '20s', target: 20 },   // Ramp up
        { duration: '4m',  target: 20 },   // Steady state (main measurement window)
        { duration: '30s', target: 40 },   // Spike
        { duration: '90s', target: 40 },   // Peak load
        { duration: '30s', target: 0  },   // Ramp down
    ],
    thresholds: {
        'job_success_rate':             ['rate>0.90'],
        'job_completion_milliseconds':  ['p(95)<45000'],
        'http_req_duration':            ['p(99)<3000'],
    },
};

export function setup() {
    console.log(`\n=== Conveyor Quick Load Test ===`);
    console.log(`Target: ${BASE_URL}`);
    console.log(`Total planned duration: ~7m 30s\n`);
    return { baseUrl: BASE_URL };
}

// ── Main VU function ──────────────────────────────────────────────────────────
export default function (data) {
    const baseUrl = data.baseUrl;

    // ── Step 1: Register ──────────────────────────────────────────────────────
    const uniqueEmail = `vu${__VU}-${__ITER}-${Date.now()}@loadtest.com`;
    const registerRes = http.post(
        `${baseUrl}/auth/register`,
        JSON.stringify({ email: uniqueEmail, password: 'loadtest-password-123' }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    const registered = check(registerRes, {
        'Register 201': (r) => r.status === 201,
    });

    if (!registered) {
        console.error(`[VU ${__VU}] Registration failed: ${registerRes.status}`);
        totalJobsFailed.add(1);
        jobSuccessRate.add(false);
        return;
    }

    const token = registerRes.json('token');
    const authHeaders = { 'Authorization': `Bearer ${token}` };

    // ── Step 2: Upload image ──────────────────────────────────────────────────
    const imageBytes = generateSmallJpegBytes();
    const formData = new FormData();
    formData.append('file', http.file(imageBytes, 'test.jpg', 'image/jpeg'));

    const uploadStart = Date.now();

    const uploadRes = http.post(
        `${baseUrl}/jobs/image`,
        formData.body(),
        { headers: { ...authHeaders, 'Content-Type': `multipart/form-data; boundary=${formData.boundary}` } }
    );

    const uploaded = check(uploadRes, {
        'Upload 201':    (r) => r.status === 201,
        'Has jobId':     (r) => r.json('jobId') !== undefined,
    });

    if (!uploaded) {
        console.error(`[VU ${__VU}] Upload failed: ${uploadRes.status} — ${uploadRes.body}`);
        totalJobsFailed.add(1);
        jobSuccessRate.add(false);
        return;
    }

    totalJobsCreated.add(1);
    const jobId = uploadRes.json('jobId');

    // ── Step 3: Poll for completion ───────────────────────────────────────────
    let completed = false;
    let polls = 0;
    const maxPolls = MAX_JOB_POLL_WAIT_SECONDS / POLL_INTERVAL_SECONDS;

    while (!completed && polls < maxPolls) {
        sleep(POLL_INTERVAL_SECONDS);

        const statusRes = http.get(`${baseUrl}/jobs/${jobId}`, { headers: authHeaders });

        if (statusRes.status !== 200) {
            break;
        }

        const status = statusRes.json('status');

        if (status === 'COMPLETED') {
            jobCompletionTime.add(Date.now() - uploadStart);
            totalJobsCompleted.add(1);
            jobSuccessRate.add(true);
            completed = true;
        } else if (status === 'FAILED') {
            totalJobsFailed.add(1);
            jobSuccessRate.add(false);
            completed = true;
        }
        polls++;
    }

    if (!completed) {
        totalJobsFailed.add(1);
        jobSuccessRate.add(false);
        console.warn(`[VU ${__VU}] Job ${jobId} timed out after ${MAX_JOB_POLL_WAIT_SECONDS}s`);
    }
}

export function teardown() {
    console.log('\n=== Test Complete ===');
    console.log('See metrics above for:');
    console.log('  jobs_completed_total   → total jobs processed');
    console.log('  job_completion_ms p95  → 95th percentile end-to-end latency');
    console.log('  job_success_rate       → fraction of jobs that completed OK');
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Minimal valid 1×1 JPEG — fast to generate, accepted by Java ImageIO */
function generateSmallJpegBytes() {
    const validJpgB64 = '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=';
    return encoding.b64decode(validJpgB64);
}
