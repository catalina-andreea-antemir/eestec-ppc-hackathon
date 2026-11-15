# Limitări cunoscute (code freeze)

- nginx nu este configurat pentru producție (rămâne cu setările de demo și certificat self-signed).
- Pot apărea deadlock-uri SQL Server în scenarii extreme de concurență; nu sunt tratate complet.
- `./mvnw test` simplu sare 30 de teste: `*SqlServerTest` nu folosesc Testcontainers, ci SQL Server-ul din stack (`127.0.0.1:1433`) și cer `-Dfilegrid.sql.integration=true`; `*StackTest` cer variabilele din `.env`.
- Rularea lor completă: stack pornit cu `-f infra/docker/docker-compose.dev.yml`, `.env` exportat în shell, apoi `./mvnw test -Dfilegrid.sql.integration=true`.
- Clasele de integrare pe stack durează împreună peste 10 minute (doar `UploadStackTest` ia ~3,5 minute).
- `simulator/meter-simulator` nu are Maven wrapper propriu; se rulează cu `../../backend/filegrid-api/mvnw -f pom.xml`.
- Upload-ul în bucăți numerotează bucățile de la 1 (`/uploads/{id}/chunks/1`), nu de la 0.
