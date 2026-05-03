import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

function dateStr(daysOffset: number = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + daysOffset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

Given("a task {string} with due date today exists", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/due-date`, {
    headers,
    data: { "due-date": dateStr() },
  });
});

Given("a task {string} with due date yesterday exists", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/due-date`, {
    headers,
    data: { "due-date": dateStr(-1) },
  });
});

Given(
  "a task {string} with urgency {string} exists",
  async ({ request }, title: string, urgency: string) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
    await request.put(`/api/tasks/${task.id}/urgency`, {
      headers,
      data: { urgency },
    });
  },
);

async function postJson(request: any, url: string, data: any) {
  const resp = await request.post(url, { headers, data });
  const text = await resp.text();
  if (!resp.ok()) throw new Error(`${url} returned ${resp.status()}: ${text}`);
  if (!text) throw new Error(`${url} returned empty body (status ${resp.status()})`);
  return JSON.parse(text);
}

Given("a meet {string} with start date today exists", async ({ request }, title: string) => {
  const meet = await postJson(request, "/api/meets", { title });
  await request.put(`/api/meets/${meet.id}/start-date`, {
    headers,
    data: { "start-date": dateStr() },
  });
});

Given("a task {string} lined up for tomorrow exists", async ({ request }, title: string) => {
  const task = await postJson(request, "/api/tasks", { title });
  await request.put(`/api/tasks/${task.id}/lined-up-for`, {
    headers,
    data: { lined_up_for: dateStr(1) },
  });
});

Given("a task {string} with due date in 5 days exists", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/due-date`, {
    headers,
    data: { "due-date": dateStr(5) },
  });
});

Given(
  "a task {string} with due date today and time {string} exists",
  async ({ request }, title: string, time: string) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
    await request.put(`/api/tasks/${task.id}/due-date`, {
      headers,
      data: { "due-date": dateStr() },
    });
    await request.put(`/api/tasks/${task.id}/due-time`, {
      headers,
      data: { "due-time": time },
    });
  },
);

When("I send task {string} to today", async ({ page }, title: string) => {
  const taskRow = page.locator(".items li").filter({ hasText: title });
  await taskRow.locator(".link-today-btn").click();
  await page.locator(".send-to-day-dropdown button").filter({ hasText: "Today" }).click();
  await page.waitForLoadState("networkidle");
});

When("I navigate to the {string} tab", async ({ page }, name: string) => {
  await page.locator(".tabs button").filter({ hasText: name }).click();
  await page.waitForLoadState("networkidle");
});

When("I click the second day button", async ({ page }) => {
  await page.locator(".day-selector button").nth(1).click();
  await page.waitForLoadState("networkidle");
});

When("I add a task {string} via the today add button", async ({ page }, title: string) => {
  await page.locator(".today-add-btn").click();
  await page.locator(".today-add-input").fill(title);
  await page.locator(".today-add-input").press("Enter");
  await page.waitForLoadState("networkidle");
});

When("I click the {string} view switcher button", async ({ page }, name: string) => {
  await page.locator(".today-view-switcher button").filter({ hasText: name }).click();
  await page.waitForLoadState("networkidle");
});

Then("I should see {string} in the today view", async ({ page }, text: string) => {
  await expect(page.locator(".today-content")).toContainText(text, { timeout: 5000 });
});

Then("I should see {string} in the overdue section", async ({ page }, text: string) => {
  await expect(page.locator(".today-section.overdue")).toContainText(text, { timeout: 5000 });
});

Then("I should see {string} in the urgent subsection", async ({ page }, text: string) => {
  await expect(page.locator(".urgency-subsection.urgent")).toContainText(text, { timeout: 5000 });
});

Then("I should see {string} in the superurgent subsection", async ({ page }, text: string) => {
  await expect(page.locator(".urgency-subsection.superurgent")).toContainText(text, { timeout: 5000 });
});

Then("I should see {string} in the other things section", async ({ page }, text: string) => {
  await expect(page.locator(".today-subsection.other-things")).toContainText(text, { timeout: 5000 });
});

Then("I should see {string} in the upcoming section", async ({ page }, text: string) => {
  await expect(page.locator(".today-section.upcoming")).toContainText(text, { timeout: 5000 });
});
