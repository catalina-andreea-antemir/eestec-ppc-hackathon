# FileGrid Meter Simulator

## Run locally

From the repository root, use Java 21 and the existing Maven wrapper.

```bash
bash backend/filegrid-api/mvnw -f simulator/meter-simulator/pom.xml package
```

Supply the credentials for the API's `METER_SERVICE` account. The backend's seeded username is `contor` and its password comes from `FILEGRID_USER_CONTOR_PASSWORD` in the generated environment.

```bash
export METER_SERVICE_USERNAME=contor
read -rs -p 'Meter service password: ' METER_SERVICE_PASSWORD
export METER_SERVICE_PASSWORD
export SIM_API_BASE_URL=http://localhost:8080/api/v1

SIM_PROFILE=normal java -jar simulator/meter-simulator/target/meter-simulator.jar
SIM_PROFILE=peak java -jar simulator/meter-simulator/target/meter-simulator.jar
```

Set `SIM_API_BASE_URL` to the base URL of a reachable API or mock. For HTTPS, use a Java trust store that trusts the server certificate. On the Compose network use `http://api:8080/api/v1`.

| Profile | Meters | Target time between rounds | Duration | Abnormal | Silent | Duplicate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `normal` | 12 | 5 s | 5 min | 0% | 0% | 0% |
| `peak` | 120 | 1 s | 5 min | 5% | 5% | 10% |

Set `SIM_CONFIG` to a YAML file to override any settings from the selected [normal](src/main/resources/profiles/normal.yml) or [peak](src/main/resources/profiles/peak.yml) profile. For example:

```yaml
meters: 30
firstMeterNumber: 9200001
sendingInterval: PT2S
duration: PT1M
readingsPerFile: 96
firstReadingAt: "2026-11-01T00:00:00Z"
scenario: storm
abnormalPercent: 10
silentPercent: 5
duplicatePercent: 20
```

- Each round produces one batch per active meter, with 15–700 readings per batch and 15 minutes between readings. A run advances through synthetic time at the configured sending interval. Repeating a profile with the same settings produces the same keys and payloads. Use disjoint meter number ranges or non-overlapping UTC time ranges for runs that should ingest new data.
- Percentages select meters or batches deterministically. A silent meter sends its first batch and then stops. An abnormal batch contains one spike. A selected duplicate replays a confirmed batch during the configured duration.
- Up to 64 deliveries run concurrently. A slow round can lower the actual sending rate. When the duration elapses, active deliveries can finish within their existing retry windows. Pending batches live in memory for the life of the process.
- Scrape `GET /metrics` on port 9400 during the run. Set `SIM_METRICS_PORT` to change the port. The simulator prints its final metrics at exit. Intentional duplicates count as separate submissions. HTTP 201 and 200 confirm readings, and each automatic resend increments the batch retry counter.
- Transient transport failures and HTTP 429/503 use the same exponential backoff and ten-minute deadline. Each resend keeps the original payload and idempotency key. After a delivery ends, its readings leave `sim_readings_pending`. A delivery that ends without confirmation adds its readings once to `sim_readings_abandoned_total`, including terminal HTTP responses, permanent communication errors, exhausted retries, and shutdown interruption.

## Test without C7

```bash
bash backend/filegrid-api/mvnw -f simulator/meter-simulator/pom.xml test
```

The tests use a loopback mock server for login, refresh, ingestion, retries and both profiles. The retry tests advance a test clock to verify the ten-minute cutoff.

## Docker and Compose

```bash
docker build -t filegrid/meter-simulator:local simulator/meter-simulator
```

For Catalina to add under `services` in `infra/docker/docker-compose.yml`:

```yaml
  meter-simulator:
    profiles: ["sim"]
    build:
      context: ../../simulator/meter-simulator
    image: filegrid/meter-simulator:local
    environment:
      SIM_PROFILE: ${SIM_PROFILE:-normal}
      SIM_API_BASE_URL: http://api:8080/api/v1
      METER_SERVICE_USERNAME: ${METER_SERVICE_USERNAME:-contor}
      METER_SERVICE_PASSWORD: ${FILEGRID_USER_CONTOR_PASSWORD:-}
    networks:
      - storage-net
    depends_on:
      api:
        condition: service_healthy
    mem_limit: 256m
```

Once integrated, run `SIM_PROFILE=normal docker compose --profile sim up --build meter-simulator` or set `SIM_PROFILE=peak` for peak mode. For O8, use the internal Prometheus target `meter-simulator:9400` with `metrics_path: /metrics`.
