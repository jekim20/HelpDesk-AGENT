#!/usr/bin/env python3
"""Run deterministic DevDesk Agent scenarios against POST /api/chat."""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class EvaluationAborted(RuntimeError):
    """Raised when the evaluation environment cannot serve valid requests."""


class AuthenticationFailed(EvaluationAborted):
    """Raised when evaluator credentials cannot establish the requested identity."""


class SessionExpired(EvaluationAborted):
    """Raised when an authenticated session expires during evaluation."""


class SecurityRequestRejected(EvaluationAborted):
    """Raised when the server rejects a request for authorization or CSRF reasons."""


ACCOUNT_ENV = {
    "user1": "APP_USER1_PASSWORD",
    "user2": "APP_USER2_PASSWORD",
    "admin": "APP_ADMIN_PASSWORD",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate the DevDesk Agent API")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--scenarios", default="evaluation/agent_scenarios.json")
    parser.add_argument("--output-dir", help="New run directory; existing latest.* files are never overwritten")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--skip-ingest", action="store_true",
                        help="Do not call POST /api/ingest before evaluation")
    return parser.parse_args()


class EvaluationClient:
    """One authenticated principal with its own cookie jar and refreshed CSRF token."""

    def __init__(self, base_url: str, username: str, password: str, timeout: float):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.password = password
        self.timeout = timeout
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookie_jar)
        )
        self.csrf_token: str | None = None
        self.csrf_header: str | None = None
        self.authenticated = False

    def _request(self, method: str, path: str, data: bytes | None = None,
                 headers: dict[str, str] | None = None) -> tuple[int, str]:
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data,
            headers={"Accept": "application/json", **(headers or {})}, method=method,
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return response.status, response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read().decode("utf-8", errors="replace")
            error.close()
            return status, raw
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            raise EvaluationAborted(f"서버 연결 실패: {self.base_url} ({error})") from error

    def refresh_csrf(self) -> None:
        status, raw = self._request("GET", "/api/csrf")
        if status == 401:
            raise SessionExpired("CSRF 토큰 조회 중 인증 세션이 만료되었습니다 (HTTP 401).")
        if status >= 400:
            raise SecurityRequestRejected(f"CSRF 토큰 조회 실패 (HTTP {status}).")
        try:
            body = json.loads(raw)
            token = body["token"]
            header = body["headerName"]
        except (json.JSONDecodeError, KeyError, TypeError) as error:
            raise EvaluationAborted("/api/csrf 응답 형식이 올바르지 않습니다.") from error
        if not isinstance(token, str) or not token or not isinstance(header, str) or not header:
            raise EvaluationAborted("/api/csrf 응답에 유효한 토큰 또는 헤더명이 없습니다.")
        self.csrf_token, self.csrf_header = token, header

    def login(self) -> None:
        self.refresh_csrf()
        form = urllib.parse.urlencode({"username": self.username, "password": self.password}).encode()
        try:
            status, _ = self._request("POST", "/login", form, {
                "Content-Type": "application/x-www-form-urlencoded",
                self.csrf_header: self.csrf_token,
            })
        finally:
            self.password = ""
        if status == 401:
            raise AuthenticationFailed(f"로그인 실패: 계정 '{self.username}'의 자격 증명을 확인하세요.")
        if status < 200 or status >= 300:
            raise AuthenticationFailed(f"로그인 실패 (HTTP {status}), 계정 '{self.username}'.")

        # Spring Security may rotate the CSRF token on authentication success.
        self.refresh_csrf()
        status, raw = self._request("GET", "/api/me")
        if status == 401:
            raise AuthenticationFailed("로그인 후 세션 확인에 실패했습니다 (HTTP 401).")
        if status >= 400:
            raise SecurityRequestRejected(f"로그인 후 사용자 확인이 거부되었습니다 (HTTP {status}).")
        try:
            actual_user = json.loads(raw).get("username")
        except (json.JSONDecodeError, AttributeError) as error:
            raise EvaluationAborted("/api/me 응답 형식이 올바르지 않습니다.") from error
        if actual_user != self.username:
            raise AuthenticationFailed("로그인 세션의 사용자 신원이 요청한 평가 계정과 다릅니다.")
        self.authenticated = True

    def post_json(self, path: str, payload: dict[str, Any],
                  idempotency_key: str | None = None) -> tuple[int, dict[str, Any], float]:
        if not self.authenticated:
            raise AuthenticationFailed("인증되지 않은 평가 클라이언트의 요청을 차단했습니다.")
        self.refresh_csrf()
        started = time.perf_counter()
        headers = {"Content-Type": "application/json", self.csrf_header: self.csrf_token}
        if idempotency_key is not None:
            headers["Idempotency-Key"] = idempotency_key
        request_body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        attempts = 2 if path == "/api/chat" and idempotency_key is not None else 1
        for attempt in range(attempts):
            try:
                status, raw = self._request("POST", path, request_body, headers)
                break
            except EvaluationAborted:
                if attempt + 1 == attempts:
                    raise
        latency_ms = round((time.perf_counter() - started) * 1000, 2)
        if status == 401:
            raise SessionExpired(f"인증 세션 만료 (HTTP 401): {path}")
        if status == 403:
            raise SecurityRequestRejected(f"권한 또는 CSRF 검증 실패 (HTTP 403): {path}")
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {"answer": raw, "parseError": "response was not JSON"}
        return status, body, latency_ms


