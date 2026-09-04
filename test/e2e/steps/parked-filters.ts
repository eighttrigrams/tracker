import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// "I press Option+Escape" lives in negative-category-filter.ts, and the seeding,
// the filter selection and the "as selected" assertion are all reused as they
// are — the only steps here are the ones about the box itself.

// Option+Escape only parks while every picker is collapsed: an open one takes the
// key for itself and clears just its own Group (see filter-section's Escape
// handler). `selectSidebarFilter` leaves the picker it used open, so the
// scenarios collapse it first, which is what a person does with Escape anyway.
When("I collapse the {string} filter group", async ({ page }, group: string) => {
  const sec = page.locator(`.sidebar .filter-section.${group}`);
  if ((await sec.locator(".filter-item").count()) > 0) {
    await sec.locator(".collapse-toggle").click();
  }
  await expect(sec.locator(".filter-item")).toHaveCount(0);
});

When("I click the parked filter box", async ({ page }) => {
  await page.locator(".sidebar .parked-filters").click();
  await page.waitForLoadState("networkidle");
});

// The pills are `.tag`s, the element the card badges use, and carry the Group
// colour as their second class — so asserting the class is asserting that the
// box says which Group the pill came from, which is the whole reason it has no
// headings.
Then("the parked filter box should show {string}", async ({ page }, name: string) => {
  const pill = page.locator(".sidebar .parked-filters .tag").filter({ hasText: name });
  await expect(pill.first()).toBeVisible();
  await expect(pill.first()).toHaveClass(/tag (person|place|workstream|project|goal|asset)/);
});

Then("there should be no parked filter box", async ({ page }) => {
  await expect(page.locator(".sidebar .parked-filters")).toHaveCount(0);
});
