package com.conveyor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Conveyor API Server.
 *
 * This starts the Spring Boot application which includes:
 *   - Embedded Tomcat web server (port 8080)
 *   - PostgreSQL connection via Hikari pool
 *   - Flyway database migrations (run automatically on startup)
 *   - Redis connection for queue publishing and caching
 *   - Spring Security with JWT filter chain
 *   - @Scheduled tasks (StuckJobReaper runs every 30 seconds)
 *
 * ─── TWO ENTRY POINTS ─────────────────────────────────────────────────────────
 * Conveyor has two main() methods:
 *   1. ConveyorApplication  (this file) — starts the API HTTP server
 *   2. WorkerApplication    (worker package) — starts a background worker process
 *
 * In Docker Compose, we run 1 API server + 3 worker instances simultaneously.
 */
@SpringBootApplication
@EnableScheduling
public class ConveyorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConveyorApplication.class, args);
    }
}
