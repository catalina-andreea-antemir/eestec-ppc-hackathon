# Notificări O8

Prometheus evaluează cele patru reguli de business la 5 s. După 15 s de încălcare continuă a condiției, trimite alerta către Alertmanager. Alertmanager așteaptă 10 s înainte de prima notificare a grupului și trimite stările `firing` și `resolved` la `POST http://alert-webhook:8080/alerts`.

Receiverul acceptă notificări JSON Alertmanager v4 și confirmă cu HTTP 204. În log apar starea, numărul de alerte și, pentru alertele FileGrid, numerele cunoscute din F7: 1 citiri, 2 avarii, 3 video, 4 documente. Valori din adnotări, URL-uri și etichete arbitrare nu sunt scrise în log.

## Date necesare

- **C12.** Alerta de avarii așteaptă histograma de operații. Alerta video așteaptă atât transferurile eșuate, deja contorizate parțial de C11, cât și seriile de operații reușite pentru `upload_complete` sau `download`. Alerta de citiri poate porni din metricile simulatorului; semnalul API pentru citiri fără confirmare așteaptă C12. Alerta documentelor folosește contorul C10.
- **MinIO.** Prometheus colectează metricile printr-o identitate dedicată. [Pornire și rotație](../../infra/minio/README.md).

## Validare

```sh
docker compose config -q
docker compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose exec -T prometheus promtool check rules /etc/prometheus/rules/business.yml
docker compose exec -T alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
docker run --rm -v "$PWD/observability/prometheus:/checks:ro" --entrypoint /bin/promtool \
  prom/prometheus:v3.13.5 test rules /checks/tests/business.test.yml
```
