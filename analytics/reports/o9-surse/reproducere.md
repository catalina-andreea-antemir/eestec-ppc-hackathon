# O9 · Surse și reproducere

[Raport](../o9-interpretare.md) · [Exportul măsurătorilor](masuratori.json)

## Pornirea mediului

Cerința și protocoalele sunt documentate în [planul FileGrid, pag. 10–11](../../../docs/Impartirea-taskurilor-FileGrid.pdf), [contractul O7](../../../docs/api/o7-analytics.md) și [scenariile F8](../../../tests/load/README.md).

Codul măsurat este commitul consemnat în export. Din rădăcina checkoutului, pregătește un director temporar pentru rulare și un `test.env` cu credențiale noi, pe baza [modelului din repo](../../../infra/secrets-example/.env.example). Folosește `STORAGE_DB_HOST=mssql`. Override-ul din `environment.composeOverride` elimină porturile publicate ale nginx și atribuie imagini cu taguri dedicate. Alege un nume nou pentru fiecare rulare completă, ca fiecare serie să înceapă cu volume goale.

```bash
lab=$(mktemp -d)
# Completează $lab/test.env cu valorile de test înainte de pornire.
python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["environment"]["composeOverride"])' \
  analytics/reports/o9-surse/masuratori.json > "$lab/test.yml"
project=filegrid-o9-replay
dc() { docker compose --env-file "$lab/test.env" -p "$project" -f docker-compose.yml -f "$lab/test.yml" "$@"; }
dc --profile sim build api nginx ftp meter-simulator
dc up -d --no-build --wait --scale api=3 nginx minio-metrics-init prometheus
dc ps api mssql minio ftp nginx prometheus
```

În `FileGridMeta`, verifică RCSI prin `sys.databases.is_read_committed_snapshot_on` și confirmă că `dbo.files` și `dbo.operation_metrics` pornesc de la 0. Confirmă cele trei ținte `up{job="api"}` în Prometheus. Comenzile API pot fi executate cu `dc exec -T nginx curl -ksS` la `https://localhost/api/v1` din container. Autentifică rolul ADMIN prin `POST /auth/login` și adaugă tokenul prin headerul `Authorization: Bearer …`.

## Ordinea măsurătorilor

1. Rulează `POST /admin/benchmark/run` o dată și salvează JSON-ul. Capturează imediat `GET /stats/storage-advisor`. Această matrice O6 are numai mostre benchmark.
2. Copiază `tests/load/storm.js` și `tests/load/lib/` într-un director temporar `$lab/storm`, cu un subdirector `results` în care utilizatorul imaginii k6 poate scrie. Pune doar variabilele `FILEGRID_USER_*_PASSWORD` în `$lab/k6.env`. Rulează:

   ```bash
   docker run --network "$project"_storage-net --env-file "$lab/k6.env" \
     -e BASE_URL=https://nginx/api/v1 -e STORM_FACTOR=10 -e RUN_ID=o9-replay \
     -v "$lab/storm:/scripts" -w /scripts grafana/k6:1.3.0 \
     run --out json=results/storm-points.jsonl storm.js
   ```

   Colectează `GET /stats/peak-vs-normal`, `GET /stats/outage-zones` și `GET /stats/storage-advisor` după flush-ul recorderului. Rezumatul k6 apare în `results/`. Pentru rezumatul HTTP pe faze din export, grupează punctele `http_reqs` după `data.tags.phase`, `op` și `status`, însumează `filegrid_unexpected` și calculează percentilele cu interpolare liniară din `http_req_duration` pe aceeași fază și operație.
3. Cu `duration: PT90S` ca unică intrare în `$lab/sim-profile.yml`, rulează simulatorul secvențial, mai întâi cu `normal`, apoi cu `peak`:

   ```bash
   dc --profile sim run --no-deps -T -e SIM_PROFILE="$profile" \
     -e SIM_CONFIG=/o9-profile.yml -v "$lab/sim-profile.yml:/o9-profile.yml:ro" \
     meter-simulator
   ```

   Salvează UTC înainte de lansarea fiecărui container și după terminare, apoi colectează contoarele imprimate de simulator. Repetă cererile din `simulator.api` din export, inclusiv intervalele sintetice explicite pentru `/stats/anomalies`.

## Urmărirea cifrelor

- `benchmark.response` este răspunsul integral C12, cu latențele uploadurilor reușite și debitul în MiB/s. `benchmark.advisorBeforeLoad` este răspunsul O7 înainte de traficul live. `sql.benchmark-download` și `sql.metric-counts` verifică descărcările și eșecurile pe operație.
- `storm.api` păstrează răspunsurile O7 separat de `storm.k6`. O7 măsoară rânduri din `operation_metrics` pe ferestrele proprii. k6 măsoară HTTP pe fazele configurate.
- `simulator.runs` păstrează intervalele UTC și contoarele finale. Pentru O6-05 folosește începutul fiecărui container și sfârșitul plus 2 s pentru flush-ul recorderului, cu etichetele `normal` și `peak`. Pentru F8 folosește ferestrele din O7. Prin intervale disjuncte eviți cumularea celor două rulări cu eticheta `normal`.
- `sql` păstrează rezultatele `sqlcmd` delimitate cu `|`. Rulează scripturile [O6](../../../db/analytics-queries/README.md) în `FileGridMeta` cu parametrii din `sqlParameters`. `additionalSql` păstrează SQL-ul pentru volume, numărul de măsurători, respingerile simulatorului și distribuția descărcărilor. Ajustează prefixul k6 în interogarea de volume dacă ai schimbat `RUN_ID`.
- `prometheus` păstrează interogările și valorile per replică pentru verificarea colectării. În export, `live` este eticheta aplicației pentru trafic prin API. Toate payloadurile din această rulare provin din generatoare sintetice.
