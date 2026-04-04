import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a recurring task {string} exists", async ({ request }, title: string) => {
  await request.post("/api/recurring-tasks", { headers, data: { title } });
});

When("I expand {string} in the recurring list", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".item-header").click();
  await page.waitForLoadState("networkidle");
});

When("I click the edit button on the expanded item", async ({ page }) => {
  await page.locator(".edit-icon").click();
  await page.waitForLoadState("networkidle");
});
