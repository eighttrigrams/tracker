import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { offsetDateStr } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const DAY_LIST = ".today-section.today .task-list";
const DAY_ITEMS = `${DAY_LIST} > div`;
const DAY_HEADING = ".today-section.today .today-subsection h4";

const dayItem = (page: any, title: string) => page.locator(DAY_ITEMS).filter({ hasText: title });

// HTML5 drag-and-drop is not driven by Playwright's mouse-based dragTo, so we
// dispatch the native drag events ourselves, sharing one DataTransfer across
// them. clientY decides where in the target the drop lands: the app reads the
// upper half as "before" and the lower half as "after". The source is found by
// its container plus title text (native querySelector has no :has-text).
async function fireDrag(
  page: any,
  selector: string,
  title: string,
  type: string,
  frac: number,
  optional = false,
) {
  await page.evaluate(
    ({ selector, title, type, frac, optional }: any) => {
      const el = [...document.querySelectorAll(selector)].find((e) =>
        (e as HTMLElement).innerText.includes(title),
      );
      // Looking the element up and dispatching on it has to happen in one turn:
      // a drop can take the card out of the list it was dragged from, and a
      // check from the test side would race that re-render.
      if (!el && optional) return;
      if (!el) throw new Error(`drag element not found: ${selector} / ${title}`);
      (window as any).__dt = (window as any).__dt || new DataTransfer();
      const rect = el.getBoundingClientRect();
      el.dispatchEvent(
        new DragEvent(type, {
          bubbles: true,
          cancelable: true,
          dataTransfer: (window as any).__dt,
          clientX: rect.left + rect.width / 2,
          clientY: rect.top + rect.height * frac,
        }),
      );
    },
    { selector, title, type, frac, optional },
  );
}

async function beginDrag(page: any, selector: string, title: string) {
  await page.evaluate(() => {
    (window as any).__dt = new DataTransfer();
  });
  await expect(page.locator(selector).filter({ hasText: title })).toBeVisible({ timeout: 5000 });
  await fireDrag(page, selector, title, "dragstart", 0.5);
}

// Waiting for a rendered mark of the active drag lets reagent re-render the drop
// targets so their handlers see the drag source — a drop dispatched before that
// render is a silent no-op. Cards that can be picked up mark themselves; the
// overdue card does not, but the day selector lights up for it.
async function startDrag(page: any, selector: string, title: string) {
  await beginDrag(page, selector, title);
  await expect(page.locator(".dragging").filter({ hasText: title })).toBeVisible({ timeout: 5000 });
}

async function startOverdueDrag(page: any, title: string) {
  await beginDrag(page, ".draggable-overdue-task", title);
  await expect(page.locator(".day-selector")).toHaveClass(/dragging/, { timeout: 5000 });
}

// The browser always follows a drag with dragend, which is what clears the drag
// state when a drop was refused; the synthetic sequence has to send it too. The
// card may be gone by now — a drop that flags a task for the day takes it out of
// the section it came from — hence optional.
async function endDrag(page: any, selector: string, title: string) {
  await fireDrag(page, selector, title, "dragend", 0.5, true);
}

async function dropOnItem(page: any, target: string, position: string) {
  const frac = position === "before" ? 0.25 : 0.75;
  for (const type of ["dragenter", "dragover", "drop"]) {
    await fireDrag(page, DAY_ITEMS, target, type, frac);
  }
  await page.waitForLoadState("networkidle");
}

Given(
  "a meet {string} with start date today and time {string} exists",
  async ({ request }, title: string, time: string) => {
    const meet = await (await request.post("/api/meets", { headers, data: { title } })).json();
    await request.put(`/api/meets/${meet.id}/start-date`, {
      headers,
      data: { "start-date": offsetDateStr(0) },
    });
    await request.put(`/api/meets/${meet.id}/start-time`, {
      headers,
      data: { "start-time": time },
    });
  },
);

When(
  "I drag the day-list task {string} after the day-list meet {string}",
  async ({ page }, source: string, target: string) => {
    await startDrag(page, DAY_ITEMS, source);
    await dropOnItem(page, target, "after");
    await endDrag(page, DAY_ITEMS, source);
  },
);

When(
  "I drag the day-list task {string} after the day-list task {string}",
  async ({ page }, source: string, target: string) => {
    await startDrag(page, DAY_ITEMS, source);
    await dropOnItem(page, target, "after");
    await endDrag(page, DAY_ITEMS, source);
  },
);

When(
  "I drag the day-list task {string} before the day-list task {string}",
  async ({ page }, source: string, target: string) => {
    await startDrag(page, DAY_ITEMS, source);
    await dropOnItem(page, target, "before");
    await endDrag(page, DAY_ITEMS, source);
  },
);

When(
  "I drag the urgent task {string} after the day-list meet {string}",
  async ({ page }, source: string, target: string) => {
    const urgent = ".urgency-subsection.urgent .draggable-urgent-task";
    await startDrag(page, urgent, source);
    await dropOnItem(page, target, "after");
    await endDrag(page, urgent, source);
  },
);

