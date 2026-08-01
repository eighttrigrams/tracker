import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { dragCard } from "./helpers";

const { When, Then } = createBdd();

// Issue cards in the urgency blocks carry both classes, so the task selector has
// to exclude them.
const URGENT_TASKS = ".urgency-subsection.urgent .draggable-urgent-task:not(.draggable-urgent-issue)";
const URGENT_ISSUES = ".urgency-subsection.urgent .draggable-urgent-issue";

async function expectOrder(page: any, selector: string, csv: string) {
  const expected = csv.split(",").map((s) => s.trim());
  const items = page.locator(selector);
  await expect(items).toHaveCount(expected.length);
  for (let i = 0; i < expected.length; i++) {
    await expect(items.nth(i)).toContainText(expected[i]);
  }
}

When(
  "I drag the urgent task {string} before the urgent task {string}",
  async ({ page }, source: string, target: string) => {
    await dragCard(page, URGENT_TASKS, source, target, "before");
  },
);

When(
  "I drag the urgent issue {string} before the urgent issue {string}",
  async ({ page }, source: string, target: string) => {
    await dragCard(page, URGENT_ISSUES, source, target, "before");
  },
);

// The Issues page has a plain button group rather than the Tasks page's
// sort dropdown.
When("I click the manual sort button on the Issues page", async ({ page }) => {
  await page.locator(".sort-toggle button", { hasText: "Manual" }).click();
  await page.waitForLoadState("networkidle");
});

Then("the urgent task list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, URGENT_TASKS, csv);
});

Then("the urgent issue list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, URGENT_ISSUES, csv);
});

Then("the issue list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, ".items li", csv);
});
