import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const daysAgo = (n: number) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);

// Shared report seeding/assertions (also used by other reports features).
Given(
  "a done task {string} completed {int} days ago exists",
  async ({ request }, title: string, days: number) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
    await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
    await request.put(`/api/tasks/${task.id}/done-at`, { headers, data: { "done-date": daysAgo(days) } });
  },
);

// "a resolved issue {string} exists" is shared — defined in issues.ts.

Then("I should see {string} in the reports", async ({ page }, text: string) => {
  await expect(page.locator(".report-weeks")).toContainText(text, { timeout: 5000 });
});

Then("I should not see {string} in the reports", async ({ page }, text: string) => {
  await expect(page.locator(".report-item").filter({ hasText: text })).toHaveCount(0);
});

// Week(s) picker interactions.
When("I increase the reports scope", async ({ page }) => {
  await page.locator(".week-control-scope .week-control-up").click();
  await page.waitForLoadState("networkidle");
});

When("I shift the reports From anchor back", async ({ page }) => {
  await page.locator(".week-control-from .week-control-down").click();
  await page.waitForLoadState("networkidle");
});

Then("I should see a report week divider", async ({ page }) => {
  await expect(page.locator(".report-week-header").first()).toBeVisible({ timeout: 5000 });
});

Then("I should not see a report week divider", async ({ page }) => {
  await expect(page.locator(".report-week-header")).toHaveCount(0);
});

Then("the report shows a resolved issue card", async ({ page }) => {
  await expect(page.locator(".report-issue").first()).toBeVisible({ timeout: 5000 });
});

Then("the reports scope shows {string}", async ({ page }, text: string) => {
  await expect(page.locator(".week-control-scope .week-control-label")).toContainText(text);
});

Then("the reports From anchor is a fixed week", async ({ page }) => {
  // A shifted anchor shows a concrete "CW n", never the dynamic "This Week".
  await expect(page.locator(".week-control-from .week-control-label")).not.toContainText("This Week");
});
