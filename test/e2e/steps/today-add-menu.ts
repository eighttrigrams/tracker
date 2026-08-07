import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { offsetDateStr, setFieldValue } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// sort=summary is the one listing with no date clause on it, so an undated meet
// shows up here. The default listing hides anything without a start_date, which
// would turn "the new meet was left undated" — a failure these scenarios are
// written to catch — into "no such meet" and read as a broken test.
const MEETS_URL = "/api/meets?sort=summary";
const TASKS_URL = "/api/tasks?sort=recent";

// Every PUT /api/meets/:id/start-date the add fired, as [id, date] pairs.
// db.meet/add-meet stamps a new row with today's date, so a meet created from
// the Today page is dated whether or not this feature dated it; the request is
// the only evidence that distinguishes the two, and on offset 0 it is the only
// thing that can fail. Armed at the add step, so a count is always this
// scenario's.
let startDateRequests: Array<{ id: string; date: string }> = [];

function recordStartDateRequests(page: any) {
  startDateRequests = [];
  page.on("request", (req: any) => {
    const m = /\/api\/meets\/(\d+)\/start-date$/.exec(new URL(req.url()).pathname);
    if (req.method() === "PUT" && m) {
      let date = "";
      try {
        date = JSON.parse(req.postData() ?? "{}")["start-date"] ?? "";
      } catch {
        date = req.postData() ?? "";
      }
      startDateRequests.push({ id: m[1], date });
    }
  });
}

// The item is created by a POST the submit fired, so it can land a beat after
// the step returns. Poll for its existence; never for the field under test.
async function fetchItem(request: any, url: string, title: string) {
  let item: any;
  await expect(async () => {
    const items = await (await request.get(url, { headers })).json();
    item = items.find((i: any) => i.title === title);
    expect(item, `no item titled "${title}" at ${url}`).toBeTruthy();
  }).toPass({ timeout: 10000 });
  return item;
}

const meet = (request: any, title: string) => fetchItem(request, MEETS_URL, title);
const task = (request: any, title: string) => fetchItem(request, TASKS_URL, title);

const addButton = (page: any) => page.locator(".today-add-btn");
const menu = (page: any) => page.locator(".today-add-menu");

// Reagent re-renders a tick or two after the state changes, so a click issued
// in the same breath as the move onto the option lands on a menu that is
// already logically closed and works anyway. That is fast enough to hide the
// whole bug this dwell is here for: with the hover handlers on the button
// instead of on the wrapper, leaving the button closes the menu, and a human —
// who rests on an option before pressing it — cannot click it at all. So dwell
// first, then assert the menu survived, then click.
const POINTER_DWELL_MS = 250;

async function hoverOption(page: any, kind: "task" | "meet") {
  await addButton(page).hover();
  const option = page.locator(`.today-add-option.add-${kind}`);
  await option.hover();
  await page.waitForTimeout(POINTER_DWELL_MS);
  return option;
}

// Hover is the only way in — there is no click to open it — so this is how both
// kinds get added.
async function addViaMenu(page: any, kind: "task" | "meet", title: string) {
  const option = await hoverOption(page, kind);
  await expect(menu(page), "the menu closed as the pointer moved onto it").toBeVisible();
  await option.click();
  const input = page.locator(".today-add-input");
  await expect(input).toBeVisible();
  await setFieldValue(input, title);
  await input.press("Enter");
  await page.waitForLoadState("networkidle");
}

Given(
  "a meet {string} starting at offset {int} exists",
  async ({ request }, title: string, offset: number) => {
    const created = await (await request.post("/api/meets", { headers, data: { title } })).json();
    const res = await request.put(`/api/meets/${created.id}/start-date`, {
      headers,
      data: { "start-date": offsetDateStr(offset) },
    });
    expect(res.ok(), `could not date the seeded meet "${title}"`).toBeTruthy();
  },
);

