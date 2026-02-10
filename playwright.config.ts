import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

const testDir = defineBddConfig({
  features: "./e2e/features",
  steps: "./e2e/steps/*.ts",
});

export default defineConfig({
  testDir,
  timeout: 30_000,
  workers: 1,
  use: {
    baseURL: "http://localhost:3027",
    headless: !!process.env.CI,
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: {
    command: "DEV=true clojure -X:run :with-sqlite-in-memory-db true :dangerously-skip-logins true",
    url: "http://localhost:3027",
    timeout: 120_000,
    reuseExistingServer: true,
  },
});
