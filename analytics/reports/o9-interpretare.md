# FileGrid PPC · Raport O9

## 1. Metodologie

Pe **15 noiembrie 2026, 07:07–07:16 EET**, am măsurat commitul `cbde362` în proiectul Compose izolat `filegrid-o9-20261115`, pornit cu volume goale. Stackul local avea trei replici API sănătoase (512 MiB/replică), nginx, SQL Server 2022, MinIO și vsftpd. `FileGridMeta` avea RCSI activ. Benchmarkul a efectuat 693 de perechi upload/download, câte 515,9 MiB pe sens. F8 `storm` a rulat prin nginx 120 s normal + 120 s furtună. Simulatorul a rulat direct spre API, 90 s configurate per profil, cu 12/120 contoare (normal/vârf), 96 citiri/lot și interval țintă de 5/1 s. La final, SQL conținea 7.407 fișiere confirmate (86,7 MiB). Datele de intrare sunt sintetice, iar timpii, volumele confirmate și erorile provin din execuția efectivă. [Export și reproducere](o9-surse/reproducere.md)

## 2. Comparația DB / S3 / FTP

Fiecare celulă arată **p50 / p95 (ms) · MiB/s** pentru uploadurile reușite. Eșantioanele sunt per backend.

| Categorie (eșantioane × mărime) | DB | S3 | FTP |
| --- | ---: | ---: | ---: |
| Citiri (200 × 10 KiB) | 5,76 / 9,35 · 1,56 | 5,49 / 7,26 · 1,61 | 39,11 / 45,22 · 0,24 |
| Avarii (10 × 5 MiB) | 115,70 / 181,10 · 39,84 | 28,62 / 42,07 · 155,84 | 44,70 / 54,60 · 107,53 |
| Documente (20 × 1 MiB) | 16,91 / 45,89 · 41,57 | 11,19 / 14,96 · 84,91 | 39,86 / 46,33 · 24,05 |
| Video (1 × 100 MiB) | 893,93 / 893,93 · 111,87 | 511,31 / 511,31 · 195,57 | 455,53 / 455,53 · 219,52 |

Zero eșecuri la upload și download în cele 12 combinații. Și la download, p95 a fost minim pentru S3 la citiri, avarii și documente, respectiv pentru FTP la video. Costul relativ configurat/GB este DB **3,2**, S3 **1,4**, FTP **1,0**. Costul facturat este indisponibil. [Benchmark C12 și verificare SQL](o9-surse/masuratori.json)

## 3. Recomandare de stocare

| Categorie | Alegere și justificare |
| --- | --- |
| Citiri | **Păstrăm DB ca stocare principală** pentru tranzacțiile SQL, deduplicarea loturilor și consistența confirmărilor la ingestie. S3 a fost mai rapid în benchmark (p95 7,26 ms față de 9,35 ms pentru DB) și rămâne stocarea de rezervă. |
| Avarii | **S3**. Cel mai mic p95 și cel mai mare debit la 5 MiB. Politica actuală folosește deja S3. |
| Documente | **S3, de validat**. Cel mai mic p95 și cel mai mare debit la 1 MiB. Benchmarkul a apelat adaptorul direct. Recomand păstrarea DB până la o probă a fluxului AES-GCM pe S3. |
| Video | **FTP, provizoriu**. A avut cel mai mare debit și are costul relativ minim. Benchmarkul a avut un singur transfer de 100 MiB per backend, deci p95 este nereprezentativ pentru încărcare susținută. |

Clasamentul O7 a rămas S3/S3/S3/FTP după adăugarea celor 7.358 de uploaduri live la cele 693 de mostre de benchmark. [Scoruri O6/O7 și politici](o9-surse/masuratori.json)

## 4. Zi normală vs. vârf

| F8 `normal → storm` | Operații stocare/min | p95 stocare (ms) | p95 HTTP upload/ingestie/bucată (ms) |
| --- | ---: | ---: | ---: |
| Citiri | 299,3 → 298,5 | 12,0 → 12,3 | 68,6 → 86,4 |
| Avarii | 161,3 → 1.232,9 | 16,4 → 11,3 | 39,2 → 27,4 |
| Documente | 89,1 → 93,4 | 20,1 → 14,5 | 56,0 → 42,2 |
| Video | 12,4 → 11,9 | 49,0 → 219,9 | 76,0 → 80,6 |

Toate pragurile k6 au trecut. Din 5.354 cereri HTTP, 0 au eșuat și 0 au fost respinse de bulkhead. Uploadurile de avarii au crescut **241 → 2.400** (~10×). O7 numără și descărcările, iar p95 video de stocare s-a bazat pe 25 → 24 operații. [O6-05, O7 și k6](o9-surse/masuratori.json)

În simulator, citirile au trecut de la **137,8 la 1.934,6 operații/min**, cu p95 de stocare **24,8 → 37,2 ms** și 0% erori de stocare. Respingerile bulkhead și reîncercările au crescut fiecare de la **0 la 756**. Simulatorul a confirmat 20.736 → 322.560 de citiri, inclusiv retrimiterile duplicate, cu 0 abandonuri. SQL confirmă 293.568 de citiri distincte la vârf. O7 a răspuns 503 pentru comparația cu `peak`, fiindcă profilul nu exercită celelalte trei categorii. [Contoarele simulatorului, O6-05 și Prometheus](o9-surse/masuratori.json)

## 5. Consum, anomalii și avarii pe zone

| Zonă | Consum normal (kWh) | Consum vârf (kWh) | Anomalii normal → vârf | Avarii F8 |
| --- | ---: | ---: | ---: | ---: |
| Banat | 86,63 | 997,86 | 0 → 1 | 875 |
| Dobrogea | 87,38 | 1.007,85 | 0 → 5 | 892 |
| Muntenia | 105,35 | 963,47 | 0 → 1 | 874 |

Consumul și anomaliile acoperă prima zi simulată din fiecare profil, 15 septembrie și 18 ianuarie 2026 UTC. Vârful are 40 de contoare/zonă față de 4, deci consumul agregat reflectă cohorte de mărimi diferite. Regula mediană + 3 deviații standard a găsit 7 anomalii la vârf. La finalul rulării, O6 a găsit 7 contoare din cohorta de vârf inactive de peste 1 minut și 0 la pragul standard de 60 de minute. Avariile F8 sunt sintetice, cu zonă aleasă aleator: Dobrogea are 33,8% din 2.641 de înregistrări. Ora maximă (05 UTC în toate zonele) reflectă fereastra testului. [O6-01–04 și răspunsurile de zonă](o9-surse/masuratori.json)

## 6. Concluzii și limite

Pentru validarea la scară, probele prioritare sunt ingestia susținută cu reîncercări și repetarea transferurilor video pe fișiere mai mari. Recomandările de stocare se bazează pe o singură rulare locală, cu payloaduri generate. Concluziile despre zone se limitează la scenariile sintetice. [Intervale și date sursă](o9-surse/reproducere.md)
