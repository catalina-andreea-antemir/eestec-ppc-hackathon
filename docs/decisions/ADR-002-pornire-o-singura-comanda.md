# ADR-002: Pornirea întregului sistem cu o singură comandă

* Stare: acceptat (planul v4, secțiunea 4, rândul „Pornire”, și 4.1)
* Taskuri: C3, C10, O2, F7, F10

## Context

Cerința minimă: sistemul pornește pe o mașină curată cu o singură comandă, urmând README-ul. În același timp, nicio
parolă sau cheie nu are voie să existe în repository, iar sistemul are secrete multe (SQL Server, utilizatori DB,
MinIO, FTP, Grafana, cheia JWT, cheia AES, utilizatori de test).

## Decizie

Comanda unică, din rădăcina repo-ului:

```bash
./scripts/start.sh        # Linux / macOS / WSL
```

```powershell
.\scripts\start.ps1       # Windows PowerShell
```

Scriptul:

1. verifică versiunea Docker Compose (minim 2.20, pentru `include`);
2. creează `.env` cu parole și chei **aleatorii** dacă lipsește (fișier ignorat de Git);
3. rulează `docker compose up -d --build`;
4. așteaptă `GET /api/v1/health` (fără autentificare, `{"status":"UP"}` când `api` și `FileGridMeta` răspund);
5. afișează adresa (`https://localhost`) și utilizatorii de test, câte unul per rol, cu parolele generate.

Prima construire a imaginilor durează câteva minute.

Cum se leagă piesele:

* `docker-compose.yml` din rădăcină **include** `infra/docker/docker-compose.yml`. `infra/docker/docker-compose.dev.yml`
  (activat explicit cu `-f`) publică porturi doar pe `127.0.0.1`, de exemplu SQL Server pe 1433 pentru SSMS.
* **Ordinea pornirii**: `api` așteaptă `mssql-init` (`service_completed_successfully`), iar `mssql-init` așteaptă
  healthcheck-ul `sqlcmd` al lui `mssql` (~20–40 s), este idempotent și iese cu cod 0. Flyway (din `api`) gestionează doar
  `FileGridMeta`, din `db/migrations`, copiat în imagine (context de build în rădăcină).
* **TLS**: certificatul self-signed îl generează containerul `nginx` la prima pornire, într-un volum (nu se comite).
  Portul 80 redirecționează la HTTPS; din exterior se vede doar `nginx`.
* **Build fără Java local**: Dockerfile multi-stage pentru `api` și `meter-simulator` (Maven + JDK 21, apoi JRE, cache
  BuildKit), ca build-ul să ruleze în container.
* **Repo curat pe Windows**: `.gitattributes` cu `eol=lf` pentru `*.sh`, `Dockerfile`, `*.yml`, `*.sql` (scripturile cu
  CRLF pică în containere).
* **Secrete**: `infra/secrets-example/.env.example` conține doar **numele** variabilelor, fără valori. Grafana: parola
  din `.env`, acces anonim dezactivat, la `/grafana`.
* **Resetare**: `docker compose down -v` șterge volumele numite (`mssql`, `minio`, `ftp`, `grafana`, `prometheus`,
  `loki`) și readuce demo-ul la zero.

## Alternative respinse

* **Doar `docker compose up`**: ar cere un `.env` existent; fără script, secretele ar trebui scrise de mână sau comise.
  Scriptul le generează aleatoriu și le ține în afara Git.
* **Parole implicite în repo** („doar pentru demo”): interzise, nicio parolă sau cheie nu se află în repository.

## Consecințe

* `main` trebuie să pornească mereu cu comanda de start; ce strică pornirea nu se integrează (planul 5.1,
  „sistem funcțional întâi”).
* README-ul (F10) se verifică pas cu pas pe un alt calculator; repetiția finală include `docker compose down -v` și
  repornirea de la zero.
