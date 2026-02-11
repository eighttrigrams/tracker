import { execSync } from "child_process";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

const ci = !!process.env.CI;

let port = "3027";
try {
  const result = execSync(`bb -e '(:port (read-string (slurp "config.edn")))' 2>/dev/null`, { encoding: "utf-8" }).trim();
  if (result) port = result;
} catch {}

const command = ci
  ? `DEV=true clojure -X:run :e2e true`
  : `./scripts/stop.sh && npx shadow-cljs release app && DEV=true clojure -X:run :e2e true`;

const testDir = defineBddConfig({
  features: "./e2e/features",
  steps: "./e2e/steps/*.ts",
});

export default defineConfig({
  testDir,
  timeout: 30_000,
  workers: 1,
  use: {
    baseURL: `http://localhost:${port}`,
    headless: ci,
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: {
    command,
    url: `http://localhost:${port}`,
    timeout: 120_000,
    reuseExistingServer: false,
  },
});
