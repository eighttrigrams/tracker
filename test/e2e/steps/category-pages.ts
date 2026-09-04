import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a person {string} exists", async ({ request }, name: string) => {
  await request.post("/api/people", { headers, data: { name } });
});

Given("a place {string} exists", async ({ request }, name: string) => {
  await request.post("/api/places", { headers, data: { name } });
});

Given("a project {string} exists", async ({ request }, name: string) => {
  await request.post("/api/projects", { headers, data: { name } });
});

Given("a goal {string} exists", async ({ request }, name: string) => {
  await request.post("/api/goals", { headers, data: { name } });
});

When("I click the {string} category tab", async ({ page }, name: string) => {
  const tab = page.locator(".tabs").getByRole("button", { name });
  await tab.click();
  await expect(tab).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

When("I add a category entry called {string}", async ({ page }, name: string) => {
  const input = page.locator(".combined-search-add-form input");
  await setFieldValue(input, name);
  await page.locator(".combined-search-add-form button").first().click();
  await expect(page.locator(".category-cards-grid")).toContainText(name);
  await expect(input).toHaveValue("");
});

When("I expand the card {string}", async ({ page }, name: string) => {
  await page.locator(".category-card-header").filter({ hasText: name }).click();
  await page.waitForLoadState("networkidle");
});

When("I click the edit pencil button", async ({ page }) => {
  await page.locator(".category-card.expanded .edit-icon.description-placeholder").click();
  await page.waitForLoadState("networkidle");
});

Then("the left nav should show category tabs", async ({ page }) => {
  const tabs = page.locator(".tabs");
  await expect(tabs).toContainText("People");
  await expect(tabs).toContainText("Places");
  await expect(tabs).toContainText("Projects");
  await expect(tabs).toContainText("Goals");
});

Then("the left nav should show the normal tabs", async ({ page }) => {
  const tabs = page.locator(".tabs");
  await expect(tabs).toContainText("Tasks");
});

Then("the {string} button should be active", async ({ page }, name: string) => {
  const btn = page.getByRole("button", { name });
  await expect(btn).toHaveClass(/active/);
});

Then("the {string} tab should be active", async ({ page }, name: string) => {
  const tab = page.locator(".top-bar .tabs").getByRole("button", { name, exact: true });
  await expect(tab).toHaveClass(/active/);
});

Then("I should see the categories back button", async ({ page }) => {
  await expect(page.locator(".top-bar .nav-back-btn")).toBeVisible();
});

Then("I should not see the categories back button", async ({ page }) => {
  await expect(page.locator(".top-bar .nav-back-btn")).toHaveCount(0);
});

Then("I should see {string} in the category cards", async ({ page }, name: string) => {
  await expect(page.locator(".category-cards-grid")).toContainText(name);
});

Then("the card {string} should be expanded", async ({ page }, name: string) => {
  const card = page.locator(".category-card").filter({ hasText: name });
  await expect(card).toHaveClass(/expanded/);
});

Then("I should see the edit pencil button", async ({ page }) => {
  await expect(
    page.locator(".category-card.expanded .edit-icon.description-placeholder")
  ).toBeVisible();
});

Then("the category edit modal should be open with {string}", async ({ page }, name: string) => {
  const modal = page.locator(".modal, [role='dialog']").first();
  await expect(modal).toBeVisible();
  await expect(modal.locator("input").first()).toHaveValue(name);
});

const categoryAddBox = ".combined-search-add-form input";

When("I type {string} in the category add box", async ({ page }, text: string) => {
  await setFieldValue(page.locator(categoryAddBox), text);
  // This bar's add reads the search value live out of app-state rather than
  // closing over a render-time copy, so there is no stale-handler race to wait
  // out here. The clear-x is still waited on as proof the typing landed at all.
  await expect(page.locator(".combined-search-add-form .clear-search")).toBeVisible();
});

// Both variants, because which one is the combo depends on the user's keymap —
// Digit9 with it, KeyS without. See et.tr.ui.keys/save-combo?.
When("I press the save combo in the category add box", async ({ page }) => {
  await page.locator(categoryAddBox).focus();
  await page.keyboard.press("Meta+KeyS");
  await page.keyboard.press("Meta+Digit9");
  await page.waitForLoadState("networkidle");
});

Then("the category add box should be empty", async ({ page }) => {
  await expect(page.locator(categoryAddBox)).toHaveValue("");
});

Then("the card {string} should be collapsed", async ({ page }, name: string) => {
  const card = page.locator(".category-card").filter({ hasText: name });
  await expect(card).not.toHaveClass(/expanded/);
});

// The Escape behaviour is defined for the cursor being *outside* any field, so
// the scenario has to put it there. A bare blur is the honest way to reach that
// state — clicking some other control would focus that control instead.
When("I move focus out of the category fields", async ({ page }) => {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.tagName ?? null))
    .toBe("BODY");
});

Then("the category add box should have focus", async ({ page }) => {
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.id ?? null))
    .toMatch(/-filter-search$/);
});

// Pins the id itself, which is the point of naming the boxes after the Group
// rather than after the tab: the tab is :cat-places, the box is places-.
Then("the focused element should be {string}", async ({ page }, id: string) => {
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.id ?? null))
    .toBe(id);
});
