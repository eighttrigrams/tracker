import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

function todayItem(page: any, title: string) {
  return page
    .locator(".today-task-item")
    .filter({ has: page.locator(".task-title", { hasText: title }) });
}

When("I activate relation mode", async ({ page }) => {
  await page.locator(".relation-mode-toggle").click();
  await page.waitForLoadState("networkidle");
});

When(
  "I click the link button on the today item {string}",
  async ({ page }, title: string) => {
    await todayItem(page, title).locator(".relation-link-btn").click();
    await page.waitForLoadState("networkidle");
  },
);

When(
  "I click the link button on the resource {string}",
  async ({ page }, title: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: title })
      .locator(".relation-link-btn")
      .click();
    await page.waitForLoadState("networkidle");
  },
);

Then("the relation-mode toggle should be visible", async ({ page }) => {
  await expect(page.locator(".relation-mode-toggle")).toBeVisible({ timeout: 5000 });
});

Then(
  "today item {string} should show a relation badge for {string}",
  async ({ page }, title: string, target: string) => {
    await expect(
      todayItem(page, title).locator(".tag.relation"),
    ).toContainText(target, { timeout: 5000 });
  },
);
