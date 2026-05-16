import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Then } = createBdd();

Then(
  "I should see {string} badge on resource {string}",
  async ({ page }, badge: string, title: string) => {
    const item = page.locator(".items li").filter({ hasText: title });
    await expect(item.locator(".mail-sender")).toContainText(badge, { timeout: 5000 });
  },
);
