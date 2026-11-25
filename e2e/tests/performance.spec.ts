import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, spawnSync } from "node:child_process";
import { expect, test } from "@playwright/test";

const baseURL = process.env.E2E_BASE_URL ?? "http://127.0.0.1:18080";
const mockURL = process.env.E2E_MOCK_CONTROL_URL ?? "http://127.0.0.1:18081";
const artifactDir = process.env.E2E_ARTIFACT_DIR ?? path.resolve("artifacts/e2e/manual-run");
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const question = "VPN 승인에는 얼마나 걸려?";

async function mockRequest(endpoint: string, method = "GET") {
  const response = await fetch(`${mockURL}${endpoint}`, { method });
  if (!response.ok) throw new Error(`test double control request failed: HTTP ${response.status}`);
  return response.json();
}

async function submit(page: import("@playwright/test").Page, value: string) {
  await page.getByLabel("Question").fill(value);
  const started = Date.now();
  const responsePromise = page.waitForResponse((response) =>
    response.url().endsWith("/api/chat") && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: /Send/ }).click();
  const response = await responsePromise;
  return { response, wallMs: Date.now() - started };
}

function stats(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = (p: number) => sorted[Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)];
  return {
    n: values.length,
    meanMs: values.reduce((sum, value) => sum + value, 0) / values.length,
    medianMs: percentile(0.5),
    p95Ms: percentile(0.95),
  };
}

