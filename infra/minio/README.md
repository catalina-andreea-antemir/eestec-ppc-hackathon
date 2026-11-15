# Metrici MinIO în Prometheus

Testat cu imaginea Chainguard fixată în Compose, MinIO AGPL `RELEASE.2026-11-12T01-15-23Z` și clientul `mc DEVELOPMENT.GOGET` din aceeași imagine. Combinația `mc admin prometheus generate --api-version v2` și politica `admin:Prometheus` permite accesul autentificat la `/minio/v2/metrics/cluster` și refuză accesul S3 pentru identitatea de monitorizare.

`minio-metrics-init` folosește contul administrativ existent pentru provisioning. Creează o identitate dedicată cu dreptul `admin:Prometheus` și verifică JWT-ul direct la endpoint înainte de publicare. Identitățile generate au prefixul `filegrid-prometheus-`, rezervat acestui serviciu. Tokenul se păstrează într-un volum separat, cu fișierul `0440 root:65534`, montat read-only în Prometheus. Prometheus citește fișierul la scrape. Jobul `minio` moștenește intervalul de 5 s și folosește doar rețeaua internă. Pornirea folosește aceleași variabile și aceleași scripturi ca înainte.

## Rotație

Din rădăcina repository-ului, cu MinIO healthy, rulează comanda de mai jos. Rulează inițializarea și rotația serial, în proiectul Compose țintă.

```sh
docker compose run --rm --no-deps minio-metrics-init --rotate
```

Rotația verifică noul token, îl publică printr-o schimbare atomică a referinței și apoi revocă identitatea precedentă. Prometheus preia noul token la următorul scrape. Confirmă `up{job="minio"} == 1` și prezența metricilor `minio_*`. Scriptul validează și reutilizează tokenul existent la pornirile obișnuite. Dacă publicarea reușește, dar revocarea eșuează, rerulează `docker compose run --rm --no-deps minio-metrics-init` pentru a termina revocarea. Dacă tokenul existent devine invalid, verifică starea MinIO și rulează rotația explicită.

Ține volumul de autentificare în perimetrul secretelor. Clientul `mc` emite tokenuri cu expirare lungă, iar rotația revocă imediat identitatea veche.
