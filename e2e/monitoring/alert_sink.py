"""Local-only Alertmanager webhook sink. Stores redacted state transitions only."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading


LOCK = threading.Lock()
EVENTS = []
LOG_PATH = Path(os.environ.get("ALERT_SINK_LOG", "/tmp/alert-events.jsonl"))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        return

    def reply(self, status, body):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ok"})
            return
        if self.path == "/events":
            with LOCK:
                self.reply(200, {"events": list(EVENTS)})
            return
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/alerts":
            self.reply(404, {"error": "not found"})
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            events = []
            for item in body.get("alerts", []):
                labels = item.get("labels", {})
                event = {"alertname": labels.get("alertname", "unknown"), "status": item.get("status", "unknown")}
                events.append(event)
            with LOCK:
                EVENTS.extend(events)
            LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
            with LOG_PATH.open("a", encoding="utf-8") as stream:
                for event in events:
                    stream.write(json.dumps(event) + "\n")
            self.reply(200, {"accepted": len(events)})
        except (ValueError, OSError):
            self.reply(400, {"error": "invalid alert payload"})


ThreadingHTTPServer(("0.0.0.0", 8082), Handler).serve_forever()
