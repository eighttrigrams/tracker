import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const daysAgo = (n: number) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);

Given(
  "a done task {string} completed {int} days ago exists",
  async ({ request }, title: string, days: number) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
    await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
    await request.put(`/api/tasks/${task.id}/done-at`, { headers, data: { "done-date": daysAgo(days) } });
  },
);

When("I click the reports See more button", async ({ page }) => {
  await page.locator(".load-more-btn").click();
  await page.waitForLoadState("networkidle");
});

Then("I should see {string} in the reports", async ({ page }, text: string) => {
  await expect(page.locator(".report-weeks")).toContainText(text, { timeout: 5000 });
});

Then("I should not see {string} in the reports", async ({ page }, text: string) => {
  await expect(page.locator(".report-item").filter({ hasText: text })).toHaveCount(0);
});

Then("I should see the reports See more button", async ({ page }) => {
  await expect(page.locator(".load-more-btn")).toBeVisible();
});

Then("I should not see the reports See more button", async ({ page }) => {
  await expect(page.locator(".load-more-btn")).toHaveCount(0);
});