test("@performance RAG baseline/optimized HTTP workload and k6 profile", async ({ page }) => {
  test.setTimeout(300_000);
  await page.goto(baseURL);
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill(process.env.E2E_ADMIN_PASSWORD ?? "");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Conversation" })).toBeVisible();

  // Three dummy writes make the isolated runner's post-run persistence checks meaningful.
  for (let index = 0; index < 3; index += 1) {
    await page.getByLabel("Question").fill(`VPN 장애 티켓을 접수해줘. 성능 실험 더미 ${index}.`);
    await page.getByLabel("Session ID").fill(`perf-ticket-${index}-${randomUUID()}`);
    const responsePromise = page.waitForResponse((response) =>
      response.url().endsWith("/api/chat") && response.request().method() === "POST",
    );
    // sendChat generates an idempotency key for the UI; the independent k6 path uses explicit UUID keys.
    await page.getByRole("button", { name: /Send/ }).click();
    expect((await responsePromise).status()).toBe(200);
    await expect(page.locator(".message--agent").last()).toContainText("PENDING");
  }

  await page.getByRole("button", { name: "Initialize policy knowledge" }).click();
  await expect(page.getByText("Ready for RAG demo")).toBeVisible({ timeout: 90_000 });
  await page.getByLabel("Session ID").fill(`perf-rag-${randomUUID()}`);

  // Warm-up requests are excluded; reset the deterministic provider counters afterwards.
  for (let index = 0; index < 3; index += 1) {
    const { response } = await submit(page, question);
    expect(response.status()).toBe(200);
  }
  await mockRequest("/__test__/reset", "POST");

  const latencies: number[] = [];
  const returnedSources: string[][] = [];
  for (let index = 0; index < 30; index += 1) {
    const { response, wallMs } = await submit(page, question);
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.answer).toContain("2영업일 이내 처리됩니다.");
    expect(body.sources).toContain("vpn-guide.md");
    latencies.push(wallMs);
    returnedSources.push(body.sources);
  }
  const normalCounts = await mockRequest("/__test__/state");

  await mockRequest("/__test__/reset", "POST");
  const fallback = await page.evaluate(async (fallbackQuestion) => {
    const csrf = await fetch("/api/csrf", { credentials: "same-origin" }).then((response) => response.json());
    const response = await fetch("/api/chat", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
      body: JSON.stringify({ question: fallbackQuestion, sessionId: `perf-fallback-${crypto.randomUUID()}`, simulatePrimaryFailure: true }),
    });
    return { status: response.status, body: await response.json() };
  }, question);
  expect(fallback.status).toBe(200);
  expect(fallback.body.fallbackUsed).toBe(true);
  expect(fallback.body.sources).toContain("vpn-guide.md");
  const fallbackCounts = await mockRequest("/__test__/state");
  await mockRequest("/__test__/reset", "POST");

  const measurementPath = path.join(artifactDir, "rag-http-measurements.json");
  const measurement = {
    environment: "isolated Docker Compose; PostgreSQL; deterministic OpenAI-compatible mock; Chromium; sequential browser requests",
    question,
    warmupRequests: 3,
    measuredRequests: latencies.length,
    timing: "Playwright browser wall time from send action through HTTP response; includes browser/UI overhead",
    httpLatency: stats(latencies),
    providerCounts: {
      normalRag: { similaritySearchCallsInferredFromQueryEmbeddings: normalCounts.embedding_requests, embeddingHttpRequests: normalCounts.embedding_requests, llmCalls: normalCounts.chat_completions },
      primaryFailureThenFallback: { similaritySearchCallsInferredFromQueryEmbeddings: fallbackCounts.embedding_requests, embeddingHttpRequests: fallbackCounts.embedding_requests, llmCalls: fallbackCounts.chat_completions },
    },
    returnedSources: returnedSources[0],
    allSourceListsSame: returnedSources.every((sources) => JSON.stringify(sources) === JSON.stringify(returnedSources[0])),
    fallback: { status: fallback.status, fallbackUsed: fallback.body.fallbackUsed, sources: fallback.body.sources },
  };
  await fs.writeFile(measurementPath, JSON.stringify(measurement, null, 2) + "\n");

  const completedMeasurement = JSON.parse(await fs.readFile(measurementPath, "utf8"));
  if (process.env.E2E_RUN_K6 !== "0") {
    const k6Output = path.join(artifactDir, "k6-output.txt");
    const k6Summary = path.join(artifactDir, "k6-summary.json");
    const k6 = spawn("k6", ["run", "--summary-export", k6Summary, path.join(repoRoot, "e2e", "performance", "helpdesk-load.js")], {
      cwd: repoRoot,
      env: {
        ...process.env,
        BASE_URL: baseURL,
        K6_USER_PASSWORD: process.env.E2E_USER1_PASSWORD ?? "",
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const k6OutputParts: string[] = [];
    k6.stdout.on("data", (chunk) => k6OutputParts.push(chunk.toString()));
    k6.stderr.on("data", (chunk) => k6OutputParts.push(chunk.toString()));
    const dockerStats: Array<{ sampledAt: string; output: string }> = [];
    let sampling = false;
    const sampleDockerStats = async () => {
      if (sampling) return;
      sampling = true;
      try {
        const project = process.env.E2E_COMPOSE_PROJECT;
        if (!project) return;
        const containers = spawnSync("docker", ["ps", "--filter", `label=com.docker.compose.project=${project}`, "--format", "{{.Names}}"], {
          cwd: repoRoot, encoding: "utf8",
        });
        const names = (containers.stdout ?? "").trim().split(/\r?\n/).filter(Boolean);
        if (containers.status === 0 && names.length > 0) {
          const stats = spawnSync("docker", ["stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}},{{.MemUsage}}", ...names], {
            cwd: repoRoot, encoding: "utf8",
          });
          if (stats.status === 0) dockerStats.push({ sampledAt: new Date().toISOString(), output: stats.stdout.trim() });
        }
      } finally {
        sampling = false;
      }
    };
    await sampleDockerStats();
    const sampler = setInterval(() => { void sampleDockerStats(); }, 1000);
    const k6Status = await new Promise<number>((resolve, reject) => {
      k6.once("error", reject);
      k6.once("close", (code) => resolve(code ?? 1));
    });
    clearInterval(sampler);
    await sampleDockerStats();
    const output = k6OutputParts.join("");
    await fs.writeFile(k6Output, output.replace(/\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{16})\b/g, "[REDACTED]"));
    await fs.writeFile(path.join(artifactDir, "docker-stats-samples.json"), JSON.stringify(dockerStats, null, 2) + "\n");
    expect(k6Status, output).toBe(0);

    const k6ProviderCounts = await mockRequest("/__test__/state");
    expect(k6ProviderCounts.chat_completions).toBeGreaterThan(0);
    if (process.env.E2E_EXPECT_SINGLE_RAG_SEARCH !== "0") {
      expect(k6ProviderCounts.embedding_requests).toBe(k6ProviderCounts.chat_completions);
    } else {
      expect(k6ProviderCounts.embedding_requests).toBe(2 * k6ProviderCounts.chat_completions);
    }
    completedMeasurement.k6 = {
      providerCounts: {
        embeddingRequests: k6ProviderCounts.embedding_requests,
        llmCalls: k6ProviderCounts.chat_completions,
        noModelPathRequests: "k6 access lookup, ticket create, and Privacy Guard requests are not counted as model calls",
      },
      resourceSamples: "docker-stats-samples.json",
    };
  } else {
    completedMeasurement.k6 = { skipped: true, reason: "E2E_RUN_K6=0; browser RAG regression remains enabled" };
  }
  await fs.writeFile(measurementPath, JSON.stringify(completedMeasurement, null, 2) + "\n");

});
