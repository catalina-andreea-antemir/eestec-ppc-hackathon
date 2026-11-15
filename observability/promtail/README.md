# Loguri API în Loki

În Compose, API-ul păstrează logurile Logstash pe stdout și le scrie și în volumul `api-logs`. Logback folosește un fișier append-only pentru fiecare combinație de hostname al containerului, zi UTC și index de rotație. Rotația trece la o cale nouă, astfel că Promtail poate relua fiecare fișier după offset chiar dacă în timpul opririi lui au apărut fișiere noi.

Promtail rulează fără privilegii și citește volumul în mod read-only. Pozițiile se salvează la 2 s pe volumul separat `promtail-positions`. Eticheta `replica` conține hostname-ul containerului API, iar `filename` separă fișierele rotite în stream-uri distincte. `traceId` rămâne în linia JSON și se caută cu LogQL.

```sh
docker compose exec -T api curl -fsSG http://loki:3100/loki/api/v1/query_range \
  --data-urlencode 'query={job="filegrid-api"} | json | traceId="TRACE_ID"'
```

Loki rulează într-un singur nod, cu TSDB și WAL pe volumul `loki` și retenție de 72 h prin compactor. Limitele Compose sunt 512 MiB pentru Loki și 128 MiB pentru Promtail. Logback rotește fișierele la 8 MB sau la schimbarea zilei UTC și aplică o limită de 64 MB pentru arhivele fiecărei replici, cu istoric de 3 zile.

Recrearea unui container API îi schimbă hostname-ul. Fișierele rămase de la replicile înlocuite rămân în volumul sursă și se gestionează separat de retenția Loki. La oprire forțată sau după expirarea reîncercărilor de livrare poate exista o diferență între offsetul citit de Promtail și ultimul lot confirmat de Loki.

Promtail 3.6.11 este fixat pentru hackathon. Grafana a încheiat suportul Promtail pe 2 martie 2026. Pentru o instalare întreținută, succesorul este Grafana Alloy. [Starea Promtail](https://grafana.com/docs/loki/latest/send-data/promtail/)
