import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// The setting lives on the user row, so it is flipped through the Settings
// checkbox that writes it — the checkbox only shows the new value once the PUT
// came back, which is what makes the assertion here a round-trip check.
async function setInvertedPlacement(page: any, on: boolean) {
  await page.locator(".settings-btn").click();
  const box = page
    .locator(".settings-item label")
    .filter({ hasText: "Inverted scope placement" })
    .locator("input");
  await box.click();
  if (on) {
    await expect(box).toBeChecked();
  } else {
    await expect(box).not.toBeChecked();
  }
  await page.locator(".settings-btn").click();
  await page.waitForLoadState("networkidle");
}

When("I turn on inverted scope placement", async ({ page }) => {
  await setInvertedPlacement(page, true);
});

When("I turn off inverted scope placement", async ({ page }) => {
  await setInvertedPlacement(page, false);
});

// Expanding is idempotent so a scenario can check the footer again after a tab
// switch (which keeps the card open) or a reload (which does not).
When("I expand the task card {string}", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title }).first();
  if (!(await card.evaluate((el: Element) => el.classList.contains("expanded")))) {
    await card.locator(".item-header").click();
  }
  await expect(card).toHaveClass(/expanded/);
});

Then("the navbar scope switcher reads {string}", async ({ page }, order: string) => {
  const glyphs = page.locator(".work-private-toggle .scope-glyph");
  await expect(glyphs).toHaveText(order.split(", "));
});

Then(
  "the scope switcher on task {string} reads {string}",
  async ({ page }, title: string, order: string) => {
    const options = page
      .locator(".items li")
      .filter({ hasText: title })
      .first()
      .locator(".task-scope-selector .toggle-option");
    await expect(options).toHaveText(order.split(", "));
  },
);

When(
  "I click {string} on the scope switcher of task {string}",
  async ({ page }, scope: string, title: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: title })
      .first()
      .locator(".task-scope-selector .toggle-option")
      .filter({ hasText: scope })
      .click();
    await page.waitForLoadState("networkidle");
  },
);

Then(
  "the scope of task {string} is {string}",
  async ({ page, request }, title: string, scope: string) => {
    // The active class diverges from the pre-click state, so this waits for the
    // re-render before the row is read back from the API.
    await expect(
      page
        .locator(".items li")
        .filter({ hasText: title })
        .first()
        .locator(".task-scope-selector .toggle-option.active"),
    ).toHaveText(scope);
    const tasks = await (await request.get("/api/tasks", { headers })).json();
    expect(tasks.find((t: any) => t.title === title).scope).toBe(scope);
  },
);
