import contextlib
import http.server
import importlib.util
import io
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT_PATH = Path(__file__).with_name("evaluate-agent.py")
SPEC = importlib.util.spec_from_file_location("evaluate_agent_auth", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
EVALUATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVALUATOR)


class MockState:
    def __init__(self):
        self.sessions = {}
        self.csrf_counts = {}
        self.chat_requests = []
        self.ingest_users = []
        self.login_attempts = []
        self.force_chat_401 = False


class MockHandler(http.server.BaseHTTPRequestHandler):
    server_version = "EvaluatorMock/1.0"

    @property
    def state(self):
        return self.server.state

    def log_message(self, format, *args):
        pass

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length)

    def _send(self, status, body=b"", headers=None):
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _cookies(self):
        return dict(
            item.strip().split("=", 1)
            for item in self.headers.get("Cookie", "").split(";") if "=" in item
        )

    def _identity(self):
        return self.state.sessions.get(self._cookies().get("SID"))

    def do_GET(self):
        if self.path == "/api/csrf":
            identity = self._identity()
            if identity:
                self.state.csrf_counts[identity] = self.state.csrf_counts.get(identity, 0) + 1
                token = f"csrf-{identity}-{self.state.csrf_counts[identity]}"
            else:
                token = "pre-login-csrf"
            body = json.dumps({"token": token, "headerName": "X-XSRF-TOKEN"}).encode()
            self._send(200, body, {
                "Content-Type": "application/json",
                "Set-Cookie": f"XSRF-TOKEN={token}; Path=/",
            })
            return
        if self.path == "/api/me":
            identity = self._identity()
            if not identity:
                self._send(401)
                return
            body = json.dumps({
                "username": identity,
                "roles": ["ADMIN"] if identity == "admin" else ["USER"],
            }).encode()
            self._send(200, body, {"Content-Type": "application/json"})
            return
        self._send(404)

    def do_POST(self):
        raw = self._body()
        if self.path == "/login":
            form = dict(item.split("=", 1) for item in raw.decode().split("&") if "=" in item)
            form = {key: EVALUATOR.urllib.parse.unquote_plus(value) for key, value in form.items()}
            username = form.get("username", "")
            self.state.login_attempts.append(username)
            if (self.headers.get("X-XSRF-TOKEN") != "pre-login-csrf"
                    or self._cookies().get("XSRF-TOKEN") != "pre-login-csrf"):
                self._send(403)
                return
            if form.get("password") != f"secret-{username}":
                self._send(401)
                return
            session = f"session-{len(self.state.sessions) + 1}"
            self.state.sessions[session] = username
            self.state.csrf_counts[username] = 0
            self._send(204, headers={"Set-Cookie": f"SID={session}; Path=/; HttpOnly"})
            return

        identity = self._identity()
        if self.state.force_chat_401 and self.path == "/api/chat":
            self._send(401)
            return
        if not identity:
            self._send(401)
            return
        expected_csrf = f"csrf-{identity}-{self.state.csrf_counts[identity]}"
        if self.headers.get("X-XSRF-TOKEN") != expected_csrf:
            self._send(403)
            return
        if self.path == "/api/ingest":
            self.state.ingest_users.append(identity)
            self._send(204 if identity == "admin" else 403)
            return
        if self.path == "/api/chat":
            payload = json.loads(raw)
            if (payload.get("userId") != identity
                    or (payload.get("simulatePrimaryFailure") and identity != "admin")):
                self._send(403)
                return
            self.state.chat_requests.append((identity, payload))
            response = {"answer": "mock answer", "toolUsed": False, "toolCalls": [], "sources": []}
            self._send(200, json.dumps(response).encode(), {"Content-Type": "application/json"})
            return
        self._send(404)


class AuthenticatedEvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.state = MockState()
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
        self.server.state = self.state
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def client(self, username="user1"):
        client = EVALUATOR.EvaluationClient(
            self.base_url, username, f"secret-{username}", timeout=2)
        client.login()
        return client

    def test_login_keeps_session_cookie_and_rotates_csrf_before_post(self):
        client = self.client()
        status, _, _ = client.post_json("/api/chat", {
            "question": "hello", "sessionId": "s1", "userId": "user1",
        })

        self.assertEqual(200, status)
        self.assertTrue(any(cookie.name == "SID" for cookie in client.cookie_jar))
        self.assertEqual("csrf-user1-2", client.csrf_token)
        self.assertEqual("user1", self.state.chat_requests[0][0])

    def test_login_failure_is_authentication_error(self):
        client = EVALUATOR.EvaluationClient(self.base_url, "user1", "wrong", timeout=2)

        with self.assertRaises(EVALUATOR.AuthenticationFailed):
            client.login()

    def test_each_user_gets_a_separate_authenticated_session(self):
        user1 = self.client("user1")
        user2 = self.client("user2")
        user1.post_json("/api/chat", {"sessionId": "s", "userId": "user1"})
        user2.post_json("/api/chat", {"sessionId": "s", "userId": "user2"})

        self.assertEqual(["user1", "user2"], [entry[0] for entry in self.state.chat_requests])
        self.assertNotEqual(
            next(cookie.value for cookie in user1.cookie_jar if cookie.name == "SID"),
            next(cookie.value for cookie in user2.cookie_jar if cookie.name == "SID"),
        )

    def test_unknown_scenario_identity_has_no_account_mapping(self):
        with self.assertRaises(EVALUATOR.AuthenticationFailed):
            EVALUATOR.account_for_user_id("attacker-controlled")

    def test_expired_session_is_distinguished_from_agent_failure(self):
        client = self.client()
        self.state.force_chat_401 = True

        with self.assertRaises(EVALUATOR.SessionExpired):
            client.post_json("/api/chat", {"sessionId": "s", "userId": "user1"})

    def test_admin_scenario_selects_admin_account(self):
        scenarios = json.loads(Path("evaluation/agent_scenarios.json").read_text(encoding="utf-8"))
        fallback = next(scenario for scenario in scenarios if scenario["id"] == "AG-15")

        self.assertEqual("admin", EVALUATOR.account_for_user_id(fallback["userId"]))
        admin = self.client("admin")
        status, _, _ = admin.post_json("/api/ingest", {})
        self.assertEqual(204, status)
        self.assertEqual(["admin"], self.state.ingest_users)
        status, _, _ = admin.post_json("/api/chat", {
            "sessionId": "eval-ag15", "userId": "admin", "simulatePrimaryFailure": True,
        })
        self.assertEqual(200, status)
        self.assertEqual("admin", self.state.chat_requests[-1][0])

    def test_security_rejection_is_not_reported_as_agent_mismatch(self):
        class ForbiddenClient(EVALUATOR.EvaluationClient):
            def _request(self, method, path, data=None, headers=None):
                if path == "/api/csrf":
                    return 200, json.dumps({"token": "csrf", "headerName": "X-XSRF-TOKEN"})
                return 403, ""

        client = ForbiddenClient(self.base_url, "user1", "unused", timeout=2)
        client.authenticated = True

        with self.assertRaises(EVALUATOR.SecurityRequestRejected):
            client.post_json("/api/chat", {})

    def test_report_output_excludes_password_and_session_cookie(self):
        secret = "secret-user1"
        with tempfile.TemporaryDirectory() as output_dir:
            scenario_file = Path(output_dir) / "scenario.json"
            scenario_file.write_text(json.dumps([{
                "id": "AUTH-SAFE", "name": "auth-safe", "category": "RAG",
                "userId": "user1", "sessionId": "safe-session",
                "turns": [{"question": "hello", "expected": {"httpStatus": 200}}],
            }]), encoding="utf-8")
            results_dir = Path(output_dir) / "results"
            with patch.dict(os.environ, {"APP_USER1_PASSWORD": secret}), patch.object(
                    EVALUATOR.sys, "argv", ["evaluate-agent.py", "--base-url", self.base_url,
                    "--scenarios", str(scenario_file), "--skip-ingest",
                    "--output-dir", str(results_dir)]):
                exit_code = EVALUATOR.main()

            self.assertEqual(0, exit_code)
            serialized = (results_dir / "latest.json").read_text(encoding="utf-8")
            self.assertNotIn(secret, serialized)
            self.assertNotIn("SID=session-", serialized)
            self.assertNotIn("Cookie", serialized)

    def test_login_failure_aborts_without_writing_result_or_echoing_secret(self):
        secret = "wrong-secret-value"
        with tempfile.TemporaryDirectory() as directory:
            scenario_file = Path(directory) / "scenario.json"
            scenario_file.write_text(json.dumps([{
                "id": "AUTH-FAIL", "name": "auth-fail", "category": "RAG",
                "userId": "user1", "sessionId": "auth-fail",
                "turns": [{"question": "hello", "expected": {"httpStatus": 200}}],
            }]), encoding="utf-8")
            results_dir = Path(directory) / "results"
            stderr = io.StringIO()
            with patch.dict(os.environ, {"APP_USER1_PASSWORD": secret}), patch.object(
                    EVALUATOR.sys, "argv", ["evaluate-agent.py", "--base-url", self.base_url,
                    "--scenarios", str(scenario_file), "--skip-ingest",
                    "--output-dir", str(results_dir)]), contextlib.redirect_stderr(stderr):
                exit_code = EVALUATOR.main()

            self.assertEqual(2, exit_code)
            self.assertFalse((results_dir / "latest.json").exists())
            self.assertIn("AuthenticationFailed", stderr.getvalue())
            self.assertNotIn(secret, stderr.getvalue())

    def test_existing_result_files_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory)
            original = output_dir / "latest.json"
            original.write_text("preserve", encoding="utf-8")

            with self.assertRaises(EVALUATOR.EvaluationAborted):
                EVALUATOR.ensure_output_is_new(output_dir)

            self.assertEqual("preserve", original.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
