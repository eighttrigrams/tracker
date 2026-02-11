import { execSync } from "child_process";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

const port = execSync(`bb -e '(:port (read-string (slurp "config.edn")))'`, { encoding: "utf-8" }).trim();
if (!port) throw new Error("Could not read :port from config.edn");

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
    headless: !!process.env.CI,
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: {
    command: `./scripts/stop.sh && npx shadow-cljs release app && DEV=true clojure -X:run :e2e true`,
    url: `http://localhost:${port}`,
    timeout: 120_000,
    reuseExistingServer: false,
  },
});
