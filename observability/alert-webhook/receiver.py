import json
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 1024 * 1024
STATUSES = ("firing", "resolved")
BUSINESS_ALERTS = {
    "FileGridUnconfirmedMeterReadings": "1",
    "FileGridOutageP95TooHigh": "2",
    "FileGridVideoTransferFailureRate": "3",
    "FileGridUnexpectedDocumentAccess": "4",
}


def validate_notification(payload):
    if (
        not isinstance(payload, dict)
        or payload.get("version") != "4"
        or payload.get("status") not in STATUSES
    ):
        raise ValueError("Invalid webhook envelope")

    alerts = payload.get("alerts")
    if not isinstance(alerts, list) or not alerts:
        raise ValueError("Expected a nonempty alerts list")

    business_ids = set()
    for alert in alerts:
        if not isinstance(alert, dict) or alert.get("status") not in STATUSES:
            raise ValueError("Invalid alert status")
        labels = alert.get("labels")
        if (
            not isinstance(labels, dict)
            or not labels.get("alertname")
            or not all(isinstance(value, str) for value in labels.values())
        ):
            raise ValueError("Expected string labels and an alertname")
        business_id = BUSINESS_ALERTS.get(labels["alertname"])
        if business_id is not None:
            business_ids.add(business_id)

    return payload["status"], len(alerts), ",".join(sorted(business_ids)) or "-"


class Receiver(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(5)
        super().setup()

    def log_message(self, format, *args):
        # BaseHTTPRequestHandler includes the raw request line in its access log.
        pass

    def respond(self, status, allow=None):
        self.send_response(status)
        if status != HTTPStatus.NO_CONTENT:
            self.send_header("Content-Length", "0")
        if allow is not None:
            self.send_header("Allow", allow)
        self.end_headers()

    def do_GET(self):
        if self.path == "/healthz":
            self.respond(HTTPStatus.OK)
        elif self.path == "/alerts":
            self.respond(HTTPStatus.METHOD_NOT_ALLOWED, allow="POST")
        else:
            self.respond(HTTPStatus.NOT_FOUND)

    def do_POST(self):
        if self.path == "/healthz":
            self.respond(HTTPStatus.METHOD_NOT_ALLOWED, allow="GET")
            return
        if self.path != "/alerts":
            self.respond(HTTPStatus.NOT_FOUND)
            return
        if self.headers.get_content_type() != "application/json":
            self.respond(HTTPStatus.UNSUPPORTED_MEDIA_TYPE)
            return
        if self.headers.get("Transfer-Encoding") is not None:
            self.respond(HTTPStatus.BAD_REQUEST)
            return

        lengths = self.headers.get_all("Content-Length", [])
        if not lengths:
            self.respond(HTTPStatus.LENGTH_REQUIRED)
            return
        length = lengths[0]
        if len(lengths) != 1 or not length.isascii() or not length.isdecimal():
            self.respond(HTTPStatus.BAD_REQUEST)
            return
        if len(length) > 7 or int(length) > MAX_BODY_BYTES:
            self.respond(HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
            return

        try:
            body = self.rfile.read(int(length))
            if len(body) != int(length):
                raise ValueError("Incomplete request body")
            status, count, business_ids = validate_notification(json.loads(body))
        except TimeoutError:
            self.respond(HTTPStatus.REQUEST_TIMEOUT)
            return
        except (ValueError, RecursionError):
            self.respond(HTTPStatus.BAD_REQUEST)
            return

        print(f"alert_notification status={status} alerts={count} business_alerts={business_ids}")
        self.respond(HTTPStatus.NO_CONTENT)


if __name__ == "__main__":
    with ThreadingHTTPServer(("0.0.0.0", 8080), Receiver) as server:
        server.serve_forever()