When("I hover the today add button", async ({ page }) => {
  await addButton(page).hover();
});

// Somewhere outside the wrapper, so mouse-leave fires. The heading is a safe
// target: it is on every day and it opens nothing of its own.
When("I move the pointer off the today add button", async ({ page }) => {
  await page.locator(".today-section.today h3").hover();
  await page.waitForTimeout(POINTER_DWELL_MS);
});

When("I move the pointer onto the Meet option", async ({ page }) => {
  await hoverOption(page, "meet");
});

When("I select the day at offset {int}", async ({ page }, offset: number) => {
  const button = page.locator(".day-selector button").nth(offset);
  await button.click();
  // The class, not the network: the refetch settles before reagent re-renders,
  // so networkidle alone would let the next step read the previous day's list.
  await expect(button).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

When("I add a meet {string} via the today add menu", async ({ page }, title: string) => {
  recordStartDateRequests(page);
  await addViaMenu(page, "meet", title);
});

When("I add a task {string} via the today add menu", async ({ page }, title: string) => {
  await addViaMenu(page, "task", title);
});

Then("the today add menu offers a Task and a Meet", async ({ page }) => {
  await expect(menu(page)).toBeVisible();
  await expect(page.locator(".today-add-option.add-task")).toBeVisible();
  await expect(page.locator(".today-add-option.add-meet")).toBeVisible();
});

Then("the today add menu is not shown", async ({ page }) => {
  await expect(menu(page)).toHaveCount(0);
});

Then("the today add button is shown", async ({ page }) => {
  await expect(addButton(page)).toBeVisible();
});

Then("the today add button is not shown", async ({ page }) => {
  await expect(addButton(page)).toHaveCount(0);
});

Then(
  "the start date was set once, to the day at offset {int}",
  async ({}, offset: number) => {
    await expect(() => {
      expect(
        startDateRequests,
        `start-date requests seen: ${JSON.stringify(startDateRequests)}`,
      ).toHaveLength(1);
      expect(startDateRequests[0].date).toBe(offsetDateStr(offset));
    }).toPass({ timeout: 10000 });
  },
);

Then(
  "the start date was set on the meet {string} itself",
  async ({ request }, title: string) => {
    const m = await meet(request, title);
    await expect(() => {
      expect(
        startDateRequests.map((r) => r.id),
        `start-date requests seen: ${JSON.stringify(startDateRequests)}`,
      ).toEqual([String(m.id)]);
    }).toPass({ timeout: 10000 });
  },
);

Then(
  "the meet {string} starts on the day at offset {int}",
  async ({ request }, title: string, offset: number) => {
    await expect(async () => {
      const m = await meet(request, title);
      expect(m.start_date, `"${title}" is dated ${m.start_date}`).toBe(offsetDateStr(offset));
    }).toPass({ timeout: 10000 });
  },
);

Then("the task {string} is flagged for today", async ({ request }, title: string) => {
  await expect(async () => {
    const t = await task(request, title);
    expect(t.today, `"${title}" carries today=${t.today}`).toBe(1);
  }).toPass({ timeout: 10000 });
});

Then("the task {string} is not flagged for today", async ({ request }, title: string) => {
  const t = await task(request, title);
  expect(t.today, `"${title}" carries today=${t.today}`).not.toBe(1);
});

Then(
  "the task {string} is lined up for the day at offset {int}",
  async ({ request }, title: string, offset: number) => {
    await expect(async () => {
      const t = await task(request, title);
      expect(t.lined_up_for, `"${title}" is lined up for ${t.lined_up_for}`).toBe(
        offsetDateStr(offset),
      );
    }).toPass({ timeout: 10000 });
  },
);

Then("the task {string} is lined up for no day", async ({ request }, title: string) => {
  const t = await task(request, title);
  expect(t.lined_up_for, `"${title}" is lined up for ${t.lined_up_for}`).toBeFalsy();
});
