# syntax=docker/dockerfile:1
FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app
COPY --from=build /src/target/meter-simulator.jar ./meter-simulator.jar
ENV SIM_API_BASE_URL=http://api:8080/api/v1 \
    SIM_PROFILE=normal
USER 10001:10001
EXPOSE 9400
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/meter-simulator.jar"]
