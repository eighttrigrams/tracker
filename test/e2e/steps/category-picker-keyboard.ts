import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue } from "./helpers";

const { When, Then } = createBdd();

const panel = ".category-selector-panel";
const cardItems = ".category-selector-item";
const sidebarItems = ".sidebar .filter-section.places .filter-item";

const cardSearch = `${panel} .category-selector-search`;
const sidebarSearch = "#tasks-filter-places";

// The picker's own search box, opened off the card's "+ Place" button.
When(
  "I search {string} in the place picker on task {string}",
  async ({ page }, term: string, task: string) => {
    const card = page.locator(".items li").filter({ hasText: task }).first();
    await card.getByRole("button", { name: "+ Place" }).click();
    await setFieldValue(page.locator(cardSearch), term);
  },
);

When("I open the {string} filter picker", async ({ page }, group: string) => {
  await page.locator(`.sidebar .filter-section.${group} .collapse-toggle`).click();
  await expect(page.locator(`#tasks-filter-${group}`)).toBeVisible();
  await page.locator(`#tasks-filter-${group}`).focus();
});

// More than one is the whole point of the Enter change: this is exactly the
// case the card's picker used to refuse to act on.
Then("the place picker lists more than one place", async ({ page }) => {
  await expect(page.locator(cardItems).nth(1)).toBeVisible();
});

Then("the place picker lists nothing", async ({ page }) => {
  await expect(page.locator(cardItems)).toHaveCount(0);
  await expect(page.locator(".category-selector-empty")).toBeVisible();
});

When("I move the place picker cursor with {string}", async ({ page }, key: string) => {
  await page.locator(cardSearch).press(key);
});

When("I move the sidebar picker cursor with {string}", async ({ page }, key: string) => {
  await page.locator(sidebarSearch).press(key);
});

// One ring, on the entry the scenario names — and nowhere else, so a cursor that
// moved without the old position clearing would fail rather than pass twice.
async function expectCursorOn(page: any, items: string, n: number) {
  await expect(page.locator(items).nth(n - 1)).toHaveClass(/preselected/);
  await expect(page.locator(`${items}.preselected`)).toHaveCount(1);
}

Then("the place picker cursor is on entry {int}", async ({ page }, n: number) => {
  await expectCursorOn(page, cardItems, n);
});

Then("the sidebar picker cursor is on entry {int}", async ({ page }, n: number) => {
  await expectCursorOn(page, sidebarItems, n);
});

When("I press Enter in the place picker", async ({ page }) => {
  // Stash the entries as the panel actually lists them, so the assertion below
  // can say "entry 2" without naming a place. The order is by modified_at, and
  // two categories seeded within the same second can come back either way
  // round — a test that named the expected one would be a coin flip on the
  // clock rather than a check of this behaviour.
  await page.evaluate((sel: string) => {
    (window as any).__pickerEntries = [...document.querySelectorAll(sel)].map(
      (el) => (el as HTMLElement).textContent,
    );
  }, cardItems);
  await page.locator(cardSearch).press("Enter");
  await page.waitForLoadState("networkidle");
});

Then(
  "the task {string} gets picker entry {int}",
  async ({ page }, task: string, n: number) => {
    const entries: (string | null)[] = await page.evaluate(() => (window as any).__pickerEntries);
    const expected = entries[n - 1];
    expect(expected).toBeTruthy();
    const card = page.locator(".items li").filter({ hasText: task }).first();
    await expect(card.locator(".tag").filter({ hasText: expected! }).first()).toBeVisible();
  },
);

Then("the place picker is still open", async ({ page }) => {
  await expect(page.locator(panel)).toBeVisible();
});