def account_for_user_id(user_id: str) -> str:
    if user_id not in ACCOUNT_ENV:
        raise AuthenticationFailed(f"평가 시나리오 userId에 등록된 계정 매핑이 없습니다: {user_id!r}")
    return user_id


def default_output_dir() -> Path:
    stamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S-%f")
    return Path("evaluation/results/runs") / stamp


def ensure_output_is_new(output_dir: Path) -> None:
    if (output_dir / "latest.json").exists() or (output_dir / "latest.md").exists():
        raise EvaluationAborted(f"기존 평가 결과를 덮어쓰지 않도록 실행을 중단했습니다: {output_dir}")


def account_client(base_url: str, user_id: str, timeout: float,
                   clients: dict[str, EvaluationClient]) -> EvaluationClient:
    username = account_for_user_id(user_id)
    if username not in clients:
        env_name = ACCOUNT_ENV[username]
        password = os.environ.get(env_name, "")
        if not password:
            raise AuthenticationFailed(
                f"평가 계정 '{username}'이 비활성화되어 있습니다. 환경변수 {env_name}를 설정하세요."
            )
        client = EvaluationClient(base_url, username, password, timeout)
        client.login()
        clients[username] = client
    return clients[username]


def contains(haystack: str, needle: str) -> bool:
    return needle.casefold() in haystack.casefold()


def is_non_negative_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0


def evaluate_tool_calls(expected: dict[str, Any], body: dict[str, Any]) -> dict[str, Any] | None:
    expected_calls = expected.get("expectedToolCalls")
    if expected_calls is None:
        return None

    failures: list[str] = []
    raw_actual_calls = body.get("toolCalls")
    actual_calls = raw_actual_calls if isinstance(raw_actual_calls, list) else []
    actual_is_list = isinstance(raw_actual_calls, list)

    selection_passed = actual_is_list and len(actual_calls) == len(expected_calls)
    if not actual_is_list:
        failures.append(f"toolCalls: expected list, actual {raw_actual_calls}")
    elif len(actual_calls) != len(expected_calls):
        failures.append(
            f"toolCalls count: expected {len(expected_calls)}, actual {len(actual_calls)}"
        )

    argument_passed = 0
    execution_passed = 0
    latency_passed = 0

    for index, expected_call in enumerate(expected_calls):
        actual_call = actual_calls[index] if index < len(actual_calls) else None
        actual_is_object = isinstance(actual_call, dict)
        if not actual_is_object:
            selection_passed = False
            failures.append(f"toolCalls[{index}]: expected object, actual {actual_call}")
            actual_call = {}

        expected_name = expected_call.get("toolName")
        actual_name = actual_call.get("toolName")
        name_matches = actual_is_object and actual_name == expected_name
        if not name_matches:
            selection_passed = False
            failures.append(
                f"toolCalls[{index}].toolName: expected {expected_name}, actual {actual_name}"
            )

        expected_arguments = expected_call.get("arguments") or {}
        actual_arguments = actual_call.get("toolArguments")
        arguments_match = (
            name_matches
            and isinstance(actual_arguments, dict)
            and all(actual_arguments.get(key) == value for key, value in expected_arguments.items())
        )
        if arguments_match:
            argument_passed += 1
        else:
            failures.append(
                f"toolCalls[{index}].toolArguments: expected subset "
                f"{expected_arguments}, actual {actual_arguments}"
            )

        expected_success = expected_call.get("success", True)
        actual_success = actual_call.get("success")
        execution_matches = name_matches and actual_success is expected_success
        if execution_matches:
            execution_passed += 1
        else:
            failures.append(
                f"toolCalls[{index}].success: expected {expected_success}, actual {actual_success}"
            )

        latency_ms = actual_call.get("latencyMs")
        if is_non_negative_number(latency_ms):
            latency_passed += 1
        else:
            failures.append(
                f"toolCalls[{index}].latencyMs: expected non-negative number, actual {latency_ms}"
            )

    return {
        "selectionPassed": selection_passed,
        "argumentPassed": argument_passed,
        "argumentTotal": len(expected_calls),
        "executionPassed": execution_passed,
        "executionTotal": len(expected_calls),
        "latencyPassed": latency_passed,
        "latencyTotal": len(expected_calls),
        "failures": failures,
    }


