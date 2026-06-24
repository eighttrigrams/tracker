import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Then } = createBdd();

Then(
  "the meet {string} in the today section should be grayed because over",
  async ({ page }, title: string) => {
    const item = page
      .locator(".today-section.today .today-task-item.meet-item")
      .filter({ hasText: title });
    await expect(item).toHaveClass(/\bover\b/, { timeout: 5000 });
  },
);

Then(
  "the meet {string} in the today section should no longer be grayed because over",
  async ({ page }, title: string) => {
    const item = page
      .locator(".today-section.today .today-task-item.meet-item")
      .filter({ hasText: title });
    await expect(item).not.toHaveClass(/\bover\b/, { timeout: 5000 });
  },
);

Then(
  "the meet {string} in the today section should no longer be maybe",
  async ({ page }, title: string) => {
    const item = page
      .locator(".today-section.today .today-task-item.meet-item")
      .filter({ hasText: title });
    await expect(item).not.toHaveClass(/\bmaybe\b/, { timeout: 5000 });
  },
);

Then("the meet footer dropdown does not show {string}", async ({ page }, label: string) => {
  await expect(
    page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: label }),
  ).toHaveCount(0);
});

Then("the expanded meet footer does not offer {string}", async ({ page }, label: string) => {
  await expect(page.locator(".item-actions-right").filter({ hasText: label })).toHaveCount(0);
});
