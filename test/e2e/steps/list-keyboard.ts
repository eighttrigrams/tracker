import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// The point of the Escape behaviour is that the cursor is *not* in the search
// box, so the scenarios have to get it out of there first. Clicking the page
// heading area is the plainest way a user would end up there — it focuses
// nothing, which is exactly the state under test.
When("I click away from the search box", async ({ page }) => {
  await page.locator(".sort-toggle").click({ position: { x: 2, y: 2 } });
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.tagName ?? null))
    .toBe("BODY");
});

When("I focus the issues search field", async ({ page }) => {
  await page.locator("#issues-filter-search").focus();
});

When("I focus the tasks search field", async ({ page }) => {
  await page.locator("#tasks-filter-search").focus();
});

// Digit9 rather than KeyS because the e2e user has no custom keymap, so the
// combo is Cmd+S... which is also the browser's Save Page. Both are sent: the
// one that matches the user's scheme acts, the other falls through to nothing.
// See et.tr.ui.keys/save-combo?.
When("I press the save combo", async ({ page }) => {
  // These bars close over the input value at *render* time, so the keypress has
  // to land after the render the typing caused — otherwise the handler still
  // holds an empty bar and does nothing, and the scenario fails with the text
  // still sitting in the box.
  //
  // Waiting on the DOM input's own value is not enough: Playwright writes that
  // directly, so it is already set before reagent has re-rendered. The clear-x
  // is the honest signal — it is rendered `(when (seq input-value))` from the
  // very binding the handler closes over, so once it is on screen the handler
  // is current by construction.
  await expect(page.locator(".combined-search-add-form .clear-search")).toBeVisible();
  await page.waitForLoadState("networkidle");
  await page.keyboard.press("Meta+KeyS");
  await page.keyboard.press("Meta+Digit9");
  await page.waitForLoadState("networkidle");
});

Then("the issue {string} should be expanded", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card.locator(".item-actions-left")).toBeVisible();
});

Then("the issue {string} should be collapsed", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card.locator(".item-actions-left")).toHaveCount(0);
});

Then("the issues search field should have focus", async ({ page }) => {
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.id ?? null))
    .toBe("issues-filter-search");
});

Then("the issues search field should be empty", async ({ page }) => {
  await expect(page.locator("#issues-filter-search")).toHaveValue("");
});

// The bar clearing is the add's own completion signal — it happens in the
// success callback — so asserting it first puts the list assertion after the
// round trip rather than racing the refetch it kicks off.
Then("the tasks search field should be empty", async ({ page }) => {
  await expect(page.locator("#tasks-filter-search")).toHaveValue("");
  await page.waitForLoadState("networkidle");
});