def evaluate_turn(expected: dict[str, Any], status: int, body: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    answer = str(body.get("answer", ""))
    sources = [str(source) for source in (body.get("sources") or [])]

    expected_status = int(expected.get("httpStatus", 200))
    if status != expected_status:
        failures.append(f"HTTP status: expected {expected_status}, actual {status}")

    for field in ("toolUsed", "fallbackUsed"):
        if field in expected and body.get(field) is not expected[field]:
            failures.append(f"{field}: expected {expected[field]}, actual {body.get(field)}")

    if expected.get("toolUsed") is False:
        actual_calls = body.get("toolCalls")
        if not isinstance(actual_calls, list) or actual_calls:
            failures.append(f"toolCalls: expected empty list, actual {actual_calls}")

    tool_evaluation = evaluate_tool_calls(expected, body)
    if tool_evaluation is not None:
        failures.extend(tool_evaluation["failures"])

    if "sourceAny" in expected:
        wanted_sources = [str(source) for source in expected["sourceAny"]]
        if wanted_sources:
            if not any(any(contains(actual, wanted) for actual in sources) for wanted in wanted_sources):
                failures.append(f"sourceAny: expected one of {wanted_sources}, actual {sources}")
        elif sources:
            failures.append(f"sources: expected empty, actual {sources}")

    any_terms = [str(term) for term in expected.get("answerContainsAny", [])]
    if any_terms and not any(contains(answer, term) for term in any_terms):
        failures.append(f"answerContainsAny: none of {any_terms} found")

    all_terms = [str(term) for term in expected.get("answerContainsAll", [])]
    missing = [term for term in all_terms if not contains(answer, term)]
    if missing:
        failures.append(f"answerContainsAll: missing {missing}")

    knowledge_boundary = expected.get("answerKnowledgeBoundary")
    if knowledge_boundary is not None:
        failures.extend(evaluate_knowledge_boundary(knowledge_boundary, answer))

    forbidden = [str(term) for term in expected.get("answerNotContains", [])]
    exposed = [term for term in forbidden if contains(answer, term)]
    if exposed:
        failures.append(f"answerNotContains: found {exposed}")

    return failures


def evaluate_knowledge_boundary(rule: dict[str, Any], answer: str) -> list[str]:
    """Require a topic-linked evidence gap and reject concrete unsupported policy claims."""
    topic_groups = rule.get("topicGroups", [])
    if topic_groups:
        topic_present = all(
            any(contains(answer, str(topic)) for topic in group)
            for group in topic_groups
        )
    else:
        topics = [str(topic) for topic in rule.get("topicAny", [])]
        topic_present = bool(topics) and any(contains(answer, topic) for topic in topics)
    if not topic_present:
        return ["answerKnowledgeBoundary: expected answer to identify the full requested topic"]

    sentences = [part.strip() for part in re.split(r"(?<=[.!?。！？])\s+|\n+", answer) if part.strip()]
    boundary_patterns = rule.get("sourceTopicAbsencePatterns", [])
    boundary_found = any(
        re.search(str(pattern), sentence, flags=re.IGNORECASE | re.DOTALL)
        for pattern in boundary_patterns
        for sentence in sentences
    )
    if not boundary_found:
        return ["answerKnowledgeBoundary: no sentence links the topic to missing source evidence"]

    unsupported_patterns = rule.get("unsupportedClaimPatterns", [])
    unsupported = [
        str(pattern) for pattern in unsupported_patterns
        if re.search(str(pattern), answer, flags=re.IGNORECASE | re.DOTALL)
    ]
    if unsupported:
        return ["answerKnowledgeBoundary: contains a concrete unsupported policy claim"]
    return []


def percentage(numerator: int, denominator: int) -> float | None:
    return round(numerator * 100.0 / denominator, 1) if denominator else None


def nearest_rank_p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[math.ceil(0.95 * len(ordered)) - 1], 2)


def summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    turns = [turn for scenario in results for turn in scenario["turns"]]
    tool_turns = [turn for turn in turns if "toolUsed" in turn["expected"]]
    rag_turns = [turn for turn in turns if turn["expected"].get("sourceAny")]
    safety = [scenario for scenario in results if scenario["category"] == "SAFETY"]
    multi_turn = [scenario for scenario in results if len(scenario["turns"]) > 1]
    latencies = [turn["latencyMs"] for turn in turns]
    tool_evaluations = [
        evaluation
        for turn in turns
        if (evaluation := evaluate_tool_calls(turn["expected"], turn["actual"])) is not None
    ]
    tool_selection_passed = sum(evaluation["selectionPassed"] for evaluation in tool_evaluations)
    tool_argument_passed = sum(evaluation["argumentPassed"] for evaluation in tool_evaluations)
    tool_argument_total = sum(evaluation["argumentTotal"] for evaluation in tool_evaluations)
    tool_execution_passed = sum(evaluation["executionPassed"] for evaluation in tool_evaluations)
    tool_execution_total = sum(evaluation["executionTotal"] for evaluation in tool_evaluations)

    return {
        "scenarioCount": len(results),
        "passed": sum(result["passed"] for result in results),
        "taskSuccessRate": percentage(sum(result["passed"] for result in results), len(results)),
        "toolDecisionAccuracy": percentage(
            sum(turn["actual"].get("toolUsed") is turn["expected"]["toolUsed"] for turn in tool_turns),
            len(tool_turns),
        ),
        "toolSelectionPassed": tool_selection_passed,
        "toolSelectionTotal": len(tool_evaluations),
        "toolSelectionAccuracy": percentage(tool_selection_passed, len(tool_evaluations)),
        "toolArgumentPassed": tool_argument_passed,
        "toolArgumentTotal": tool_argument_total,
        "toolArgumentAccuracy": percentage(tool_argument_passed, tool_argument_total),
        "toolExecutionPassed": tool_execution_passed,
        "toolExecutionTotal": tool_execution_total,
        "toolExecutionSuccessRate": percentage(tool_execution_passed, tool_execution_total),
        "ragSourceHitRate": percentage(
            sum(
                any(
                    any(contains(actual, expected) for actual in (turn["actual"].get("sources") or []))
                    for expected in turn["expected"]["sourceAny"]
                )
                for turn in rag_turns
            ),
            len(rag_turns),
        ),
        "safetyPassRate": percentage(sum(result["passed"] for result in safety), len(safety)),
        "multiTurnPassRate": percentage(sum(result["passed"] for result in multi_turn), len(multi_turn)),
        "averageLatencyMs": round(statistics.mean(latencies), 2) if latencies else None,
        "medianLatencyMs": round(statistics.median(latencies), 2) if latencies else None,
        "p95LatencyMs": nearest_rank_p95(latencies),
    }


