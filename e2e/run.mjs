import { randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import fs from "node:fs/promises";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const e2eDir = path.join(root, "e2e");
const stamp = new Date().toISOString().replaceAll(":", "").replaceAll(".", "-");
const suffix = randomBytes(3).toString("hex");
const project = `helpdesk-e2e-${process.pid}-${suffix}`;
const artifactDir = path.join(root, "artifacts", "e2e", "runs", `${stamp}-${suffix}`);
const envFile = path.join(tmpdir(), `${project}.env`);
const credentials = {
  HELPDESK_DB_PASSWORD: randomBytes(32).toString("hex"),
  APP_USER1_PASSWORD: randomBytes(32).toString("hex"),
  APP_USER2_PASSWORD: randomBytes(32).toString("hex"),
  APP_ADMIN_PASSWORD: randomBytes(32).toString("hex"),
  GRAFANA_ADMIN_PASSWORD: randomBytes(32).toString("hex"),
};

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  await new Promise((resolve) => server.close(resolve));
  if (!address || typeof address === "string") throw new Error("Could not reserve an ephemeral localhost port");
  return address.port;
}

function redact(value) {
  let output = value;
  for (const secret of Object.values(credentials)) {
    if (secret) output = output.split(secret).join("[REDACTED]");
  }
  return output
    .replace(/(authorization:\s*bearer\s+)\S+/gi, "$1[REDACTED]")
    .replace(/((?:JSESSIONID|XSRF-TOKEN)=)[^;\s]+/gi, "$1[REDACTED]")
    .replace(/\bsk-(?:proj-)?[A-Za-z0-9_-]{12,}\b/g, "[REDACTED_API_KEY]");
}