When(
  "I drag the overdue task {string} after the day-list task {string}",
  async ({ page }, source: string, target: string) => {
    await startOverdueDrag(page, source);
    await dropOnItem(page, target, "after");
  },
);

Given(
  "a task {string} overdue with an active reminder exists",
  async ({ request }, title: string) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
    await request.put(`/api/tasks/${task.id}/due-date`, {
      headers,
      data: { "due-date": offsetDateStr(-1) },
    });
    await request.put(`/api/tasks/${task.id}/reminder`, {
      headers,
      data: { "reminder-date": offsetDateStr(-1) },
    });
    await request.post("/api/test/activate-reminders", { headers });
  },
);

When(
  "I drag the reminder task {string} onto the day-list task {string}",
  async ({ page }, source: string, target: string) => {
    const reminders = ".today-section.reminders .draggable-reminder-task";
    await startDrag(page, reminders, source);
    await dropOnItem(page, target, "after");
    await endDrag(page, reminders, source);
  },
);

// The positive half of the pair: the drop was processed (it acknowledged the
// reminder), so the "no place in a day list" below cannot pass by the drag
// having quietly done nothing.
Then("the task {string} has no reminder left", async ({ request }, title: string) => {
  const task = await taskByTitle(request, title);
  expect(task.reminder).toBeNull();
});

Then("the task {string} has no place in a day list", async ({ request }, title: string) => {
  const task = await taskByTitle(request, title);
  expect(task.sort_order_today).toBeNull();
});

When(
  "I drop the day-list task {string} beside the day list heading",
  async ({ page }, source: string) => {
    await startDrag(page, DAY_ITEMS, source);
    for (const type of ["dragenter", "dragover", "drop"]) {
      await fireDrag(page, DAY_HEADING, "", type, 0.5);
    }
    await page.waitForLoadState("networkidle");
    await endDrag(page, DAY_ITEMS, source);
  },
);

Then("the day list reads {string}", async ({ page }, csv: string) => {
  const expected = csv.split(",").map((s) => s.trim());
  const items = page.locator(DAY_ITEMS);
  await expect(items).toHaveCount(expected.length);
  for (let i = 0; i < expected.length; i++) {
    await expect(items.nth(i)).toContainText(expected[i]);
  }
});

Then("the task list reads {string}", async ({ page }, csv: string) => {
  const expected = csv.split(",").map((s) => s.trim());
  const items = page.locator(".items li");
  await expect(items).toHaveCount(expected.length);
  for (let i = 0; i < expected.length; i++) {
    await expect(items.nth(i)).toContainText(expected[i]);
  }
});

Then("the day list has one heading", async ({ page }) => {
  await expect(page.locator(".today-section.today .today-subsection")).toHaveCount(1);
  await expect(page.locator(DAY_HEADING)).toHaveCount(1);
});

Then("the add button is the last thing in the day list", async ({ page }) => {
  const last = page.locator(".today-section.today .today-subsection > *:last-child");
  await expect(last).toHaveClass(/day-list-footer/);
  await expect(last.locator(".today-add-btn")).toHaveCount(1);
});

Then("the day-list task {string} is draggable", async ({ page }, title: string) => {
  await expect(dayItem(page, title)).toHaveAttribute("draggable", "true");
});

Then("the day-list meet {string} is not draggable", async ({ page }, title: string) => {
  await expect(dayItem(page, title)).toHaveAttribute("draggable", "false");
});

Then("a due-date confirmation is asked for", async ({ page }) => {
  await expect(page.locator(".modal-overlay .modal-body .task-title")).toBeVisible({ timeout: 5000 });
});

// Every drop handler ends by clearing the drag state, and the modal a mis-routed
// drop would open is set in the same swap!, so once the dragged card has lost
// its "dragging" class the modal would already have rendered with it.
Then("no due-date confirmation is asked for", async ({ page }) => {
  await expect(page.locator(".dragging")).toHaveCount(0, { timeout: 5000 });
  await expect(page.locator(".modal-overlay")).toHaveCount(0);
});

async function taskByTitle(request: any, title: string) {
  const tasks = await (await request.get("/api/tasks?sort=today", { headers })).json();
  const task = tasks.find((t: any) => t.title === title);
  expect(task, `task ${title} not found`).toBeTruthy();
  return task;
}

Then("the task {string} is still due today", async ({ request }, title: string) => {
  const task = await taskByTitle(request, title);
  expect(task.due_date).toBe(offsetDateStr(0));
  expect(task.today).toBe(0);
  expect(task.lined_up_for).toBeNull();
});

Then("the task {string} is still due yesterday", async ({ request }, title: string) => {
  const task = await taskByTitle(request, title);
  expect(task.due_date).toBe(offsetDateStr(-1));
});

Then("{string} is gone from the urgent subsection", async ({ page }, title: string) => {
  await expect(
    page.locator(".urgency-subsection.urgent .draggable-urgent-task").filter({ hasText: title }),
  ).toHaveCount(0);
});
