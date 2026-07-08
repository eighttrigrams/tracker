import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// The navbar scope switcher renders three .toggle-option buttons in the
// order private / both / work.
const scopeIndex: Record<string, number> = { private: 0, both: 1, work: 2 };

When("I switch scope to {string}", async ({ page }, scope: string) => {
  const toggle = page.locator(".top-bar-right .work-private-toggle");
  await expect(toggle).toBeVisible();
  await toggle.locator(".toggle-option").nth(scopeIndex[scope]).click();
  await page.waitForLoadState("networkidle");
});

When(
  "I set the card {string} scope to {string}",
  async ({ page }, name: string, scope: string) => {
    const card = page.locator(".category-card").filter({ hasText: name });
    const selector = card.locator(".task-scope-selector");
    await expect(selector).toBeVisible();
    await selector.locator(".toggle-option").filter({ hasText: scope }).click();
    await page.waitForLoadState("networkidle");
  },
);

Then("I should see the scope switcher", async ({ page }) => {
  await expect(page.locator(".top-bar-right .work-private-toggle")).toBeVisible();
});

Then("I should not see {string} in the category cards", async ({ page }, name: string) => {
  await expect(page.locator(".category-cards-grid")).not.toContainText(name);
});

Then(
  "task {string} shows the category badge {string}",
  async ({ page }, task: string, badge: string) => {
    const row = page.locator(".items li").filter({ hasText: task }).first();
    await expect(row.locator(".tag").filter({ hasText: badge })).toBeVisible();
  },
);

Then(
  "task {string} does not show the category badge {string}",
  async ({ page }, task: string, badge: string) => {
    const row = page.locator(".items li").filter({ hasText: task }).first();
    await expect(row.locator(".tag").filter({ hasText: badge })).toHaveCount(0);
  },
);
