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

// Clicking the button that is already active toggles strict mode instead of
// selecting, so this only clicks when it has to; either way it leaves the
// switcher with that scope active, which is what the icon assertion reads
// against.
When("I select {string} in the navbar scope switcher", async ({ page }, scope: string) => {
  const glyphs: Record<string, string> = { private: "🏠", work: "👔" };
  const button = page
    .locator(".work-private-toggle .toggle-option")
    .filter({ hasText: glyphs[scope] });
  if (!(await button.evaluate((el: Element) => el.classList.contains("active")))) {
    await button.click();
  }
  await expect(button).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

Then("the active navbar scope button is {string}", async ({ page }, glyph: string) => {
  await expect(page.locator(".work-private-toggle .toggle-option.active")).toHaveText(glyph);
});

// The middle button's icon draws its two lobes at fixed x positions — cx=11 is
// the left one, cx=21 the right — and paints the one belonging to the active
// scope. Which scope that is comes from the order, so the painted lobe has to
// move to the other side when the placement is inverted. `svg > circle` keeps
// the two mask circles inside <defs> out; :not([fill="none"]) keeps the two
// outlines out.
Then("the vesica-piscis icon fills its {word} lobe", async ({ page }, side: string) => {
  const painted = page.locator('.work-private-toggle svg > circle:not([fill="none"])');
  await expect(painted).toHaveCount(1);
  await expect(painted).toHaveAttribute("cx", side === "left" ? "11" : "21");
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
