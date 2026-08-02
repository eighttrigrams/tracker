import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { apiCategorize } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// The two-hop chain Alpha -> Beta -> Lagos is what tells a real bypass from a
// resolve-then-filter: a one-rule chain lets the second kind through. "Rule
// task" carries the whole chain's ends plus the ruleless "Extra", so it stays on
// screen whichever of them is filtered on and every gesture has a badge to aim
// at. The reload is what puts the seeded categories into the sidebar's lists,
// which load once at app start.
Given("test data for rule bypassing exists", async ({ page, request }) => {
  const alpha = await (await request.post("/api/projects", { headers, data: { name: "Alpha" } })).json();
  const beta = await (await request.post("/api/projects", { headers, data: { name: "Beta" } })).json();
  const extra = await (await request.post("/api/projects", { headers, data: { name: "Extra" } })).json();
  const lagos = await (await request.post("/api/places", { headers, data: { name: "Lagos" } })).json();

  await request.post("/api/category-rules", {
    headers,
    data: { "source-type": "project", "source-id": alpha.id, "target-type": "project", "target-id": beta.id },
  });
  await request.post("/api/category-rules", {
    headers,
    data: { "source-type": "project", "source-id": beta.id, "target-type": "place", "target-id": lagos.id },
  });

  const task = await (await request.post("/api/tasks", { headers, data: { title: "Rule task" } })).json();
  await apiCategorize(request, `/api/tasks/${task.id}`, "project", alpha.id);
  await apiCategorize(request, `/api/tasks/${task.id}`, "project", extra.id);
  await apiCategorize(request, `/api/tasks/${task.id}`, "place", lagos.id);

  await page.reload();
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".top-bar .tabs")).toBeVisible();
});

// "No rule was applied" and "the resolver was never asked" are different
// claims, and only the second one rules out a resolve-then-filter that happens
// to agree on this data. Counting the POSTs is the direct assertion; the count
// starts where the scenario's own gesture starts, since selecting the
// pre-condition through the sidebar resolves a closure of its own.
const resolveCounts = new WeakMap<object, { n: number }>();

When("I start counting rule resolutions", async ({ page }) => {
  const counter = { n: 0 };
  resolveCounts.set(page, counter);
  page.on("request", (req) => {
    if (req.url().includes("/api/category-rules/resolve")) counter.n += 1;
  });
});

Then("no rule resolution should have been requested", async ({ page }) => {
  await page.waitForLoadState("networkidle");
  expect(resolveCounts.get(page)?.n).toBe(0);
});

Then("a rule resolution should have been requested", async ({ page }) => {
  await expect(async () => {
    expect(resolveCounts.get(page)?.n ?? 0).toBeGreaterThan(0);
  }).toPass({ timeout: 10000 });
});

When(
  "I shift-option-click the {string} badge on task {string}",
  async ({ page }, badge: string, task: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge })
      .first()
      .click({ modifiers: ["Shift", "Alt"] });
    await page.waitForLoadState("networkidle");
  },
);

When(
  "I shift-option-click {string} in the {string} filter group",
  async ({ page }, name: string, group: string) => {
    const sec = page.locator(`.sidebar .filter-section.${group}`);
    if ((await sec.locator(".filter-item").count()) === 0) {
      await sec.locator(".collapse-toggle").click();
    }
    await sec.locator(".filter-item").filter({ hasText: name }).first()
      .click({ modifiers: ["Shift", "Alt"] });
    await expect(sec.locator(".clear-filter")).toBeVisible();
    await page.waitForLoadState("networkidle");
  },
);

// Only assert this behind a positive assertion on the same gesture — the
// selection a bypass wrongly added would render a frame after the click, so on
// its own an absent one proves nothing.
Then(
  "the {string} filter should not show {string} as selected",
  async ({ page }, group: string, name: string) => {
    const sec = page.locator(`.sidebar .filter-section.${group}`);
    if ((await sec.locator(".filter-item").count()) === 0) {
      await sec.locator(".collapse-toggle").click();
    }
    const item = sec.locator(".filter-item").filter({ hasText: name });
    await expect(item).toHaveCount(1);
    await expect(item).not.toHaveClass(/active/);
  },
);
