import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// Same first-input-is-the-title locator the optimistic-concurrency steps use.
const modalTitle = ".edit-item-modal .item-edit-form input";

async function titlesOfUser(request: any, userId: string): Promise<string[]> {
  const tasks = await (
    await request.get("/api/tasks", { headers: { ...headers, "X-User-Id": userId } })
  ).json();
  return tasks.map((t: any) => t.title);
}

async function pressAndStay(page: any, key: string) {
  // A flash still on screen from an earlier save would satisfy the assertion
  // below, so wait it out first — the flash asserted here must be this save's.
  await expect(page.locator("#save-flash")).toHaveCount(0);
  await page.keyboard.press(key);
  // The checkmark only fires once every write of this save has landed, so it is
  // also the signal that makes a following API read-back race-free. It has to be
  // asserted here, before the wait below: it disappears on its own after ~1.5s.
  await expect(page.locator("#save-flash")).toBeVisible();
  // Lets the follow-up read of the row (which re-arms the next save's
  // optimistic-concurrency guard) finish before the scenario saves again.
  await page.waitForLoadState("networkidle");
}

When("I save without closing", async ({ page }) => {
  await pressAndStay(page, "Meta+Shift+S");
});

When(
  "I change the modal title to {string} and save without closing",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await pressAndStay(page, "Meta+Shift+S");
  },
);

When(
  "I change the modal title to {string} and save without closing using the custom keymap",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await pressAndStay(page, "Meta+Shift+Digit9");
  },
);

// A refused save flashes no checkmark, so this step has nothing of its own to
// synchronise on — the banner assertion that follows it in the feature is what
// waits for the round trip.
When(
  "I change the modal title to {string} and save without closing, hitting a conflict",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await page.keyboard.press("Meta+Shift+S");
  },
);

When(
  "I change the modal title to {string} and save with the keyboard",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await page.keyboard.press("Meta+S");
    await page.waitForLoadState("networkidle");
  },
);

// modified_at has 1-second resolution (see the optimistic-concurrency steps), so
// a save inside the same second as the modal's open-time read writes back a row
// that is textually unchanged — neither a stale guard nor a re-seed of the form
// would show. Waiting between opening the modal and saving is what makes the
// scenarios that pin those two fail when they are broken.
When("a full second passes", async () => {
  await new Promise((resolve) => setTimeout(resolve, 1100));
});

When("I press the save-and-stay shortcut", async ({ page }) => {
  await page.keyboard.press("Meta+Shift+S");
});

When("I press Escape in the modal", async ({ page }) => {
  await page.keyboard.press("Escape");
});

Given(
  "a user {string} with the custom keymap exists",
  async ({ request }, username: string) => {
    const user = await (
      await request.post("/api/users", { headers, data: { username, password: "testpass" } })
    ).json();
    // The vim-keys setting is per user row, and the admin the e2e app runs as has
    // none — hence a real user, switched to in the UI, for the Digit9 variant.
    const resp = await request.put("/api/user/vim-keys", {
      headers: { ...headers, "X-User-Id": String(user.id) },
      data: { vim_keys: 1 },
    });
    expect(resp.ok()).toBeTruthy();
  },
);

When("I switch to the user {string}", async ({ page }, username: string) => {
  await page.locator(".switch-user-btn").click();
  await page.locator(".user-switcher-item").filter({ hasText: username }).click();
  await expect(page.locator(".switch-user-btn .current-user")).toHaveText(username);
  await page.waitForLoadState("networkidle");
});

Then(
  "the {string} tab in the modal should still be active",
  async ({ page }, tab: string) => {
    await expect(page.locator(".edit-modal-tabs button.active")).toHaveText(tab);
  },
);

Then("the edit modal should still be open", async ({ page }) => {
  await expect(page.locator(".edit-item-modal")).toBeVisible();
  await expect(page.locator(".modal-overlay")).toHaveCount(1);
});

Then("no modal should be open", async ({ page }) => {
  // Closing the edit modal pushes "/", while the unsaved-changes dialog leaves
  // the item URL up: waiting for the URL first keeps the count below from
  // passing against a dialog that has not rendered yet.
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator(".modal-overlay")).toHaveCount(0);
});

Then("the save checkmark should disappear on its own", async ({ page }) => {
  await expect(page.locator("#save-flash")).toHaveCount(0);
});

Then("the save checkmark should not be visible", async ({ page }) => {
  await expect(page.locator("#save-flash")).toHaveCount(0);
});

Then("the task {string} should be stored", async ({ request }, title: string) => {
  expect(await titlesOfUser(request, "null")).toContain(title);
});

Then(
  "the task {string} should be stored for user {string}",
  async ({ request }, title: string, username: string) => {
    const users = await (await request.get("/api/users", { headers })).json();
    const user = users.find((u: any) => u.username === username);
    if (!user) throw new Error(`no user "${username}"`);
    expect(await titlesOfUser(request, String(user.id))).toContain(title);
  },
);
