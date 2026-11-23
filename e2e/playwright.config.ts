import path from "node:path";
import { defineConfig } from "@playwright/test";

const evidenceDir = process.env.E2E_ARTIFACT_DIR ?? path.resolve("artifacts/e2e/manual-run");

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 8_000 },
  outputDir: path.join(evidenceDir, "test-output"),
  reporter: [
    ["list"],
    ["json", { outputFile: path.join(evidenceDir, "playwright-results.json") }],
    ["html", { outputFolder: path.join(evidenceDir, "html-report"), open: "never" }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://127.0.0.1:18080",
    browserName: "chromium",
    headless: true,
    screenshot: "only-on-failure",
    trace: "off",
  },
});
