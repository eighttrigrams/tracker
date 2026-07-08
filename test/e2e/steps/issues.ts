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
  "an issue {string} with an open task {string} and a completed task {string}",
  async ({ request }, issueTitle: string, openTitle: string, doneTitle: string) => {
    const issue = await (await request.post("/api/issues", { headers, data: { title: issueTitle } })).json();
    const link = async (taskTitle: string) => {
      const task = await (await request.post("/api/tasks", { headers, data: { title: taskTitle } })).json();
      await request.post("/api/relations", {
        headers,
        data: { "source-type": "tsk", "source-id": task.id, "target-type": "iss", "target-id": issue.id },
      });
      return task;
    };
    await link(openTitle);
    const done = await link(doneTitle);
    await request.put(`/api/tasks/${done.id}/done`, { headers, data: { done: true } });
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

Given("a resolved issue {string} exists", async ({ request }, title: string) => {
  const issue = await (await request.post("/api/issues", { headers, data: { title } })).json();
  await request.put(`/api/issues/${issue.id}/resolved`, { headers, data: { resolved: true } });
});

When("I type {string} in the issues search field", async ({ page }, text: string) => {
  await page.locator("#issues-filter-search").fill(text);
});

When("I expand the issue {string}", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".item-header").click();
  await page.waitForLoadState("networkidle");
});

When("I click the resolve button on issue {string}", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await card.locator(".combined-main-btn.done").click();
  await page.waitForLoadState("networkidle");
});

When("I switch the issues sort to {string}", async ({ page }, mode: string) => {
  await page.locator(".sort-toggle").getByRole("button", { name: mode, exact: true }).click();
  await page.waitForLoadState("networkidle");
});

When("I open the footer dropdown on issue {string}", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".combined-dropdown-btn").click();
});

When("I confirm the issue deletion", async ({ page }) => {
  await page.locator(".modal-overlay .confirm-delete").click();
  await page.waitForLoadState("networkidle");
});

When("I click the issues add button", async ({ page }) => {
  await page.locator(".combined-search-add-form button").first().click();
  await page.waitForLoadState("networkidle");
});

When("I click the create-task button on issue {string}", async ({ page }, issueTitle: string) => {
  // The issues list re-renders when its filtered fetch resolves, which can land
  // just as we click and detach the card mid-action ("element was detached from
  // the DOM"). Re-attempt the click as a unit and confirm the modal opened, so
  // we interact once the list has settled instead of racing a repaint.
  const modal = page.locator(".create-task-modal");
  await expect(async () => {
    if (await modal.isVisible()) return;
    const btn = page.locator(".items li").filter({ hasText: issueTitle }).locator(".create-next-meeting-btn");
    await btn.click({ timeout: 3000 });
    await expect(modal).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 15000 });
});

When("I enter {string} as the task title", async ({ page }, title: string) => {
  await page.locator(".create-task-modal .create-task-title-input").fill(title);
});

When("I confirm the create-task modal", async ({ page }) => {
  await page.locator(".create-task-modal .confirm").click();
  await page.waitForLoadState("networkidle");
});

When("I click the show-tasks button on issue {string}", async ({ page }, issueTitle: string) => {
  const card = page.locator(".items li").filter({ hasText: issueTitle });
  await card.locator(".issue-filter-btn").click();
  await page.waitForLoadState("networkidle");
});

When("I click the issue icon on task {string}", async ({ page }, taskTitle: string) => {
  const card = page.locator(".items li").filter({ hasText: taskTitle });
  await card.locator(".issue-icon").click();
  await page.waitForLoadState("networkidle");
});

Then("I should see the {string} tab in the navbar", async ({ page }, name: string) => {
  await expect(page.locator(".top-bar .tabs").getByRole("button", { name })).toBeVisible();
});

Then("I should see {string} in the issues list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("I should not see {string} in the issues list", async ({ page }, text: string) => {
  await expect(page.locator(".items li").filter({ hasText: text })).toHaveCount(0, { timeout: 5000 });
});

Then("the resolve button on issue {string} is disabled", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card.locator(".combined-main-btn.done")).toBeDisabled({ timeout: 5000 });
});

Then("the create-task button on issue {string} is not present", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card).toBeVisible({ timeout: 5000 });
  await expect(card.locator(".create-next-meeting-btn")).toHaveCount(0);
});

Then("the footer dropdown on issue {string} is open", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card.locator(".task-dropdown-menu")).toBeVisible({ timeout: 5000 });
});

Then("the footer dropdown on issue {string} is closed", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await expect(card).toBeVisible({ timeout: 5000 });
  await expect(card.locator(".task-dropdown-menu")).toHaveCount(0);
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
    await expect(
      page.locator(".issues-page .items li").filter({ hasText: taskTitle }),
    ).toBeVisible({ timeout: 5000 });
  },
);

Then(
  "the task {string} in the focused issue task listing is a task card",
  async ({ page }, taskTitle: string) => {
    // The shared item-card renders an .item-header; the old plain badge did not.
    const card = page.locator(".issues-page .items li").filter({ hasText: taskTitle });
    await expect(card.locator(".item-header")).toBeVisible({ timeout: 5000 });
  },
);

Then(
  "no task in the focused issue task listing shows the belongs-to-issue icon",
  async ({ page }) => {
    // Confirm at least one task card is present, then that none carry the ◈.
    await expect(page.locator(".issues-page .items li").first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator(".issues-page .items li .issue-icon")).toHaveCount(0);
  },
);

Then("I should see the completed divider in the focused issue task listing", async ({ page }) => {
  await expect(page.locator(".issues-page .done-divider")).toBeVisible({ timeout: 5000 });
  await expect(page.locator(".issues-page .done-heading")).toBeVisible();
});

Then(
  "the task {string} is listed above the completed divider",
  async ({ page }, taskTitle: string) => {
    // The active section is the first .items list, before the divider/heading.
    const activeSection = page.locator(".issues-page .items").first();
    await expect(activeSection.locator("li").filter({ hasText: taskTitle })).toBeVisible({ timeout: 5000 });
    // ...and not misplaced into the completed section.
    const doneSection = page.locator(".issues-page .done-heading + .items");
    await expect(doneSection.locator("li").filter({ hasText: taskTitle })).toHaveCount(0);
  },
);

Then(
  "the task {string} is listed under the completed heading",
  async ({ page }, taskTitle: string) => {
    const doneSection = page.locator(".issues-page .done-heading + .items");
    await expect(doneSection.locator("li").filter({ hasText: taskTitle })).toBeVisible({ timeout: 5000 });
  },
);

Then("I should see the inbox page", async ({ page }) => {
  await expect(page.locator(".mail-page")).toBeVisible();
});
