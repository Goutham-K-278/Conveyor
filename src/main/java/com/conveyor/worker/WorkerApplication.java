package com.conveyor.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Entry point for running a Conveyor Worker process.
 *
 * Workers are separate JVM processes — they run alongside the API server
 * but independently. Each worker instance:
 *   - Has its own unique ID (hostname + random suffix)
 *   - Connects to the same PostgreSQL and Redis instances
 *   - Runs the WorkerLoop and StuckJobReaper
 *   - Does NOT expose any HTTP endpoints (web server is disabled in worker profile)
 *
 * ─── HOW TO RUN A WORKER ─────────────────────────────────────────────────────
 * java -jar conveyor.jar \
 *   --spring.profiles.active=worker \
 *   --worker.worker-id=worker-1
 *
 * Or via Docker Compose (see docker-compose.yml), which runs 3 workers
 * automatically with unique IDs.
 *
 * ─── WORKER ID ────────────────────────────────────────────────────────────────
 * Each worker needs a unique ID so we can track which worker holds which job
 * in the database's claimed_by column. The ID combines the machine hostname
 * and a short random suffix, making it both human-readable and collision-safe.
 * Example: "web-server-01-a3b4"
 */
@SpringBootApplication(scanBasePackages = "com.conveyor")
@EnableScheduling
public class WorkerApplication {

    public static void main(String[] args) {
        // Generate a unique worker ID based on hostname + random suffix
        String workerHostname = getHostnameOrFallback();
        String shortRandomSuffix = UUID.randomUUID().toString().substring(0, 4);
        String uniqueWorkerId = workerHostname + "-" + shortRandomSuffix;

        // Set the worker ID as a system property so application.yml can read it
        System.setProperty("worker.worker-id", uniqueWorkerId);

        System.out.println("Starting Conveyor Worker: " + uniqueWorkerId);
        SpringApplication.run(WorkerApplication.class, args);
    }

    private static String getHostnameOrFallback() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception unresolvableHostname) {
            // Fallback if we're in an environment where hostname isn't available
            return "worker";
        }
    }
}
