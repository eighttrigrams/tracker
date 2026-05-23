import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given(
  "a motto {string} with description {string} exists",
  async ({ request }, title: string, description: string) => {
    await request.post("/api/mottos", {
      headers,
      data: { title, description, scope: "both" },
    });
  },
);

Given(
  "the motto screensaver is enabled with timeout {int}",
  async ({ request }, seconds: number) => {
    await request.put("/api/user/screensaver-enabled", {
      headers,
      data: { screensaver_enabled: 1 },
    });
    await request.put("/api/user/screensaver-timeout", {
      headers,
      data: { screensaver_timeout_seconds: seconds },
    });
  },
);

When("I open the settings menu", async ({ page }) => {
  await page.locator(".settings-btn").click();
  await page.waitForLoadState("networkidle");
});

When("I type {string} in the motto title field", async ({ page }, text: string) => {
  await page.locator(".motto-add-title").fill(text);
});

When("I type {string} in the motto description field", async ({ page }, text: string) => {
  await page.locator(".motto-add-description").fill(text);
});

When("I click the motto add button", async ({ page }) => {
  await page.locator(".motto-add-btn").click();
  await page.waitForLoadState("networkidle");
});

When("I type {string} in the motto search field", async ({ page }, text: string) => {
  await page.locator("#mottos-filter-search").fill(text);
  await page.waitForLoadState("networkidle");
});

When("I click delete on motto {string}", async ({ page }, title: string) => {
  const row = page.locator(".motto-row").filter({ hasText: title });
  await row.locator(".motto-delete-btn").click();
});

When("I confirm motto deletion", async ({ page }) => {
  await page.locator(".motto-delete-confirm-btn").click();
  await page.waitForLoadState("networkidle");
});

When("I check the screensaver opt-in checkbox", async ({ page }) => {
  // Reagent's `:checked` is driven by app-state; use click() so React's
  // onChange runs (Playwright's check() can short-circuit when the DOM
  // state already matches its expectation).
  const box = page.locator(".motto-screensaver-toggle input[type='checkbox']");
  if (!(await box.isChecked())) await box.click();
  await page.waitForLoadState("networkidle");
});

When("I trigger the motto screensaver", async ({ page }) => {
  // Wait long enough for the 5-second timer to fire.
  await page.waitForSelector(".motto-screensaver-overlay", { timeout: 15000 });
});

When("I click on the motto overlay", async ({ page }) => {
  await page.locator(".motto-screensaver-overlay").click();
});

Then("I should see the mottos page", async ({ page }) => {
  await expect(page.locator(".mottos-page")).toBeVisible();
});

Then("I should see {string} in the motto list", async ({ page }, text: string) => {
  await expect(page.locator(".motto-list")).toContainText(text);
});

Then("I should not see {string} in the motto list", async ({ page }, text: string) => {
  // Look at the row container if it's there; otherwise fall back to the page
  // scope (an empty list has no .motto-list at all).
  const page_ = page.locator(".mottos-page");
  await expect(page_.locator(".motto-row").filter({ hasText: text })).toHaveCount(0);
});

Then("the screensaver opt-in checkbox should be checked", async ({ page }) => {
  await expect(page.locator(".motto-screensaver-toggle input[type='checkbox']")).toBeChecked();
});

Then(
  "I should see the motto overlay containing {string}",
  async ({ page }, text: string) => {
    const overlay = page.locator(".motto-screensaver-overlay");
    await expect(overlay).toBeVisible();
    await expect(overlay).toContainText(text);
  },
);

Then("the motto overlay should be gone", async ({ page }) => {
  await expect(page.locator(".motto-screensaver-overlay")).toHaveCount(0);
});
