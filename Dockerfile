# ─────────────────────────────────────────────────────────────────────────────
# Conveyor Dockerfile — Multi-Stage Build
# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 (builder): Compiles the Java source and creates the fat JAR
# Stage 2 (runtime): Runs the JAR in a minimal JRE image (no build tools)
#
# This two-stage approach keeps the final image small (~200MB vs ~600MB)
# by not including Maven, source code, or the full JDK in the runtime image.
#
# The same image runs as EITHER the API server or a worker — the
# SPRING_PROFILES_ACTIVE environment variable controls which mode:
#   SPRING_PROFILES_ACTIVE=api    → starts the HTTP server on port 8080
#   SPRING_PROFILES_ACTIVE=worker → starts a background worker process
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build the application ───────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy dependency descriptor first — Docker caches this layer separately.
# If only source code changes (not pom.xml), Maven won't re-download dependencies.
COPY pom.xml .

# Install maven
RUN apk add --no-cache maven

# Pre-download all Maven dependencies (separate layer for caching)
RUN mvn dependency:go-offline -q

# Now copy source code and build the fat JAR
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime image ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Create upload directories that the application needs at runtime
RUN mkdir -p uploads/originals uploads/thumbnails uploads/compressed

# Copy only the compiled JAR from the builder stage — nothing else
COPY --from=builder /build/target/conveyor-*.jar app.jar

# Document that the API server listens on port 8080
# (Workers don't use this port — it's just documentation for docker run)
EXPOSE 8080

# Environment variables with defaults — override in docker-compose.yml
ENV SPRING_PROFILES_ACTIVE=api

# Start the application
# The profile (api or worker) determines which main() class behavior runs
ENTRYPOINT ["java", "-jar", "app.jar"]
