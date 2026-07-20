import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

When(
  "I add a rule from {string} to {string}",
  async ({ page }, source: string, target: string) => {
    await page.locator('[data-testid="rules-source-picker"]').selectOption({ label: source });
    await page.locator('[data-testid="rules-target-picker"]').selectOption({ label: target });
    await page.locator('[data-testid="rules-add-button"]').click();
    await expect(page.locator(".rule-row").filter({ hasText: source })).toBeVisible();
  },
);

Then(
  "I should see a rule from {string} to {string}",
  async ({ page }, source: string, target: string) => {
    const row = page.locator(".rule-row").filter({ hasText: source }).filter({ hasText: target });
    await expect(row.first()).toBeVisible();
  },
);

When(
  "I assign the person {string} to task {string}",
  async ({ page }, person: string, task: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: task }).first();
    const personBtn = taskRow.getByRole("button", { name: "+ Person" });
    if (!(await personBtn.isVisible())) {
      await taskRow.locator(".item-header").click();
      await personBtn.waitFor({ state: "visible" });
    }
    await personBtn.click();
    await page.locator(".category-selector-item").filter({ hasText: person }).first().click();
    await expect(taskRow.locator(".tag", { hasText: person })).toBeVisible();
  },
);

Then(
  "task {string} should show the tag {string}",
  async ({ page }, task: string, tag: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: task }).first();
    await expect(taskRow.locator(".tag", { hasText: tag }).first()).toBeVisible();
  },
);

When("I filter by person {string}", async ({ page }, name: string) => {
  const sec = page.locator(".filter-section.people");
  if ((await sec.locator(".filter-item").count()) === 0) {
    await sec.locator(".collapse-toggle").click();
  }
  const clear = sec.locator(".clear-filter");
  await expect(async () => {
    if (await clear.isVisible()) return;
    await sec.locator(".filter-item").filter({ hasText: name }).click({ timeout: 3000 });
    await expect(clear).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 15000 });
  await page.waitForLoadState("networkidle");
});

Then(
  "the {string} filter should show {string} as selected",
  async ({ page }, section: string, name: string) => {
    const sec = page.locator(`.sidebar .filter-section.${section}`);
    if ((await sec.locator(".filter-item").count()) === 0) {
      await sec.locator(".collapse-toggle").click();
    }
    const item = sec.locator(".filter-item.active").filter({ hasText: name });
    await expect(item.first()).toBeVisible();
  },
);
