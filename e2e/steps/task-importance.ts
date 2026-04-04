import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a task {string} exists", async ({ request }, title: string) => {
  await request.post("/api/tasks", { headers, data: { title } });
});

Given(
  "a task {string} with importance {string} exists",
  async ({ request }, title: string, importance: string) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
    await request.put(`/api/tasks/${task.id}/importance`, {
      headers,
      data: { importance },
    });
  },
);

When("I click the critical importance filter", async ({ page }) => {
  await page.locator(".importance-filter-toggle button").filter({ hasText: "★★" }).click();
  await page.waitForLoadState("networkidle");
});

When("I click the important importance filter", async ({ page }) => {
  await page.locator(".importance-filter-toggle button").filter({ hasText: /^★$/ }).click();
  await page.waitForLoadState("networkidle");
});
