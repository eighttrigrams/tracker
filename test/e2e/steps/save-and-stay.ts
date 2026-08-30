import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue, today } from "./helpers";

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
    await pressAndStay(page, "Meta+Digit9");
  },
);

// Cmd is held down across both keys, because that is the gesture being pinned:
// Escape arms the chord and Digit9 completes it without the modifier ever coming
// up. Two separate Meta+... presses would also satisfy the implementation — it
// only reads metaKey per keydown — but they are not what the scheme asks of the
// hand, so the test presses it the way a user does.
async function pressSaveAndClose(page: any) {
  await page.keyboard.down("Meta");
  await page.keyboard.press("Escape");
  await page.keyboard.press("Digit9");
  await page.keyboard.up("Meta");
}

When(
  "I change the modal title to {string} and save and close using the custom keymap",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await pressSaveAndClose(page);
    await page.waitForLoadState("networkidle");
  },
);

// The combo that used to save and stay. It is bound to nothing now, and this is
// what says so: the modal has to be left untouched by it, not merely saved by
// some other branch that happens to ignore shift.
When(
  "I change the modal title to {string} and press the retired save-and-stay combo",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await page.keyboard.press("Meta+Shift+Digit9");
    await page.waitForLoadState("networkidle");
  },
);

// Arming and then walking away: the chord must not lie in wait and turn the next
// plain save into a close.
When("I press the save-and-exit prefix and then type in the title", async ({ page }) => {
  await page.keyboard.down("Meta");
  await page.keyboard.press("Escape");
  await page.keyboard.up("Meta");
  await page.locator(modalTitle).first().pressSequentially("x");
});

// A save that is refused, or whose date write fails, flashes no checkmark — so
// these two steps have nothing of their own to synchronise on: the banner
// assertion that follows them in the feature is what waits for the round trip.
When(
  "I change the modal title to {string} and save without closing, hitting a conflict",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await page.keyboard.press("Meta+Shift+S");
  },
);

When(
  "I set the modal due date to today and save without closing, hitting a failed write",
  async ({ page }) => {
    await setFieldValue(page.locator(".edit-item-modal .date-picker-input"), today());
    await page.keyboard.press("Meta+Shift+S");
  },
);

// The content PUT of that save lands and bumps modified_at while this one fails,
// which is what used to doom the save after it: the modal kept the pre-save
// modified_at, so its next save was bound to conflict.
When("the next due-date write fails once", async ({ page }) => {
  await page.route(
    "**/api/tasks/*/due-date",
    (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ error: "Injected due-date failure" }),
      }),
    { times: 1 },
  );
});

// A stay save's writes can land after the modal that issued them is gone, and
// both of the things the latch does then are app-wide: it clears :error and
// flashes the checkmark. Holding the content PUT open is the only way a test can
// act inside that window — nothing else happens between the keypress and the
// response. The scenario raises a banner of its own in there, and the pin is
// that the landing save leaves it standing.
// Why the scenario searches the row up before asserting: a clear of :error renders
// a frame after the response that triggered it, so a banner assertion made right
// after the response still sees the old DOM and would pass against the unguarded
// code. Waiting for the searched row to appear is a render of the app's own, and
// it flushes any pending clear ahead of the banner assertion that follows.
let releaseHeldWrite: (() => void) | null = null;

When("the next content write is held", async ({ page }) => {
  let holding = false;
  await page.route("**/api/tasks/*", async (route) => {
    // Only the first PUT is held: the refresh GET the same save issues afterwards
    // has to reach the server untouched.
    if (holding || route.request().method() !== "PUT") return route.fallback();
    holding = true;
    await new Promise<void>((resolve) => (releaseHeldWrite = resolve));
    await route.continue();
  });
});

When("the held write lands", async ({ page }) => {
  if (!releaseHeldWrite) throw new Error("no held write to release");
  const itemUrl = /\/api\/tasks\/\d+$/;
  const write = page.waitForResponse((r) => r.request().method() === "PUT" && itemUrl.test(r.url()));
  // The refresh read the latch issues once the write has settled, i.e. in the same
  // tick as — and just after — the two actions this pins. Waiting for it is what
  // puts the assertions that follow behind them. waitForLoadState("networkidle")
  // does not: a request parked in a route handler counts as no traffic at all, so
  // it returns before the write has even been let go.
  const refresh = page.waitForResponse((r) => r.request().method() === "GET" && itemUrl.test(r.url()));
  releaseHeldWrite();
  releaseHeldWrite = null;
  await write;
  await refresh;
});

