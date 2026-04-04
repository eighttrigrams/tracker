import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

function todayDateStr(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

Given("a task {string} with due date today exists", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/due-date`, {
    headers,
    data: { "due-date": todayDateStr() },
  });
});

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

Then("I should see {string} in the today view", async ({ page }, text: string) => {
  await expect(page.locator(".today-content")).toContainText(text, { timeout: 5000 });
});
