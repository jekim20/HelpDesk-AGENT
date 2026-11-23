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

const composeFiles = [
  "-f", path.join(root, "compose.deploy.yaml"),
  "-f", path.join(e2eDir, "compose.e2e.yaml"),
];
const demoPort = await freePort();
let mockPort = await freePort();
while (mockPort === demoPort) mockPort = await freePort();
const composeArgs = ["compose", "--env-file", envFile, "-p", project, ...composeFiles];
const baseURL = `http://127.0.0.1:${demoPort}`;
const mockURL = `http://127.0.0.1:${mockPort}`;
let composeMayExist = false;
let runCode = 1;
let failure = "";
let testCounts = null;

await fs.mkdir(path.dirname(artifactDir), { recursive: true });
await fs.mkdir(artifactDir, { recursive: false });
await fs.writeFile(envFile, [
  `DEMO_PORT=${demoPort}`,
  `AI_MOCK_PORT=${mockPort}`,
  "HELPDESK_OPENAI_API_KEY=e2e-no-external-key",
  ...Object.entries(credentials).map(([key, value]) => `${key}=${value}`),
].join("\n") + "\n", { mode: 0o600 });

const testEnv = {
  ...process.env,
  E2E_BASE_URL: baseURL,
  E2E_MOCK_CONTROL_URL: mockURL,
  E2E_ARTIFACT_DIR: artifactDir,
  E2E_ADMIN_PASSWORD: credentials.APP_ADMIN_PASSWORD,
  E2E_USER1_PASSWORD: credentials.APP_USER1_PASSWORD,
  E2E_USER2_PASSWORD: credentials.APP_USER2_PASSWORD,
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

  const playwright = await command("npm", ["run", "test:browser", "--prefix", e2eDir, "--", "--config", path.join(e2eDir, "playwright.config.ts")], { env: testEnv });
  if (playwright.code !== 0) throw new Error(`Playwright exited with status ${playwright.code}`);

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
    failure: failure || undefined,
  };
  await fs.writeFile(path.join(artifactDir, "run-summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
  console.log(`E2E evidence: ${path.relative(root, artifactDir)}`);
  if (failure) console.error(`E2E runner: ${failure}`);
}

process.exitCode = runCode;
