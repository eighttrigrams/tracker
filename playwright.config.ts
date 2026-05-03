import { execSync } from "child_process";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

// Headless when CI=true. Both container flavours set CI:
//   - docker-plurama (Claude's working-env container, alpine) sets CI=true in compose
//   - Dockerfile.e2e (the throwaway image used by `make e2e-docker`) sets ENV CI=1
// On the host CI is unset, so the browser opens visibly during local debugging.
const ci = !!process.env.CI;

// Single source of truth for the port lives in config.edn (so the clojure
// server and the playwright config can't drift). We shell out to babashka
// to read it; if PORT is set explicitly that wins.
let port = process.env.PORT;
if (!port) {
  try {
    port = execSync(`bb -e '(:port (read-string (slurp "config.edn")))' 2>/dev/null`, { encoding: "utf-8" }).trim();
  } catch {}
}
if (!port) throw new Error("PORT env var not set and could not read :port from config.edn");

const command = `DEV=true clojure -X:run :e2e true`;

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
  projects: [{
    name: "chromium",
    use: {
      browserName: "chromium",
      // Two execution targets share this config:
      //   1. docker-plurama (Claude's working-env container, alpine) sets
      //      PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium so we
      //      use the OS-installed chromium — playwright's bundled chromium
      //      is glibc-only and won't run on musl.
      //   2. Dockerfile.e2e (the bookworm image used by `make e2e-docker`)
      //      and the host both leave the env var unset, so playwright
      //      falls back to its bundled chromium (installed via
      //      `npx playwright install [--with-deps] chromium`).
      // --no-sandbox is needed when running chromium inside a container,
      // --disable-dev-shm-usage avoids the small /dev/shm crashing tabs.
      ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
        ? { launchOptions: {
              executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
              args: ["--no-sandbox", "--disable-dev-shm-usage"],
            } }
        : {}),
    },
  }],
  webServer: {
    command,
    url: `http://localhost:${port}`,
    timeout: 120_000,
    reuseExistingServer: false,
  },
});
