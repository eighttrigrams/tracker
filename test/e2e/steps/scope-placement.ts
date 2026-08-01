import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a tracked YouTube channel {string} exists", async ({ request }, channelId: string) => {
  const resp = await request.post("/api/sources/youtube/channels", {
    headers,
    data: { channel_id: channelId },
  });
  expect(resp.ok()).toBeTruthy();
});

// Sources is a mode on the Inbox page that survives a tab switch, so this only
// clicks the toggle when the channel list is not already showing.
When("I open the sources page", async ({ page }) => {
  const tab = page.locator(".top-bar .tabs").getByRole("button", { name: "Inbox" });
  await tab.click();
  await expect(tab).toHaveClass(/active/);
  const rows = page.locator(".sources-channel-row").first();
  if (!(await rows.isVisible())) {
    await page
      .locator(".series-mode-toggle")
      .getByRole("button", { name: "Sources", exact: true })
      .click();
  }
  await expect(rows).toBeVisible();
});

Then("the sources scope switcher reads {string}", async ({ page }, order: string) => {
  const options = page.locator(".sources-channel-scope .toggle-option");
  await expect(options).toHaveText(order.split(", "));
});

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
