import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given(
  "a journal {string} exists with schedule type {string}",
  async ({ request }, title: string, scheduleType: string) => {
    await request.post("/api/journals", {
      headers,
      data: { title, "schedule-type": scheduleType },
    });
  },
);

Given(
  "a journal {string} exists with schedule type {string} and scope {string}",
  async ({ request }, title: string, scheduleType: string, scope: string) => {
    await request.post("/api/journals", {
      headers,
      data: { title, "schedule-type": scheduleType, scope },
    });
  },
);

When("I select {string} in the journal type modal", async ({ page }, type: string) => {
  await page.locator(".modal-overlay").waitFor({ state: "visible", timeout: 5000 });
  await page.locator(".modal-overlay .toggle-option").filter({ hasText: type }).click();
  await page.waitForLoadState("networkidle");
});

Then("I should see {string} in the journals list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("I should see {string} badge on {string}", async ({ page }, badge: string, title: string) => {
  const item = page.locator(".items li").filter({ hasText: title });
  await expect(item).toContainText(badge, { timeout: 5000 });
});

Then("the importance filter should not be visible", async ({ page }) => {
  await expect(page.locator(".importance-filter-toggle")).toHaveCount(0);
});

Then("the sort toggle should not be visible", async ({ page }) => {
  await expect(page.locator(".sort-toggle")).toHaveCount(0);
});

Then("I should see {string}", async ({ page }, text: string) => {
  await expect(page.locator(".main-content")).toContainText(text, { timeout: 5000 });
});
