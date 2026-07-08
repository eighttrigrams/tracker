import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

When("I open the send-to-day picker on task {string}", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".link-today-btn").click();
});

Then("the send-to-day picker on task {string} is open", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card.locator(".send-to-day-dropdown")).toBeVisible({ timeout: 5000 });
});

Then("the send-to-day picker on task {string} is closed", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card).toBeVisible({ timeout: 5000 });
  await expect(card.locator(".send-to-day-dropdown")).toHaveCount(0);
});
