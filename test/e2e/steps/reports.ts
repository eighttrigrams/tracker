import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { apiCategorize, today, latestPastDayThisWeek, previousWeekDay } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("report data with categorized items exists", async ({ request }) => {
  const alice = await (await request.post("/api/people", { headers, data: { name: "Alice" } })).json();
  const apollo = await (await request.post("/api/projects", { headers, data: { name: "Apollo" } })).json();
  await request.post("/api/places", { headers, data: { name: "Office" } });

  const task = await (await request.post("/api/tasks", { headers, data: { title: "Buy paint" } })).json();
  await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
  await apiCategorize(request, `/api/tasks/${task.id}`, "person", alice.id);
  await apiCategorize(request, `/api/tasks/${task.id}`, "project", apollo.id);

  const journal = await (await request.post("/api/journals", { headers, data: { title: "Daily log" } })).json();
  const entry = await (await request.post(`/api/journals/${journal.id}/create-entry`, {
    headers, data: { date: today() },
  })).json();
  await apiCategorize(request, `/api/journal-entries/${entry.id}`, "person", alice.id);
  await apiCategorize(request, `/api/journal-entries/${entry.id}`, "project", apollo.id);

  const meet = await (await request.post("/api/meets/", { headers, data: { title: "Standup" } })).json();
  await request.put(`/api/meets/${meet.id}/start-date`, { headers, data: { "start-date": previousWeekDay() } });
  await apiCategorize(request, `/api/meets/${meet.id}`, "person", alice.id);
  await apiCategorize(request, `/api/meets/${meet.id}`, "project", apollo.id);
});

Given("a report day with a task, a meet, and a journal entry exists", async ({ request }) => {
  const day = latestPastDayThisWeek();

  const task = await (await request.post("/api/tasks", { headers, data: { title: "Buy paint" } })).json();
  await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
  await request.put(`/api/tasks/${task.id}/done-at`, { headers, data: { "done-date": day } });

  const meet = await (await request.post("/api/meets/", { headers, data: { title: "Standup" } })).json();
  await request.put(`/api/meets/${meet.id}/start-date`, { headers, data: { "start-date": previousWeekDay() } });

  const journal = await (await request.post("/api/journals", { headers, data: { title: "Daily log" } })).json();
  await request.post(`/api/journals/${journal.id}/create-entry`, { headers, data: { date: day } });
});

Given(
  "done report tasks {string} and {string} exist",
  async ({ request }, first: string, second: string) => {
    const todayStr = today();
    for (const title of [first, second]) {
      const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
      await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
      await request.put(`/api/tasks/${task.id}/done-at`, { headers, data: { "done-date": todayStr } });
    }
  },
);

Then("I should not see any report type sub-headings", async ({ page }) => {
  await expect(page.locator(".report-section-label")).toHaveCount(0);
});

When("I open the action dropdown on report item {string}", async ({ page }, title: string) => {
  await page.locator(".report-item").filter({ hasText: title }).locator(".combined-dropdown-btn").click();
});

Then("the action dropdown on report item {string} is open", async ({ page }, title: string) => {
  const card = page.locator(".report-item").filter({ hasText: title });
  await expect(card.locator(".task-dropdown-menu")).toBeVisible({ timeout: 5000 });
});

Then("the action dropdown on report item {string} is closed", async ({ page }, title: string) => {
  const card = page.locator(".report-item").filter({ hasText: title });
  await expect(card).toBeVisible({ timeout: 5000 });
  await expect(card.locator(".task-dropdown-menu")).toHaveCount(0);
});

When("I select the {string} reports filter", async ({ page }, label: string) => {
  await page.locator(".items-filter-toggle button").filter({ hasText: label }).click();
  await page.waitForLoadState("networkidle");
});

Then("the report journal title carries no notepad glyph", async ({ page }) => {
  await expect(page.locator(".report-journal-entry").first()).toBeVisible();
  await expect(page.locator(".report-journal-entry .item-title-icon")).toHaveCount(0);
});

Then("the report meet title carries the calendar glyph", async ({ page }) => {
  await expect(page.locator(".report-meet .item-title-icon")).toContainText("🗓️");
});

Then("the report journal title carries the notepad glyph", async ({ page }) => {
  await expect(page.locator(".report-journal-entry .item-title-icon")).toContainText("📝");
});

Then("the report task title carries the checkbox glyph", async ({ page }) => {
  await expect(page.locator(".report-task .item-title-icon")).toContainText("☑");
});

Then(
  "the report day items appear in order journals, then tasks, then meets",
  async ({ page }) => {
    await expect(page.locator(".report-day-group .report-meet")).toBeVisible();
    const classes = await page
      .locator(".report-day-group .report-item")
      .evaluateAll((els) => els.map((el) => el.className));
    const journalIdx = classes.findIndex((c) => c.includes("report-journal-entry"));
    const taskIdx = classes.findIndex((c) => c.includes("report-task"));
    const meetIdx = classes.findIndex((c) => c.includes("report-meet"));
    expect(journalIdx).toBeGreaterThanOrEqual(0);
    expect(taskIdx).toBeGreaterThan(journalIdx);
    expect(meetIdx).toBeGreaterThan(taskIdx);
  },
);

When("I expand the report item {string}", async ({ page }, title: string) => {
  await page.locator(".report-item").filter({ hasText: title }).locator(".item-header").click();
  await page.locator(`.report-item.expanded`).filter({ hasText: title }).waitFor({ state: "visible", timeout: 5000 });
});

When(
  "I assign the place {string} to the report item {string}",
  async ({ page }, place: string, title: string) => {
    const item = page.locator(".report-item.expanded").filter({ hasText: title });
    await item.getByRole("button", { name: "+ Place" }).click();
    await page.locator(".category-selector-item").filter({ hasText: place }).first().click();
    await page.waitForLoadState("networkidle");
  },
);
