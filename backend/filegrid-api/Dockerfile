# syntax=docker/dockerfile:1
# API image: Maven + JDK 21 build stage, JRE 21 runtime.
# The build context is the REPO ROOT so db/migrations can be copied:
#   docker build -f backend/filegrid-api/Dockerfile .
# The context is filtered by Dockerfile.dockerignore (next to this file).

FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /src/backend/filegrid-api
COPY backend/filegrid-api/pom.xml ./
# BuildKit cache for ~/.m2: dependencies are downloaded once and survive pom.xml changes.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -ntp dependency:go-offline || true
COPY backend/filegrid-api/src ./src
COPY db/analytics-queries /src/db/analytics-queries
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && cp target/filegrid-api-*.jar /tmp/app.jar

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system --gid 10001 filegrid \
    && useradd --system --uid 10001 --gid filegrid --home-dir /app --shell /usr/sbin/nologin filegrid
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
# Flyway (db profile) only manages FileGridMeta, from the migrations baked into the image.
COPY db/migrations /app/db/migrations
# Per-category policies (4.2). docker-compose mounts the repo directory over this one so YAML edits are
# picked up without a restart (PolicyEngine); without the mount the baked-in files are used.
COPY backend/filegrid-api/src/main/resources/policies /app/policies
ENV FLYWAY_LOCATIONS=filesystem:/app/db/migrations \
    FILEGRID_POLICY_DIR=/app/policies \
    SERVER_PORT=8080 \
    JAVA_OPTS=""
USER filegrid
EXPOSE 8080
# exec: java becomes PID 1 and receives SIGTERM on `docker compose stop`.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
