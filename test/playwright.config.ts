import * as path from "path";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

// Single source of truth for the port is the PORT env var (with the
// canonical default of 3110 — same port the dev server uses, since we
// never run e2e and dev concurrently). The clojure server reads it via
// config.edn's `#env PORT` reader tag; playwright just uses the same
// env-derived value here.
const port = process.env.PORT || "3110";

const command = `DEV=true clojure -X:run :e2e true`;

const testDir = defineBddConfig({
  features: path.resolve(__dirname, "e2e/features"),
  steps: path.resolve(__dirname, "e2e/steps/*.ts"),
});

export default defineConfig({
  testDir,
  timeout: 60_000,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  expect: { timeout: 10_000 },
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    headless: true,
    actionTimeout: 10_000,
    // The card menu's "Copy link" goes through navigator.clipboard, and the
    // app deliberately shows its error banner instead of the save-flash when
    // that write rejects — so a missing permission reads as a product bug.
    //
    // Which chromium runs decides whether the permission is there by default:
    // a full chromium build (new headless — what the executablePath below
    // selects) auto-grants clipboard-write, while playwright's own
    // `headless: true` default resolves to the chromium-headless-shell build
    // (old headless), whose permission manager denies it. That is why the
    // copy scenario passed everywhere except inside Dockerfile.e2e, which is
    // the one target using the bundled shell. Granting it here — for every
    // context, before any test runs — makes both targets behave alike.
    // clipboard-read is never auto-granted anywhere and is what the
    // "the clipboard holds" step needs to read the value back.
    permissions: ["clipboard-read", "clipboard-write"],
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
      launchOptions: {
        args: ["--no-sandbox", "--disable-dev-shm-usage"],
        ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
          ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH }
          : {}),
      },
    },
  }],
  webServer: {
    command,
    cwd: path.resolve(__dirname, ".."),
    url: `http://127.0.0.1:${port}`,
    timeout: 120_000,
    reuseExistingServer: false,
    // The dev rate limit (720 req/60s) is tight for a sequential SPA e2e
    // suite — boot fans out ~8 requests, setup POSTs add 10+, and bursts
    // cross the limit. Hitting 429 on /api/test/reset breaks the next
    // test (empty body → JSON.parse error → cascading locator timeouts).
    // Raise the cap for the test server only; prod still uses the default.
    env: { RATE_LIMIT_MAX_REQUESTS: "99999" },
  },
});
