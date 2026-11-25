import http from "k6/http";
import { check, sleep } from "k6";

const base = __ENV.BASE_URL;
const userPassword = __ENV.K6_USER_PASSWORD;

export const options = {
  scenarios: {
    staged: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "10s", target: 1 },
        { duration: "10s", target: 5 },
        { duration: "10s", target: 10 },
        { duration: "10s", target: 20 },
      ],
    },
  },
  thresholds: {
    checks: ["rate>0.99"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{workload:permission_lookup}": ["p(95)<5000"],
    "http_req_duration{workload:ticket_create}": ["p(95)<5000"],
    "http_req_duration{workload:privacy_guard}": ["p(95)<5000"],
    "http_req_duration{workload:rag_mock}": ["p(95)<5000"],
  },
};

let csrf = null;

function jsonRequest(path, body, params = {}) {
  return http.post(`${base}${path}`, JSON.stringify(body), {
    headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token, ...params.headers },
    tags: params.tags,
  });
}

function authenticate() {
  let response = http.get(`${base}/api/csrf`);
  check(response, { "initial CSRF token available": (r) => r.status === 200 });
  csrf = response.json();
  const form = `username=user1&password=${encodeURIComponent(userPassword)}`;
  response = http.post(`${base}/login`, form, {
    headers: { "Content-Type": "application/x-www-form-urlencoded", [csrf.headerName]: csrf.token },
    redirects: 0,
  });
  check(response, { "USER login succeeds": (r) => r.status === 204 });
  response = http.get(`${base}/api/csrf`);
  check(response, { "refreshed CSRF token available": (r) => r.status === 200 });
  csrf = response.json();
}

export default function () {
  // A fresh authenticated test session per operation avoids sharing mutable
  // session/CSRF state between virtual users or workload types.
  authenticate();
  const unique = `${String(__VU).padStart(8, "0")}${String(__ITER).padStart(4, "0")}`;
  const sessionId = `k6-${__VU}-${__ITER}`;
  let response;
  switch (__ITER % 4) {
    case 0:
      response = jsonRequest("/api/chat", {
        question: "내 DEV_DB 권한 상태 알려줘.", sessionId,
      }, { tags: { workload: "permission_lookup" } });
      check(response, { "permission lookup returned expected state": (r) => r.status === 200 && String(r.json("answer")).includes("DEV_DB") });
      break;
    case 1:
      response = jsonRequest("/api/chat", {
        question: "DEV_DB 권한을 신청해줘. k6 합성 부하 테스트.", sessionId: `${sessionId}-ticket`,
      }, { headers: { "Idempotency-Key": `00000000-0000-4000-8000-${unique}` }, tags: { workload: "ticket_create" } });
      check(response, { "ticket persisted as pending": (r) => r.status === 200 && r.json("ticketStatus") === "PENDING" && Boolean(r.json("ticketId")) });
      break;
    case 2:
      response = jsonRequest("/api/chat", {
        question: "내 암호는 LOADTEST-FAKE-ONLY 입니다.", sessionId: `${sessionId}-privacy`,
      }, { tags: { workload: "privacy_guard" } });
      check(response, { "privacy guard blocks synthetic credential": (r) => r.status === 200 && String(r.json("answer")).includes("민감") });
      break;
    default:
      response = jsonRequest("/api/chat", {
        question: "VPN 승인에는 얼마나 걸려?", sessionId: `${sessionId}-rag`,
      }, { tags: { workload: "rag_mock" } });
      check(response, { "mock RAG responds with source": (r) => r.status === 200 && String(r.json("sources")).includes("vpn-guide.md") });
  }
  sleep(0.5);
}