When(
  "I change the modal title to {string} and press the save-and-stay shortcut",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    // No checkmark to wait for here: this save's write is held, and by the time it
    // lands the modal is gone, which is exactly when it must flash nothing.
    await page.keyboard.press("Meta+Shift+S");
  },
);

// Escape with the typed title still unsaved goes through the unsaved-changes
// prompt; discarding is what runs clear-editing-modal, i.e. what makes the modal
// really gone rather than merely covered.
When("I discard the unsaved changes", async ({ page }) => {
  await page.locator(".modal-footer button.confirm-delete").click();
});

// Leaves the form diverged from the saved state without writing anything, which
// is the precondition for the unsaved-changes prompt appearing on Escape.
When(
  "I change the modal title to {string} without saving",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
  },
);

// The unsaved-changes prompt. Its two choices are ordered discard-then-keep in
// the DOM, which is also left-to-right on screen, and the selected one is the
// focused one.
const unsavedFooter = ".unsaved-changes-footer";

Then("the unsaved-changes prompt should be open", async ({ page }) => {
  await expect(page.locator(unsavedFooter)).toBeVisible();
});

Then(
  "the prompt's choices should read {string} then {string}",
  async ({ page }, left: string, right: string) => {
    await expect(page.locator(`${unsavedFooter} button`)).toHaveText([left, right]);
  },
);

// Reads the selection off the document rather than off a class, because focus
// *is* the selection here — that is what makes Enter take it.
async function selectedChoice(page: any): Promise<string | null> {
  return page.evaluate((sel: string) => {
    const el = document.activeElement;
    if (!el || !el.closest(sel)) return null;
    return el.textContent;
  }, unsavedFooter);
}

Then("the selected choice should be {string}", async ({ page }, label: string) => {
  await expect.poll(() => selectedChoice(page)).toBe(label);
});

When("I press the select-left chord", async ({ page }) => {
  await page.keyboard.press("Meta+KeyJ");
});

When("I press the select-right chord", async ({ page }) => {
  await page.keyboard.press("Meta+KeyL");
});

When("I press Enter", async ({ page }) => {
  await page.keyboard.press("Enter");
  await page.waitForTimeout(300);
});

// cmd+9 in this prompt used to be wired to the discard. It must now do nothing
// at all: the combo means "save" everywhere else in the app.
When("I press the save combo in the prompt", async ({ page }) => {
  await page.keyboard.press("Meta+Digit9");
  await page.keyboard.press("Meta+KeyS");
  await page.waitForTimeout(300);
});

When("the next task creation fails once", async ({ page }) => {
  let failed = false;
  await page.route("**/api/tasks", async (route) => {
    if (failed || route.request().method() !== "POST") return route.fallback();
    failed = true;
    await route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ error: "Injected add failure" }),
    });
  });
});

// The banner this raises belongs to no modal, which is the point: the save still
// in flight has no business taking it down. A refused add has nothing of its own
// to synchronise on — the banner assertion that follows in the feature is the wait.
When("I try to add a task called {string}", async ({ page }, title: string) => {
  await setFieldValue(page.locator("#tasks-filter-search"), title);
  await page.locator(".combined-search-add-form button").first().click();
});

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

Then("the error banner should say {string}", async ({ page }, message: string) => {
  await expect(page.locator(".error")).toContainText(message);
});

// The date the failed write never stored: the save after it has to be the one
// that gets it there, which it only can once its guard has been re-armed.
Then("the task {string} should have its due date set", async ({ request }, title: string) => {
  const tasks = await (await request.get("/api/tasks", { headers })).json();
  const match = tasks.find((t: any) => t.title === title);
  if (!match) throw new Error(`no task titled "${title}"`);
  expect(match.due_date).toBe(today());
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

Then(
  "the task {string} should not be stored for user {string}",
  async ({ request }, title: string, username: string) => {
    const users = await (await request.get("/api/users", { headers })).json();
    const user = users.find((u: any) => u.username === username);
    if (!user) throw new Error(`no user "${username}"`);
    expect(await titlesOfUser(request, String(user.id))).not.toContain(title);
  },
);
