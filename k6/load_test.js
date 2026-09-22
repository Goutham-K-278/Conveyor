/**
 * Conveyor — k6 Load Test
 * ─────────────────────────────────────────────────────────────────────────────
 * Measures throughput (jobs/sec) and latency (p95, p99) under concurrent load.
 *
 * Run this test TWICE to compare 1 worker vs 3 workers:
 *
 *   # With 1 worker running:
 *   k6 run --env BASE_URL=http://localhost:8080 k6/load_test.js
 *
 *   # With 3 workers running (docker compose up):
 *   k6 run --env BASE_URL=http://localhost:8080 k6/load_test.js
 *
 * ─── WHAT THIS TEST DOES ─────────────────────────────────────────────────────
 * 1. Ramp up to 50 concurrent users over 30 seconds
 * 2. Each user registers, logs in, and repeatedly uploads images for 2 minutes
 * 3. After each upload, polls the job status until it completes (or times out)
 * 4. Ramp down over 10 seconds
 * 5. k6 outputs throughput and p95/p99 latency automatically
 *
 * ─── WHAT TO RECORD ──────────────────────────────────────────────────────────
 * | Workers | Jobs/sec (throughput) | p95 latency | p99 latency |
 * |---------|----------------------|-------------|-------------|
 * |    1    |         ?            |      ?      |      ?      |
 * |    3    |         ?            |      ?      |      ?      |
 *
 * This table is your benchmark result — it shows horizontal scaling in action.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { FormData } from 'https://jslib.k6.io/formdata/0.0.2/index.js';
import encoding from 'k6/encoding';

// ── Custom Metrics ────────────────────────────────────────────────────────────
const jobCompletionTime = new Trend('job_completion_milliseconds', true);
const totalJobsCreated = new Counter('jobs_created_total');
const totalJobsCompleted = new Counter('jobs_completed_total');
const totalJobsFailed = new Counter('jobs_failed_total');
const jobSuccessRate = new Rate('job_success_rate');

// ── Test Configuration ────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Maximum time to wait for a job to complete before giving up
const MAX_JOB_POLL_WAIT_SECONDS = 120;

// How often to poll job status
const POLL_INTERVAL_SECONDS = 2;

export const options = {
    stages: [
        { duration: '30s', target: 50 },   // Ramp up to 50 concurrent users
        { duration: '2m',  target: 50 },   // Hold at 50 users for 2 minutes
        { duration: '10s', target: 0 },    // Ramp down
    ],
    thresholds: {
        // At least 95% of jobs should complete successfully
        'job_success_rate': ['rate>0.95'],
        // 95th percentile of job completion time should be under 60 seconds
        'job_completion_milliseconds': ['p(95)<60000'],
        // HTTP requests themselves should be fast (status endpoint)
        'http_req_duration': ['p(99)<5000'],
    },
};

// ── Test Setup: Create a unique user per VU ───────────────────────────────────
export function setup() {
    console.log(`Starting Conveyor load test against: ${BASE_URL}`);
    return { baseUrl: BASE_URL };
}

// ── Main Virtual User Function ────────────────────────────────────────────────
export default function (data) {
    const baseUrl = data.baseUrl;

    // ── Step 1: Register a unique user ───────────────────────────────────────
    const uniqueEmail = `loadtest-vu${__VU}-${Date.now()}@test.com`;
    const registerResponse = http.post(
        `${baseUrl}/auth/register`,
        JSON.stringify({ email: uniqueEmail, password: 'loadtest-password-123' }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    const registrationSucceeded = check(registerResponse, {
        'Registration returned 201': (r) => r.status === 201,
    });

    if (!registrationSucceeded) {
        console.error(`Registration failed: ${registerResponse.status} — ${registerResponse.body}`);
        return;
    }

    const authToken = registerResponse.json('token');
    const authHeaders = { 'Authorization': `Bearer ${authToken}` };

    // ── Step 2: Upload an image and create a job ──────────────────────────────
    // Use a small synthetic JPEG for consistent test results
    const syntheticImageBytes = generateSmallJpegBytes();
    const formData = new FormData();
    formData.append('file', http.file(syntheticImageBytes, 'loadtest-image.jpg', 'image/jpeg'));

    const uploadStartTime = Date.now();

    const uploadResponse = http.post(
        `${baseUrl}/jobs/image`,
        formData.body(),
        { headers: { ...authHeaders, 'Content-Type': `multipart/form-data; boundary=${formData.boundary}` } }
    );

    const uploadSucceeded = check(uploadResponse, {
        'Upload returned 201': (r) => r.status === 201,
        'Upload response has jobId': (r) => r.json('jobId') !== undefined,
    });

    if (!uploadSucceeded) {
        totalJobsFailed.add(1);
        jobSuccessRate.add(false);
        return;
    }

    totalJobsCreated.add(1);
    const jobId = uploadResponse.json('jobId');

    // ── Step 3: Poll for job completion ──────────────────────────────────────
    let jobCompleted = false;
    let pollAttempts = 0;
    const maxPollAttempts = MAX_JOB_POLL_WAIT_SECONDS / POLL_INTERVAL_SECONDS;

    while (!jobCompleted && pollAttempts < maxPollAttempts) {
        sleep(POLL_INTERVAL_SECONDS);

        const statusResponse = http.get(
            `${baseUrl}/jobs/${jobId}`,
            { headers: authHeaders }
        );

        if (statusResponse.status !== 200) {
            break;
        }

        const jobStatus = statusResponse.json('status');

        if (jobStatus === 'COMPLETED') {
            const totalElapsedMilliseconds = Date.now() - uploadStartTime;
            jobCompletionTime.add(totalElapsedMilliseconds);
            totalJobsCompleted.add(1);
            jobSuccessRate.add(true);
            jobCompleted = true;
        } else if (jobStatus === 'FAILED') {
            totalJobsFailed.add(1);
            jobSuccessRate.add(false);
            jobCompleted = true;
        }

        pollAttempts++;
    }

    // Job timed out — count as failure
    if (!jobCompleted) {
        totalJobsFailed.add(1);
        jobSuccessRate.add(false);
        console.warn(`Job ${jobId} did not complete within ${MAX_JOB_POLL_WAIT_SECONDS} seconds`);
    }
}

// ── Test Summary ──────────────────────────────────────────────────────────────
export function teardown(data) {
    console.log('Load test complete. Check the metrics above for throughput and latency results.');
}

// ── Helper: Generate minimal JPEG bytes for upload ────────────────────────────
function generateSmallJpegBytes() {
    // Minimal valid 1x1 JPEG (satisfies Java ImageIO)
    const validJpgB64 = '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=';
    return encoding.b64decode(validJpgB64);
}
