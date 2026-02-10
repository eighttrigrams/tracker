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
    baseURL: "http://localhost:3029",
    headless: !!process.env.CI,
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: {
    command: "PORT=3029 ./scripts/stop.sh && PORT=3029 DEV=true clojure -X:run :e2e true",
    url: "http://localhost:3029",
    timeout: 120_000,
    reuseExistingServer: false,
  },
});