function command(program, args, { cwd = root, env = process.env, capture = false } = {}) {
  return new Promise((resolve) => {
    const child = spawn(program, args, { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    const stdout = [];
    const stderr = [];
    child.stdout.on("data", (chunk) => {
      const value = chunk.toString();
      stdout.push(value);
      if (!capture) process.stdout.write(value);
    });
    child.stderr.on("data", (chunk) => {
      const value = chunk.toString();
      stderr.push(value);
      if (!capture) process.stderr.write(value);
    });
    child.on("error", (error) => resolve({ code: 1, stdout: "", stderr: error.message }));
    child.on("close", (code) => resolve({
      code: code ?? 1,
      stdout: stdout.join(""),
      stderr: stderr.join(""),
    }));
  });
}

async function promQueryScalar(prometheusURL, expression) {
  const response = await fetch(`${prometheusURL}/api/v1/query?query=${encodeURIComponent(expression)}`);
  if (!response.ok) throw new Error(`Prometheus query failed with HTTP ${response.status}`);
  const body = await response.json();
  const value = body.data?.result?.[0]?.value?.[1];
  return value === undefined ? null : Number(value);
}

async function waitFor(description, probe, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const result = await probe();
      if (result) return result;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? `: ${lastError.message}` : ""}`);
}

async function waitForPrometheusScrape(prometheusURL) {
  await waitFor("Prometheus app scrape and core readiness", async () =>
    await promQueryScalar(prometheusURL, 'up{job="helpdesk"}') === 1
      && await promQueryScalar(prometheusURL, "helpdesk_core_readiness") === 1);
  return { scrapeUp: true, coreReadiness: 1 };
}

async function waitForGrafanaDashboard(grafanaURL, password) {
  const authorization = `Basic ${Buffer.from(`admin:${password}`).toString("base64")}`;
  await waitFor("provisioned Grafana dashboard", async () => {
    const response = await fetch(`${grafanaURL}/api/search?query=HelpDesk`, { headers: { authorization } });
    if (!response.ok) return false;
    const dashboards = await response.json();
    return dashboards.some((item) => item.uid === "helpdesk-operations");
  });
  return { dashboardUid: "helpdesk-operations", listener: "loopback-only" };
}

async function waitForAlertEvent(sinkURL, alertName, status) {
  return waitFor(`${alertName} ${status} webhook`, async () => {
    const response = await fetch(`${sinkURL}/events`);
    if (!response.ok) return false;
    const body = await response.json();
    return body.events.some((event) => event.alertname === alertName && event.status === status);
  });
}

async function containerHealth(composeArgs, service) {
  const container = await command("docker", [...composeArgs, "ps", "-q", service], { capture: true });
  const id = container.stdout.trim();
  if (container.code !== 0 || !id) return null;
  const health = await command("docker", ["inspect", "--format", "{{.State.Health.Status}}", id], { capture: true });
  return health.code === 0 ? health.stdout.trim() : null;
}

const monitoringEnabled = process.env.E2E_MONITORING === "1";
const composeFiles = [
  "-f", path.join(root, "compose.deploy.yaml"),
  "-f", path.join(e2eDir, "compose.e2e.yaml"),
  ...(monitoringEnabled ? ["-f", path.join(root, "compose.monitoring.yaml")] : []),
];
const demoPort = await freePort();
let mockPort = await freePort();
while (mockPort === demoPort) mockPort = await freePort();
let prometheusPort;
let grafanaPort;
let alertSinkPort;
if (monitoringEnabled) {
  prometheusPort = await freePort();
  grafanaPort = await freePort();
  alertSinkPort = await freePort();
  while ([demoPort, mockPort, prometheusPort, grafanaPort].includes(alertSinkPort)) alertSinkPort = await freePort();
}
const composeArgs = ["compose", ...(monitoringEnabled ? ["--profile", "monitoring"] : []), "--env-file", envFile, "-p", project, ...composeFiles];
const baseURL = `http://127.0.0.1:${demoPort}`;
const mockURL = `http://127.0.0.1:${mockPort}`;
const healthBase = `http://127.0.0.1:${monitoringEnabled ? 8081 : 8080}`;
let composeMayExist = false;
let runCode = 1;
let failure = "";
let testCounts = null;
let databaseHealthVerification = "not-run";
let monitoringVerification = null;

await fs.mkdir(path.dirname(artifactDir), { recursive: true });
await fs.mkdir(artifactDir, { recursive: false });
await fs.writeFile(envFile, [
  `DEMO_PORT=${demoPort}`,
  `AI_MOCK_PORT=${mockPort}`,
  ...(monitoringEnabled ? [`PROMETHEUS_PORT=${prometheusPort}`, `GRAFANA_PORT=${grafanaPort}`, `ALERT_SINK_PORT=${alertSinkPort}`] : []),
  "HELPDESK_OPENAI_API_KEY=e2e-no-external-key",
  ...Object.entries(credentials).map(([key, value]) => `${key}=${value}`),
].join("\n") + "\n", { mode: 0o600 });

const testEnv = {
  ...process.env,
  E2E_BASE_URL: baseURL,
  E2E_MOCK_CONTROL_URL: mockURL,
  E2E_ARTIFACT_DIR: artifactDir,
  E2E_COMPOSE_PROJECT: project,
  E2E_COMPOSE_ENV_FILE: envFile,
  E2E_ADMIN_PASSWORD: credentials.APP_ADMIN_PASSWORD,
  E2E_USER1_PASSWORD: credentials.APP_USER1_PASSWORD,
  E2E_USER2_PASSWORD: credentials.APP_USER2_PASSWORD,
  ...(monitoringEnabled ? {
    E2E_PROMETHEUS_URL: `http://127.0.0.1:${prometheusPort}`,
    E2E_ALERT_SINK_URL: `http://127.0.0.1:${alertSinkPort}`,
    E2E_GRAFANA_URL: `http://127.0.0.1:${grafanaPort}`,
    E2E_GRAFANA_ADMIN_PASSWORD: credentials.GRAFANA_ADMIN_PASSWORD,
  } : {}),
};
for (const key of Object.keys(testEnv)) {
  if (/(?:API_KEY|PASSWORD|SECRET|TOKEN)/i.test(key) && !key.startsWith("E2E_")) delete testEnv[key];
}
const startedAt = new Date().toISOString();
let databaseVerification = "not-run";

try {
  const up = await command("docker", [...composeArgs, "up", "--build", "--detach", "--wait", "--wait-timeout", "180"]);
  composeMayExist = true;
  if (up.code !== 0) throw new Error("Docker Compose did not reach healthy state");

  const ps = await command("docker", [...composeArgs, "ps"], { capture: true });
  await fs.writeFile(path.join(artifactDir, "docker-compose-ps.txt"), redact(ps.stdout + ps.stderr));
  if (monitoringEnabled) {
    const scrape = await waitForPrometheusScrape(`http://127.0.0.1:${prometheusPort}`);
    const dashboard = await waitForGrafanaDashboard(`http://127.0.0.1:${grafanaPort}`, credentials.GRAFANA_ADMIN_PASSWORD);
    monitoringVerification = { scrape, dashboard, aiAlertTransitions: "pending browser failure/recovery verification", databaseAlertTransitions: "pending database stop/restart verification" };
  }

  const browserArgs = ["run", "test:browser", "--prefix", e2eDir, "--", "--config", path.join(e2eDir, "playwright.config.ts")];
  if (process.env.E2E_PERFORMANCE_ONLY === "1") browserArgs.push("--grep", "@performance");
  const aiDockerHealthProbe = monitoringEnabled ? waitFor("Docker health stays UP during AI/RAG failure", async () => {
    const unavailable = await promQueryScalar(`http://127.0.0.1:${prometheusPort}`, 'helpdesk_rag_readiness{state="unavailable"}');
    if (unavailable !== 1) return false;
    const health = await containerHealth(composeArgs, "app");
    if (health !== "healthy") throw new Error(`Docker app health during AI/RAG failure was ${health ?? "unavailable"}`);
    return { ragUnavailable: true, dockerHealth: health };
  }).then(value => ({ value }), error => ({ error })) : null;
  const playwright = await command("npm", browserArgs, { env: testEnv });
  if (playwright.code !== 0) throw new Error(`Playwright exited with status ${playwright.code}`);
  if (aiDockerHealthProbe) {
    const probe = await aiDockerHealthProbe;
    if (probe.error) throw probe.error;
    monitoringVerification.aiFailureDockerHealth = probe.value;
  }

  const reportPath = path.join(artifactDir, "playwright-results.json");
  const report = JSON.parse(await fs.readFile(reportPath, "utf8"));
  const tests = [];
  const collectTests = (suites = []) => {
    for (const suite of suites) {
      tests.push(...(suite.specs ?? []).flatMap((spec) => spec.tests ?? []));
      collectTests(suite.suites ?? []);
    }
  };
  collectTests(report.suites);
  testCounts = tests.reduce((counts, item) => {
    const status = item.results?.at(-1)?.status ?? "unknown";
    counts[status] = (counts[status] ?? 0) + 1;
    return counts;
  }, { total: tests.length });
  if (testCounts.passed !== testCounts.total || testCounts.total === 0) {
    throw new Error("Playwright JSON report did not show all tests passing");
  }
  if (monitoringEnabled) {
    await waitForAlertEvent(`http://127.0.0.1:${alertSinkPort}`, "HelpDeskRagUnavailable", "firing");
    await waitForAlertEvent(`http://127.0.0.1:${alertSinkPort}`, "HelpDeskRagUnavailable", "resolved");
    monitoringVerification.aiAlertTransitions = "firing and resolved webhook received";
    const exported = await command("docker", [...composeArgs, "exec", "--no-TTY", "app", "curl", "--silent", "--show-error", "http://127.0.0.1:8081/actuator/prometheus"], { capture: true });
    if (exported.code !== 0) throw new Error("Internal Prometheus endpoint could not be scraped from the app container");
    const metricText = exported.stdout;
    const requiredMetrics = [
      "helpdesk_core_readiness", "helpdesk_rag_readiness", "http_server_requests_seconds_bucket",
      "ai_tool_calls_total", "ai_tool_latency_seconds_bucket", "ai_tokens_total",
      "ai_latency_seconds_bucket", "jvm_memory_used_bytes", "hikaricp_connections_active",
    ];
    const missingMetrics = requiredMetrics.filter((name) => !metricText.includes(name));
    if (missingMetrics.length) throw new Error(`Prometheus scrape is missing required metric families: ${missingMetrics.join(", ")}`);
    if (/\b(?:userId|user_id|sessionId|session_id|ticketId|ticket_id|question|email)=/.test(metricText)) {
      throw new Error("Prometheus scrape contains a forbidden high-cardinality or personal-data label");
    }
    const coreEndpoint = await command("docker", [...composeArgs, "exec", "--no-TTY", "app", "curl", "--silent", "--output", "/dev/null", "--write-out", "%{http_code}", "http://127.0.0.1:8080/actuator/prometheus"], { capture: true });
    if (coreEndpoint.code !== 0 || coreEndpoint.stdout.trim() === "200") {
      throw new Error("Prometheus endpoint is reachable on the main application port");
    }
    monitoringVerification.metricFamilies = requiredMetrics;
    monitoringVerification.personalOrHighCardinalityLabels = "not found";
    monitoringVerification.mainApplicationPortPrometheus = `HTTP ${coreEndpoint.stdout.trim() || "probe failed"}`;
  }

  if (process.env.E2E_PERFORMANCE_ONLY === "1") {
    const syntheticSql = `BEGIN;
INSERT INTO tickets (ticket_number, id, type, resource, reason, requested_by, requested_at, status, idempotency_key, request_fingerprint)
SELECT 100000 + n, 'PERF-' || lpad(n::text, 5, '0'),
       CASE WHEN n % 3 = 0 THEN 'INCIDENT' WHEN n % 3 = 1 THEN 'ACCESS_REQUEST' ELSE 'ACCOUNT_SUPPORT' END,
       CASE n % 4 WHEN 0 THEN 'VPN' WHEN 1 THEN 'DEV_DB' WHEN 2 THEN 'PROD_DB' ELSE 'ACCOUNT' END,
       'synthetic performance-plan row', 'load-user-' || lpad((n % 100)::text, 3, '0'),
       now() - make_interval(secs => n::double precision), 'PENDING', 'perf-key-' || n, repeat('a', 64)
FROM generate_series(1, 10000) AS n;
SELECT setval('helpdesk_ticket_number_seq', (SELECT max(ticket_number) FROM tickets));
ANALYZE tickets;
COMMIT;
SELECT 'rows=' || count(*) FROM tickets;
SELECT 'idempotency_keys=' || count(idempotency_key) FROM tickets;
SELECT 'requesters=' || count(DISTINCT requested_by) FROM tickets;
SELECT 'indexes=' || string_agg(indexname, ',' ORDER BY indexname) FROM pg_indexes WHERE tablename='tickets';
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT id,type,resource,reason,requested_by,requested_at,status FROM tickets WHERE requested_by='load-user-010' ORDER BY requested_at DESC LIMIT 20;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT id FROM tickets WHERE requested_by='load-user-010' AND idempotency_key='perf-key-10';
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT id,type,resource,reason,requested_by,requested_at,status FROM tickets ORDER BY ticket_number;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT id,type,resource,reason,requested_by,requested_at,status FROM tickets WHERE requested_by='load-user-010' AND type='ACCESS_REQUEST' AND status='PENDING' AND upper(resource)=upper('PROD_DB') ORDER BY ticket_number DESC LIMIT 1;`;
    const plan = await command("docker", [...composeArgs, "exec", "--no-TTY", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-X", "-q", "-U", "helpdesk", "-d", "helpdesk", "-c", syntheticSql], { capture: true });
    if (plan.code !== 0) throw new Error("Could not record isolated PostgreSQL query plans");
    await fs.writeFile(path.join(artifactDir, "sql-plans.txt"), redact(plan.stdout + plan.stderr));
    const appLogs = await command("docker", [...composeArgs, "logs", "--no-color", "app"], { capture: true });
    const timingLines = `${appLogs.stdout}\n${appLogs.stderr}`.split(/\r?\n/)
      .filter((line) => line.includes("[PERF] rag.source.lookup latencyMs=")
        || line.includes("[PERF] rag.source.extract latencyMs="));
    await fs.writeFile(path.join(artifactDir, "rag-source-processing-timings.txt"), redact(timingLines.join("\n") + "\n"));
  }

  const stopDatabase = await command("docker", [...composeArgs, "stop", "postgres"], { capture: true });
  if (stopDatabase.code !== 0) throw new Error("Could not stop the isolated E2E PostgreSQL service for health verification");
  try {
    const readiness = await command("docker", [...composeArgs, "exec", "--no-TTY", "app", "curl", "--silent", "--show-error", "--max-time", "35", `${healthBase}/actuator/health/readiness`], { capture: true });
    const liveness = await command("docker", [...composeArgs, "exec", "--no-TTY", "app", "curl", "--silent", "--show-error", "--max-time", "5", `${healthBase}/actuator/health/liveness`], { capture: true });
    if (!readiness.stdout.trim()) {
      throw new Error(`Database outage readiness probe returned no body (curl exit ${readiness.code})`);
    }
    const readinessBody = JSON.parse(readiness.stdout);
    const livenessBody = JSON.parse(liveness.stdout);
    if (readinessBody.status !== "DOWN" || livenessBody.status !== "UP") {
      throw new Error("Database outage health contract did not show readiness DOWN and liveness UP");
    }
    databaseHealthVerification = "postgres_stopped_readiness=DOWN\nliveness_during_database_outage=UP";
    if (monitoringEnabled) {
      const prometheusURL = `http://127.0.0.1:${prometheusPort}`;
      await waitFor("core readiness gauge down", async () => await promQueryScalar(prometheusURL, "helpdesk_core_readiness") === 0);
      await waitForAlertEvent(`http://127.0.0.1:${alertSinkPort}`, "HelpDeskCoreReadinessDown", "firing");
      monitoringVerification.databaseDownAlert = "readiness gauge 0 and firing webhook received";
      await waitFor("Docker health DOWN during database outage", async () => await containerHealth(composeArgs, "app") === "unhealthy", 30_000);
      monitoringVerification.databaseDownDockerHealth = "unhealthy";
    }
  } finally {
    const startDatabase = await command("docker", [...composeArgs, "start", "postgres"], { capture: true });
    if (startDatabase.code !== 0) throw new Error("Could not restart the isolated E2E PostgreSQL service");
  }
  let restored = false;
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const readiness = await command("docker", [...composeArgs, "exec", "--no-TTY", "app", "curl", "--silent", "--max-time", "5", `${healthBase}/actuator/health/readiness`], { capture: true });
    try {
      if (JSON.parse(readiness.stdout).status === "UP") {
        restored = true;
        break;
      }
    } catch { /* wait for database and pool recovery */ }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  if (!restored) throw new Error("Readiness did not recover after PostgreSQL restarted");
  databaseHealthVerification += "\nreadiness_after_database_restart=UP";
  if (monitoringEnabled) {
    const prometheusURL = `http://127.0.0.1:${prometheusPort}`;
    await waitFor("core readiness gauge recovery", async () => await promQueryScalar(prometheusURL, "helpdesk_core_readiness") === 1);
    await waitForAlertEvent(`http://127.0.0.1:${alertSinkPort}`, "HelpDeskCoreReadinessDown", "resolved");
    monitoringVerification.databaseRecoveryAlert = "readiness gauge 1 and resolved webhook received";
    await waitFor("Docker health recovery after database restart", async () => await containerHealth(composeArgs, "app") === "healthy", 60_000);
    monitoringVerification.databaseRecoveryDockerHealth = "healthy";
    monitoringVerification.databaseAlertTransitions = "firing and resolved webhook received";
    await fs.writeFile(path.join(artifactDir, "monitoring-verification.json"), JSON.stringify(monitoringVerification, null, 2) + "\n");
  }

  const sql = [
    "select 'tickets='||count(*) from tickets;",
    "select 'idempotency_keys='||count(idempotency_key) from tickets;",
    "select 'pending_tickets='||count(*) filter (where status='PENDING') from tickets;",
    "select 'unique_key_constraint='||exists(select 1 from pg_constraint where conrelid='tickets'::regclass and conname='uq_tickets_requester_idempotency') from tickets limit 1;",
    "select 'requesters='||coalesce(string_agg(distinct requested_by,',' order by requested_by),'') from tickets;",
    "select 'flyway_versions='||string_agg(version,',' order by installed_rank) from flyway_schema_history where success;",
  ].join(" ");
  const database = await command("docker", [...composeArgs, "exec", "--no-TTY", "postgres", "psql", "-U", "helpdesk", "-d", "helpdesk", "-At", "-c", sql], { capture: true });
  if (database.code !== 0) throw new Error("Could not verify the isolated E2E PostgreSQL database");
  databaseVerification = redact(database.stdout.trim());
  await fs.writeFile(path.join(artifactDir, "database-verification.txt"), `${databaseVerification}\n`);
  await fs.writeFile(path.join(artifactDir, "database-health-verification.txt"), `${databaseHealthVerification}\n`);

  const fields = Object.fromEntries(databaseVerification.split("\n").map((line) => {
    const separator = line.indexOf("=");
    return [line.slice(0, separator), line.slice(separator + 1)];
  }));
  if (Number(fields.tickets) < 3
      || fields.idempotency_keys !== fields.tickets
      || fields.pending_tickets !== fields.tickets
      || fields.unique_key_constraint !== "true"
      || fields.flyway_versions !== "1,2"
      || !fields.requesters?.includes("admin")
      || !fields.requesters?.includes("user1")) {
    throw new Error("The E2E database did not match the expected ticket, idempotency, or migration invariants");
  }

  runCode = 0;
} catch (error) {
  failure = error instanceof Error ? error.message : "unknown E2E runner error";
  if (composeMayExist) {
    const logs = await command("docker", [...composeArgs, "logs", "--no-color", "--timestamps"], { capture: true });
    await fs.writeFile(path.join(artifactDir, "compose-failure.log"), redact(logs.stdout + logs.stderr));
  }
} finally {
  if (composeMayExist) {
    const down = await command("docker", [...composeArgs, "down", "--volumes", "--rmi", "local", "--remove-orphans"]);
    if (down.code !== 0 && runCode === 0) {
      runCode = 1;
      failure = "E2E stack cleanup failed; inspect only the generated E2E project and volume";
    }
  }
  await fs.unlink(envFile).catch(() => {});
  const summary = {
    startedAt,
    finishedAt: new Date().toISOString(),
    result: runCode === 0 ? "passed" : "failed",
    environment: "local Docker Compose, PostgreSQL, Chromium, deterministic OpenAI-compatible test double",
    liveLlmCalls: 0,
    awsResourcesCreated: 0,
    tests: testCounts,
    databaseVerification,
    databaseHealthVerification,
    monitoringVerification,
    failure: failure || undefined,
  };
  await fs.writeFile(path.join(artifactDir, "run-summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
  console.log(`E2E evidence: ${path.relative(root, artifactDir)}`);
  if (failure) console.error(`E2E runner: ${failure}`);
}

process.exitCode = runCode;
