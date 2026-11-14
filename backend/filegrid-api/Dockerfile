# syntax=docker/dockerfile:1
# Imaginea api (C3, planul v4 4.1 "Imagini si repo curat"): build Maven + JDK 21 in container, rulare pe JRE 21.
# Contextul de build este RADACINA repo-ului (ca sa copieze db/migrations):
#   docker build -f backend/filegrid-api/Dockerfile .
# Ce intra in context e filtrat de Dockerfile.dockerignore (langa acest fisier).

FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY backend/filegrid-api/pom.xml ./
# Cache BuildKit pentru ~/.m2: dependentele se descarca o singura data, si dupa schimbari in pom.xml.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -ntp dependency:go-offline || true
COPY backend/filegrid-api/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && cp target/filegrid-api-*.jar /tmp/app.jar

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system --gid 10001 filegrid \
    && useradd --system --uid 10001 --gid filegrid --home-dir /app --shell /usr/sbin/nologin filegrid
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
# Flyway (profilul db) gestioneaza doar FileGridMeta, din db/migrations copiat in imagine.
COPY db/migrations /app/db/migrations
ENV FLYWAY_LOCATIONS=filesystem:/app/db/migrations \
    SERVER_PORT=8080 \
    JAVA_OPTS=""
USER filegrid
EXPOSE 8080
# exec: java devine PID 1 si primeste SIGTERM la `docker compose stop`.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
