import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const todayCard = (page: any, title: string) =>
  page.locator(".today-section.today .today-task-item").filter({ hasText: title });

async function openTodayDropdown(page: any, title: string) {
  await todayCard(page, title).locator(".combined-dropdown-btn").click();
  await expect(page.locator(".task-dropdown-menu")).toBeVisible();
}

// The toggle is one PUT and no list refetch, so wait on the response rather
// than on networkidle: there is no other traffic to settle.
async function toggleWorkOn(page: any, title: string, expectedLabel: string) {
  await openTodayDropdown(page, title);
  const item = page.locator(".task-dropdown-menu .dropdown-item.toggle-work-on");
  await expect(item).toHaveText(expectedLabel);
  const response = page.waitForResponse(
    (resp: any) => resp.url().includes("/work-on") && resp.request().method() === "PUT",
  );
  await item.click();
  await response;
}

Given("a task {string} flagged for today exists", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/today`, { headers, data: { today: true } });
});

When("I expand the today task {string}", async ({ page }, title: string) => {
  await todayCard(page, title).locator(".today-task-header").click();
  await expect(todayCard(page, title)).toHaveClass(/\bexpanded\b/);
});

When("I start working on the today task {string}", async ({ page }, title: string) => {
  await toggleWorkOn(page, title, "Work on task");
});

When("I stop working on the today task {string}", async ({ page }, title: string) => {
  await toggleWorkOn(page, title, "Stop working on task");
});

When("I open the footer dropdown on the today task {string}", async ({ page }, title: string) => {
  await openTodayDropdown(page, title);
});

When("I click the done button on the today task {string}", async ({ page }, title: string) => {
  await todayCard(page, title).locator(".combined-main-btn.done").click();
  await page.waitForLoadState("networkidle");
});

Then("the task footer dropdown offers {string}", async ({ page }, label: string) => {
  await expect(
    page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: label }),
  ).toHaveCount(1);
});

Then("the task footer dropdown does not offer {string}", async ({ page }, label: string) => {
  await expect(
    page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: label }),
  ).toHaveCount(0);
});

Then(
  "the today task {string} should carry the working-on dot",
  async ({ page }, title: string) => {
    await expect(todayCard(page, title).locator(".working-on-indicator")).toHaveCount(1);
  },
);

Then(
  "the today task {string} should not carry the working-on dot",
  async ({ page }, title: string) => {
    await expect(todayCard(page, title).locator(".working-on-indicator")).toHaveCount(0);
  },
);

Then("the working-on API reports no task", async ({ request }) => {
  const marker = await (await request.get("/api/working-on", { headers })).json();
  expect(marker["task-id"]).toBeNull();
});
