# FileGrid PPC pe Windows 11

## 1. Pregătirea calculatorului

- Ai nevoie de Git, o copie locală a repository-ului, acces la internet pentru prima descărcare și Docker Desktop pornit în modul **Linux containers**. Imaginea SQL Server 2022 din proiect are suport oficial pe x86-64. [Instalare Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) · [Platforme suportate pentru SQL Server în containere](https://learn.microsoft.com/sql/linux/sql-server-linux-docker-container-deployment?view=sql-server-ver16)
- **Ubuntu WSL.** Instalează Ubuntu în WSL 2 dacă lipsește. În Docker Desktop, activează **Settings → General → Use the WSL 2 based engine**, apoi **Settings → Resources → WSL Integration → Ubuntu** și aplică setările. [Instalarea WSL și Ubuntu](https://learn.microsoft.com/en-us/windows/wsl/install) · [Configurarea integrării WSL](https://docs.docker.com/desktop/features/wsl/)
- **Windows PowerShell.** Scriptul merge în Windows PowerShell 5.1 și PowerShell 7. Pentru un calculator fără WSL, instalează Docker Desktop în modul **All users** și selectează backendul **Hyper-V** pentru containere Linux. Ai nevoie de o ediție Windows compatibilă, precum Pro sau Enterprise, de virtualizare hardware activată și de funcțiile **Hyper-V** și **Containers** din **Turn Windows features on or off**. Poți folosi PowerShell direct din Windows și dacă Docker Desktop folosește motorul WSL 2. [Cerințe Docker Desktop pe Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- Instalează **SQL Server Management Studio (SSMS)** în Windows dacă vrei să verifici bazele de date. SQL Server rulează în Docker. Imaginile de build includ Java, Maven și Node.js. [Instalare SSMS](https://learn.microsoft.com/en-us/ssms/install/install)

Pornește Docker Desktop și așteaptă să fie gata motorul. În terminalul în care vei lucra, verifică motorul și versiunea Compose. Prima comandă trebuie să afișeze `linux`. Scripturile cer Compose **2.20 sau mai nou**. Folosește o versiune actualizată de Docker Desktop, inclusiv pentru overlay-ul de dezvoltare.

```text
docker info --format '{{.OSType}}'
docker compose version --short
```

Rulează scriptul într-un terminal privat. Scripturile afișează la final parolele conturilor demo, așa că ține rezumatul în afara capturilor, logurilor distribuite și partajării de ecran.

## 2. Pornire rapidă din Ubuntu WSL

Deschide profilul **Ubuntu** din Windows Terminal. Navighează în folderul care conține `docker-compose.yml` și directorul `scripts`. În exemplul de mai jos, înlocuiește `NUME_WINDOWS` cu numele folderului tău din `C:\Users`. Dacă ai clonat proiectul în directorul personal din Ubuntu, folosește `cd ~/eestec-ppc-hackathon`.

```bash
cd "/mnt/c/Users/NUME_WINDOWS/eestec-ppc-hackathon"
./scripts/start.sh
docker compose ps
```

Dacă sistemul refuză executarea directă cu `Permission denied`, rulează `bash ./scripts/start.sh` din același director.

## 3. Pornire rapidă din Windows PowerShell

În File Explorer, deschide folderul proiectului, fă clic dreapta într-un spațiu liber și alege **Open in Terminal**, apoi selectează profilul **Windows PowerShell** sau **PowerShell**. Dacă ai deschis PowerShell separat, folosește comanda de mai jos și adaptează calea la locul unde ai clonat proiectul.

```powershell
Set-Location "$env:USERPROFILE\eestec-ppc-hackathon"
.\scripts\start.ps1
docker compose ps
```

Dacă primești `running scripts is disabled on this system`, poți aplica `RemoteSigned` doar în sesiunea curentă. Pe un calculator administrat, o politică de grup poate avea prioritate. [Politicile de execuție PowerShell](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies/)

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned
.\scripts\start.ps1
```

## 4. Ce se întâmplă la prima pornire

1. Scriptul creează `.env` în rădăcina repository-ului și generează parolele și cheile locale. Dacă fișierul există, completează variabilele necesare care lipsesc sau sunt goale și păstrează valorile existente. `infra/secrets-example/.env.example` este lista de variabile.
2. Scriptul execută `docker compose up -d --build`. Compose descarcă imaginile necesare, construiește imaginile locale și pornește containerele în fundal. Prima descărcare și construire pot dura câteva minute, în funcție de conexiune și de calculator. Pornirile ulterioare pot reutiliza imaginile și cache-ul de build.
3. După ce `mssql` trece verificarea de sănătate, `mssql-init` pregătește `FileGridMeta` și `FileGridStorage`, conturile aplicației și `dbo.blob_chunks` în `FileGridStorage`. La pornirea API-ului, Flyway aplică automat `db/migrations/V1__init.sql` în `FileGridMeta`. Migrarea manuală ar dubla munca făcută de Flyway. În SSMS vei rula doar verificări.
4. Scriptul așteaptă ca `api` să fie `healthy` și, dacă are `curl` sau `curl.exe`, verifică răspunsul HTTP `200` prin nginx.

Parola unei variabile din `.env` este **valoarea aflată după `=`**. De exemplu, `MSSQL_SA_PASSWORD` este numele câmpului pe care îl cauți în editor. Git ignoră `.env`. Păstrează fișierul local alături de volume. Nu afișa, nu distribui și nu comite secretele.

Din rădăcina proiectului, verifică serviciile cu aceste comenzi, valabile în ambele terminale.

```text
docker compose ps
docker compose ps -a
```

`mssql`, `api` și `nginx` ar trebui să ajungă la `healthy`. `ps -a` include și containerele care s-au încheiat. Pentru `mssql-init` și `minio-init`, `Exited (0)` înseamnă că inițializarea s-a terminat cu succes.

## 5. Aplicația și verificarea API-ului

- Deschide [FileGrid](https://localhost/) în browserul din Windows. Pentru autentificare poți folosi contul `dispecer`, cu valoarea `FILEGRID_USER_DISPECER_PASSWORD` din `.env`.
- Deschide [endpointul de verificare](https://localhost/api/v1/health). Răspunsul așteptat este HTTP `200` cu `{"status":"UP"}`. Endpointul este public și verifică API-ul și conexiunea la `FileGridMeta`.

nginx folosește un certificat autosemnat pentru `localhost`. Dacă browserul afișează un avertisment, verifică adresa și folosește opțiunea **Advanced / Avansat** pentru a continua la `https://localhost`, dacă browserul o oferă.

Pentru aceeași verificare în terminal, `-k` permite certificatul autosemnat la această cerere locală.

**Ubuntu WSL**

```bash
curl -kfsS https://localhost/api/v1/health
```

**Windows PowerShell**

```powershell
curl.exe -kfsS https://localhost/api/v1/health
```

## 6. Activează accesul local la SQL Server pentru SSMS

În configurația implicită, ambele baze sunt în același container SQL Server. După prima pornire prin script, rulează din rădăcina proiectului aceste comenzi în Ubuntu WSL sau PowerShell. Cele două opțiuni `-f` încarcă configurația de bază și overlay-ul de dezvoltare pentru același proiect, `filegrid`.

```text
docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml up -d
docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml ps
```

Compose poate recrea `mssql` și `minio` ca să publice porturile, folosind aceleași volume de date. Așteaptă ca `mssql` să revină la `healthy` și verifică în coloana `PORTS` maparea `127.0.0.1:1433->1433/tcp`.

Overlay-ul publică SQL Server pe `127.0.0.1:1433` și MinIO pe `127.0.0.1:9000` și `127.0.0.1:9001`. Pe un Docker Desktop actualizat, aceste porturi sunt accesibile doar de pe calculatorul tău. nginx are porturile `80` și `443` în configurația de bază. [Publicarea porturilor în Docker](https://docs.docker.com/engine/network/port-publishing/)

## 7. Conectarea din SSMS

1. În Windows, deschide SSMS și alege **Object Explorer → Connect → Database Engine**.
2. La **Server name**, introdu `tcp:127.0.0.1,1433`, cu virgulă înaintea portului. Prefixul `tcp:` indică protocolul de conectare.
3. La **Authentication**, alege **SQL Server Authentication**. La **Login** sau **User name**, introdu `sa`.
4. La **Password**, introdu valoarea `MSSQL_SA_PASSWORD` din `.env` din rădăcina repository-ului. Deschide fișierul într-un editor local și copiază numai valoarea după `=`.
5. În SSMS 20 sau mai nou, la **Connection Security**, lasă **Encryption** pe **Mandatory** și bifează **Trust server certificate** pentru conexiunea locală. În interfața mai veche, opțiunea se găsește la **Options → Connection Properties**. [Conectare în SSMS](https://learn.microsoft.com/en-us/ssms/quickstarts/ssms-connect) · [Opțiunea Trust server certificate](https://learn.microsoft.com/en-us/troubleshoot/sql/database-engine/connect/error-message-when-you-connect)
6. Apasă **Connect** și extinde **Databases** în Object Explorer. Dacă lista era deja deschisă, dă clic dreapta pe **Databases** și alege **Refresh**.

### Verificări SQL doar în citire

După conectare, apasă **New Query**, lipește blocul de mai jos și apasă **Execute** sau `F5`. Poți rula întregul bloc într-o singură fereastră.

```sql
-- 1. Ambele baze trebuie să apară cu starea ONLINE.
SELECT name, state_desc
FROM sys.databases
WHERE name IN (N'FileGridMeta', N'FileGridStorage')
ORDER BY name;

-- 2. Cele șapte tabele create de V1 în dbo.
SELECT TABLE_SCHEMA, TABLE_NAME
FROM FileGridMeta.INFORMATION_SCHEMA.TABLES
WHERE TABLE_TYPE = 'BASE TABLE'
  AND TABLE_SCHEMA = N'dbo'
  AND TABLE_NAME IN (
      N'audit_log', N'files', N'meter_readings', N'operation_metrics',
      N'outage_records', N'upload_chunks', N'upload_sessions'
  )
ORDER BY TABLE_NAME;

-- 3. Așteaptă un rând cu version = 1, script = V1__init.sql și success = 1.
SELECT [version], [script], [success]
FROM FileGridMeta.dbo.flyway_schema_history
WHERE [version] = N'1';

-- 4. Așteaptă un rând pentru dbo.blob_chunks.
SELECT TABLE_SCHEMA, TABLE_NAME
FROM FileGridStorage.INFORMATION_SCHEMA.TABLES
WHERE TABLE_TYPE = 'BASE TABLE'
  AND TABLE_SCHEMA = N'dbo'
  AND TABLE_NAME = N'blob_chunks';
```

## 8. Loguri, repornire și oprire

Rulează comenzile din rădăcina proiectului. `--tail 100` afișează ultimele 100 de linii per serviciu.

```text
docker compose logs --tail 100 mssql mssql-init
docker compose logs --tail 100 minio minio-init ftp
docker compose logs --tail 100 api nginx
```

Pentru a reporni doar API-ul deja creat, rulează comanda de mai jos, apoi verifică din nou `docker compose ps` și endpointul HTTPS.

```text
docker compose restart --no-deps api
```

Pentru oprirea întregului proiect, alege comanda care corespunde configurației cu care l-ai pornit.

**Configurația de bază**

```text
docker compose down
```

**Configurația cu overlay**

```text
docker compose -f docker-compose.yml -f infra/docker/docker-compose.dev.yml down
```

`down` oprește și elimină containerele și rețelele proiectului și păstrează volumele numite. Pentru o repornire completă, rulează apoi scriptul terminalului tău (`./scripts/start.sh` sau `.\scripts\start.ps1`) și reaplică overlay-ul dacă ai nevoie de SSMS. **Evită `docker compose down -v`**. Opțiunea `-v` șterge volumele numite și datele persistente din ele, inclusiv bazele SQL. [Comanda Docker Compose down](https://docs.docker.com/reference/cli/docker/compose/down/)

## 9. Depanare

| Ce vezi | Ce verifici |
| --- | --- |
| Inițial nu rulează niciun container | Un `docker compose ps` gol este normal înainte de prima pornire. Deschide Docker Desktop, intră în rădăcina repository-ului și rulează scriptul din secțiunea terminalului tău. Dacă ai rulat deja scriptul, folosește `docker compose ps -a` pentru a vedea și containerele oprite. |
| Compose avertizează că lipsesc variabile | O comandă Compose dată prea devreme poate citi configurația înainte să existe `.env`. Rulează scriptul de pornire, care generează sau completează fișierul înainte de `up`. |
| Descărcarea sau buildul durează câteva minute | Lasă scriptul să termine cât timp descărcarea sau buildul progresează. La o eroare explicită de descărcare, verifică internetul și spațiul pe disc, apoi rulează din nou același script. |
| SQL Server arată `health: starting` | Așteaptă să devină `healthy` înainte de a conecta SSMS. Dacă rămâne blocat sau devine `unhealthy`, rulează `docker compose logs --tail 100 mssql mssql-init`. |
| SSMS afișează eroarea 1225 sau `connection refused` | Verifică exact `tcp:127.0.0.1,1433`, maparea din `PORTS` și starea `healthy` a lui `mssql`. Numele `mssql` este pentru comunicația dintre containere. Dacă lipsește maparea, aplică overlay-ul. |
| Portul `1433` este deja ocupat | Folosește `docker ps --format "table {{.Names}}\t{{.Ports}}"` și caută un alt container care publică portul `1433` al gazdei. Dacă este al tău și îl poți opri, folosește **Stop** în Docker Desktop, apoi reaplică overlay-ul. Dacă lista nu arată un container, verifică în Windows dacă rulează o altă instanță SQL Server. |
| Ai uitat parola `sa` | Caută valoarea `MSSQL_SA_PASSWORD` în `.env` local. Păstrează fișierul original împreună cu volumul SQL. Dacă `.env` a fost regenerat, o parolă nouă poate să nu corespundă bazei deja inițializate. Încearcă să recuperezi fișierul `.env` folosit inițial. |
| Browserul avertizează despre certificatul HTTPS | Verifică să fii pe `https://localhost` și acceptă excepția doar pentru această adresă locală. |
| API-ul nu devine `healthy` | Rulează `docker compose ps -a` și `docker compose logs --tail 100 api mssql-init minio-init ftp`. Caută întâi un serviciu de inițializare ieșit cu un cod diferit de `0`, apoi erorile de conectare la SQL sau de migrare Flyway. Dacă API-ul este `healthy`, dar ruta HTTPS eșuează, consultă `docker compose logs --tail 100 nginx`. |

## 10. Checklist pentru ziua demo-ului

- [ ] Docker Desktop rulează cu motorul de containere Linux și terminalul este în rădăcina repository-ului.
- [ ] Am rulat scriptul de pornire din terminalul ales, înainte să partajez ecranul.
- [ ] `docker compose ps` arată `mssql`, `api` și `nginx` cu starea `healthy`.
- [ ] `https://localhost/api/v1/health` răspunde cu `200` și `{"status":"UP"}`.
- [ ] Interfața se deschide la `https://localhost/` și mă pot autentifica cu contul demo.
- [ ] Dacă demonstrez și SQL, overlay-ul este activ, SSMS se conectează, iar cele patru verificări SQL dau rezultatele așteptate.
- [ ] Am pregătit comanda `down` potrivită, fără `-v`, pentru oprirea de după demo.
