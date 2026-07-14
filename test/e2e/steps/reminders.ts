import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue, offsetDateStr } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const dateStr = (daysOffset: number = 0): string => offsetDateStr(daysOffset);

Given(
  "a task {string} with an active reminder exists",
  async ({ request }, title: string) => {
    const task = await (
      await request.post("/api/tasks", { headers, data: { title } })
    ).json();
    await request.put(`/api/tasks/${task.id}/reminder`, {
      headers,
      data: { "reminder-date": dateStr(-1) },
    });
    await request.post("/api/test/activate-reminders", { headers });
  },
);

Given(
  "a task {string} with an active reminder and urgency {string} exists",
  async ({ request }, title: string, urgency: string) => {
    const task = await (
      await request.post("/api/tasks", { headers, data: { title } })
    ).json();
    await request.put(`/api/tasks/${task.id}/urgency`, {
      headers,
      data: { urgency },
    });
    await request.put(`/api/tasks/${task.id}/reminder`, {
      headers,
      data: { "reminder-date": dateStr(-1) },
    });
    await request.post("/api/test/activate-reminders", { headers });
  },
);

When("I open the dropdown on task {string}", async ({ page }, title: string) => {
  const taskRow = page.locator(".items li").filter({ hasText: title });
  await taskRow.locator(".combined-dropdown-btn").click();
  await page.waitForLoadState("networkidle");
});

When("I click {string} in the dropdown", async ({ page }, label: string) => {
  await page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: label }).click();
  await page.waitForLoadState("networkidle");
});

When("I pick a reminder date 3 days from now", async ({ page }) => {
  // The reminder modal's date input is a controlled reagent input; a plain
  // fill() occasionally sets the DOM value without its on-change committing to
  // the :selected-date atom, so confirm would send a null date and the reminder
  // never persists. setFieldValue retries until the value sticks (a controlled
  // input reverts if the atom didn't update), guaranteeing the date committed.
  await setFieldValue(page.locator(".modal .date-picker-input"), dateStr(3));
});

When("I confirm the reminder modal", async ({ page }) => {
  const confirm = page.locator(".modal .modal-footer .confirm");
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await page.waitForLoadState("networkidle");
});

Then(
  "the task {string} should have a reminder date set",
  async ({ request }, title: string) => {
    const tasks = await (await request.get("/api/tasks?sort=recent", { headers })).json();
    const task = tasks.find((t: any) => t.title === title);
    expect(task).toBeTruthy();
    expect(task.reminder_date).toBeTruthy();
  },
);

Then(
  "I should see the {string} button on task {string}",
  async ({ page }, buttonText: string, title: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: title });
    await expect(taskRow.getByRole("button", { name: buttonText })).toBeVisible();
  },
);

Then(
  "I should not see the dropdown button on task {string}",
  async ({ page }, title: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: title });
    await expect(taskRow.locator(".combined-dropdown-btn")).not.toBeVisible();
  },
);

Then(
  "I should see the dropdown button on task {string}",
  async ({ page }, title: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: title });
    await expect(taskRow.locator(".combined-dropdown-btn")).toBeVisible();
  },
);

Then(
  "I should not see the {string} button on task {string}",
  async ({ page }, buttonText: string, title: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: title });
    await expect(
      taskRow.getByRole("button", { name: buttonText }),
    ).not.toBeVisible();
  },
);

When(
  "I click {string} on task {string}",
  async ({ page }, buttonText: string, title: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: title });
    await taskRow.getByRole("button", { name: buttonText }).click();
    await page.waitForLoadState("networkidle");
  },
);

Then(
  "I should see {string} in the reminders section",
  async ({ page }, text: string) => {
    await expect(
      page.locator(".today-section.reminders"),
    ).toContainText(text, { timeout: 5000 });
  },
);

Then("the Reminders button should have an indicator", async ({ page }) => {
  const btn = page.locator(".today-view-switcher button").filter({ hasText: "Reminders" });
  await expect(btn.locator(".reminder-indicator")).toBeVisible();
});

When("I expand reminder task {string}", async ({ page }, title: string) => {
  await page
    .locator(".today-section.reminders .today-task-item")
    .filter({ hasText: title })
    .locator(".today-task-header")
    .click();
  await page.waitForLoadState("networkidle");
});

When(
  "I click {string} on reminder task {string}",
  async ({ page }, buttonText: string, title: string) => {
    const taskItem = page
      .locator(".today-section.reminders .today-task-item")
      .filter({ hasText: title });
    await taskItem.getByRole("button", { name: buttonText }).click();
    await page.waitForLoadState("networkidle");
  },
);

When("I collapse reminder task {string}", async ({ page }, title: string) => {
  await page
    .locator(".today-section.reminders .today-task-item")
    .filter({ hasText: title })
    .locator(".today-task-header")
    .click();
  await page.waitForLoadState("networkidle");
});

Then(
  "I should not see {string} in the reminders section",
  async ({ page }, text: string) => {
    await expect(
      page.locator(".today-section.reminders"),
    ).not.toContainText(text, { timeout: 5000 });
  },
);

Then(
  "the task {string} should not have an active reminder",
  async ({ request }, title: string) => {
    const tasks = await (await request.get("/api/tasks?sort=recent", { headers })).json();
    const task = tasks.find((t: any) => t.title === title);
    expect(task).toBeTruthy();
    expect(task.reminder).not.toBe("active");
  },
);

Then(
  "the task {string} should still have an active reminder",
  async ({ request }, title: string) => {
    const tasks = await (await request.get("/api/tasks?sort=recent", { headers })).json();
    const task = tasks.find((t: any) => t.title === title);
    expect(task).toBeTruthy();
    expect(task.reminder).toBe("active");
  },
);
