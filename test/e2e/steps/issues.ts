import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("an issue {string} exists", async ({ request }, title: string) => {
  await request.post("/api/issues", { headers, data: { title } });
});

Given(
  "a task {string} belongs to issue {string}",
  async ({ request }, taskTitle: string, issueTitle: string) => {
    const task = await (await request.post("/api/tasks", { headers, data: { title: taskTitle } })).json();
    const issue = await (await request.post("/api/issues", { headers, data: { title: issueTitle } })).json();
    await request.post("/api/relations", {
      headers,
      data: { "source-type": "tsk", "source-id": task.id, "target-type": "iss", "target-id": issue.id },
    });
  },
);

Given(
  "an issue {string} categorised with place {string} exists",
  async ({ request }, issueTitle: string, placeName: string) => {
    const place = await (await request.post("/api/places", { headers, data: { name: placeName } })).json();
    const issue = await (await request.post("/api/issues", { headers, data: { title: issueTitle } })).json();
    await request.post(`/api/issues/${issue.id}/categorize`, {
      headers,
      data: { "category-type": "place", "category-id": place.id },
    });
  },
);

When("I type {string} in the issues search field", async ({ page }, text: string) => {
  await page.locator("#issues-filter-search").fill(text);
});

When("I click the issues add button", async ({ page }) => {
  await page.locator(".combined-search-add-form button").first().click();
  await page.waitForLoadState("networkidle");
});

When("I click the create-task button on issue {string}", async ({ page }, issueTitle: string) => {
  const card = page.locator(".items li").filter({ hasText: issueTitle });
  await card.locator(".create-next-meeting-btn").click();
  await page.waitForLoadState("networkidle");
});

When("I click the issue icon on task {string}", async ({ page }, taskTitle: string) => {
  const card = page.locator(".items li").filter({ hasText: taskTitle });
  await card.locator(".issue-icon").click();
  await page.waitForLoadState("networkidle");
});

When("I click the Inbox icon", async ({ page }) => {
  await page.locator(".inbox-btn").click();
  await page.waitForLoadState("networkidle");
});

Then("I should see the {string} tab in the navbar", async ({ page }, name: string) => {
  await expect(page.locator(".top-bar .tabs").getByRole("button", { name })).toBeVisible();
});

Then("I should see the Inbox icon", async ({ page }) => {
  await expect(page.locator(".inbox-btn")).toBeVisible();
});

Then("I should see {string} in the issues list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("the task {string} shows the issue icon", async ({ page }, taskTitle: string) => {
  const card = page.locator(".items li").filter({ hasText: taskTitle });
  await expect(card.locator(".issue-icon")).toBeVisible({ timeout: 5000 });
});

Then("I should see the issue filter bar for {string}", async ({ page }, issueTitle: string) => {
  await expect(page.locator(".issues-page .series-filter-bar .series-filter-label")).toHaveText(issueTitle, {
    timeout: 5000,
  });
});

Then(
  "I should see the task {string} in the focused issue task listing",
  async ({ page }, taskTitle: string) => {
    await expect(page.locator(".issues-page .issue-tasks .tag", { hasText: taskTitle })).toBeVisible({
      timeout: 5000,
    });
  },
);

Then("I should see the inbox page", async ({ page }) => {
  await expect(page.locator(".mail-page")).toBeVisible();
});
