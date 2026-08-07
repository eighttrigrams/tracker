import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then, Before } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// Every /api GET the page makes, newest last, so a Then can ask what the *last*
// fetch of one collection carried. A list assertion alone cannot see this bug:
// when the switcher refetches the wrong endpoint the sub-mode list is simply
// left as it was, and a stale list agrees with the new scope whenever the two
// happen to coincide. The request is what says which endpoint was refetched at
// all, and with which context/strict.
let apiGets: string[] = [];

Before(async ({ page }) => {
  apiGets = [];
  page.on("request", (r) => {
    if (r.method() === "GET" && new URL(r.url()).pathname.startsWith("/api/")) {
      apiGets.push(r.url());
    }
  });
});

function lastFetchOf(pathname: string): URL {
  const found = apiGets
    .map((u) => new URL(u))
    .filter((u) => u.pathname === pathname)
    .pop();
  if (!found) {
    throw new Error(
      `no GET ${pathname} was made. The page fetched:\n  ` +
        [...new Set(apiGets.map((u) => new URL(u).pathname))].join("\n  "),
    );
  }
  return found;
}

Given(
  "a recurring task {string} scoped {string} exists",
  async ({ request }, title: string, scope: string) => {
    const rtask = await (
      await request.post("/api/recurring-tasks", { headers, data: { title } })
    ).json();
    const resp = await request.put(`/api/recurring-tasks/${rtask.id}/scope`, {
      headers,
      data: { scope },
    });
    expect(resp.ok()).toBeTruthy();
  },
);

Given(
  "a meeting series {string} scoped {string} exists",
  async ({ request }, title: string, scope: string) => {
    const series = await (
      await request.post("/api/meeting-series", { headers, data: { title } })
    ).json();
    const resp = await request.put(`/api/meeting-series/${series.id}/scope`, {
      headers,
      data: { scope },
    });
    expect(resp.ok()).toBeTruthy();
  },
);

// Clicking the scope button that is already active is what toggles strict mode
// — there is no control of its own for it (see components/controls's
// scope-toggle), so this is the only way to reach it from the navbar.
When("I toggle strict mode", async ({ page }) => {
  await page.locator(".work-private-toggle .toggle-option.active").click();
  await page.waitForLoadState("networkidle");
});

Then(
  "the last {string} fetch carried context {string}",
  async ({}, pathname: string, context: string) => {
    const url = lastFetchOf(pathname);
    expect(url.searchParams.get("context"), `last ${pathname} fetch was ${url.search}`).toBe(
      context,
    );
  },
);

// `strict` is only put on the query string when it is on, so "false" here means
// the parameter is absent — which is what the app means by not strict.
Then(
  "the last {string} fetch carried strict {string}",
  async ({}, pathname: string, strict: string) => {
    const url = lastFetchOf(pathname);
    expect(
      url.searchParams.get("strict") ?? "false",
      `last ${pathname} fetch was ${url.search}`,
    ).toBe(strict);
  },
);

Then("I should not see {string} in the series list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).not.toContainText(text);
});