def render_markdown(report: dict[str, Any]) -> str:
    summary = report["summary"]
    lines = [
        "# DevDesk Agent Evaluation Result",
        "",
        f"- Generated: `{report['generatedAt']}`",
        f"- Base URL: `{report['baseUrl']}`",
        f"- Scenarios: {summary['scenarioCount']}",
        f"- Passed: {summary['passed']}",
        f"- Task Success Rate: {summary['taskSuccessRate']}%",
        f"- Tool Decision Accuracy: {summary['toolDecisionAccuracy']}%",
        f"- Tool Selection Accuracy: {summary['toolSelectionPassed']}/"
        f"{summary['toolSelectionTotal']} ({summary['toolSelectionAccuracy']}%)",
        f"- Tool Argument Accuracy: {summary['toolArgumentPassed']}/"
        f"{summary['toolArgumentTotal']} ({summary['toolArgumentAccuracy']}%)",
        f"- Tool Execution Success Rate: {summary['toolExecutionPassed']}/"
        f"{summary['toolExecutionTotal']} ({summary['toolExecutionSuccessRate']}%)",
        f"- RAG Source Hit Rate: {summary['ragSourceHitRate']}%",
        f"- Safety Pass Rate: {summary['safetyPassRate']}%",
        f"- Multi-turn Pass Rate: {summary['multiTurnPassRate']}%",
        f"- Average Latency: {summary['averageLatencyMs']} ms",
        f"- Median Latency: {summary['medianLatencyMs']} ms",
        f"- P95 Latency (nearest-rank): {summary['p95LatencyMs']} ms",
        "",
        "| ID | Category | Result | Latency (ms) | Failure |",
        "|---|---|---:|---:|---|",
    ]
    for scenario in report["scenarios"]:
        latency = round(sum(turn["latencyMs"] for turn in scenario["turns"]), 2)
        failures = "; ".join(
            failure for turn in scenario["turns"] for failure in turn["failures"]
        ).replace("|", "\\|")
        lines.append(
            f"| {scenario['id']} | {scenario['category']} | "
            f"{'PASS' if scenario['passed'] else 'FAIL'} | {latency} | {failures or '-'} |"
        )
    lines.extend([
        "",
        "> 이 결과는 위 Base URL의 실행 중인 서버와 모델 환경에서 측정한 값이다.",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    scenario_path = Path(args.scenarios)
    try:
        scenarios = json.loads(scenario_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"시나리오 파일을 읽을 수 없습니다: {scenario_path} ({error})", file=sys.stderr)
        return 2

    output_dir = Path(args.output_dir) if args.output_dir else default_output_dir()
    try:
        ensure_output_is_new(output_dir)
        for scenario in scenarios:
            account_for_user_id(scenario.get("userId", ""))
    except EvaluationAborted as error:
        print(str(error), file=sys.stderr)
        return 2

    print("DevDesk Agent Evaluation")
    print("========================")
    results: list[dict[str, Any]] = []
    clients: dict[str, EvaluationClient] = {}
    try:
        if not args.skip_ingest:
            admin = account_client(args.base_url, "admin", args.timeout, clients)
            status, _, _ = admin.post_json("/api/ingest", {})
            if status >= 400:
                raise EvaluationAborted(f"정책 문서 인제스트 실패 (HTTP {status})")
            print("[READY] policy documents ingested")
        for scenario in scenarios:
            client = account_client(args.base_url, scenario["userId"], args.timeout, clients)
            turn_results = []
            for turn in scenario["turns"]:
                payload = {
                    "question": turn["question"],
                    "sessionId": scenario["sessionId"],
                    "userId": client.username,
                    "simulatePrimaryFailure": bool(turn.get("simulatePrimaryFailure", False)),
                }
                expected = turn["expected"]
                ticket_write = any(
                    call.get("toolName") == "createTicket"
                    for call in expected.get("expectedToolCalls", [])
                )
                # Reuse this key for an automatic transport retry; each turn gets a fresh key.
                idempotency_key = str(uuid.uuid4()) if ticket_write else None
                status, body, latency_ms = client.post_json(
                    "/api/chat", payload, idempotency_key=idempotency_key)
                failures = evaluate_turn(expected, status, body)
                turn_results.append({
                    "question": turn["question"],
                    "expected": expected,
                    "httpStatus": status,
                    "latencyMs": latency_ms,
                    "actual": body,
                    "passed": not failures,
                    "failures": failures,
                })
            passed = all(turn["passed"] for turn in turn_results)
            results.append({
                "id": scenario["id"],
                "name": scenario["name"],
                "category": scenario["category"],
                "passed": passed,
                "turns": turn_results,
            })
            print(f"[{'PASS' if passed else 'FAIL'}] {scenario['id']} {scenario['name']}")
    except EvaluationAborted as error:
        print(f"[{type(error).__name__}] {error}", file=sys.stderr)
        print("평가 결과 파일을 갱신하지 않았습니다.", file=sys.stderr)
        return 2

    report = {
        "generatedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "baseUrl": args.base_url.rstrip("/"),
        "summary": summarize(results),
        "scenarios": results,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "latest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / "latest.md").write_text(render_markdown(report), encoding="utf-8")

    summary = report["summary"]
    print("\nSummary")
    print("-------")
    print(f"Scenarios: {summary['scenarioCount']}")
    print(f"Passed: {summary['passed']}")
    print(f"Task Success Rate: {summary['taskSuccessRate']}%")
    print(f"Tool Decision Accuracy: {summary['toolDecisionAccuracy']}%")
    print(
        f"Tool Selection Accuracy: {summary['toolSelectionPassed']}/"
        f"{summary['toolSelectionTotal']} ({summary['toolSelectionAccuracy']}%)"
    )
    print(
        f"Tool Argument Accuracy: {summary['toolArgumentPassed']}/"
        f"{summary['toolArgumentTotal']} ({summary['toolArgumentAccuracy']}%)"
    )
    print(
        f"Tool Execution Success Rate: {summary['toolExecutionPassed']}/"
        f"{summary['toolExecutionTotal']} ({summary['toolExecutionSuccessRate']}%)"
    )
    print(f"RAG Source Hit Rate: {summary['ragSourceHitRate']}%")
    print(f"Safety Pass Rate: {summary['safetyPassRate']}%")
    print(f"Multi-turn Pass Rate: {summary['multiTurnPassRate']}%")
    print(f"P95 Latency: {summary['p95LatencyMs']} ms")
    return 0 if summary["passed"] == summary["scenarioCount"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
