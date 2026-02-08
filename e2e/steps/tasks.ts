import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

Given("I am on the app", async ({ page, request }) => {
  await request.post("/api/test/reset");
  await page.goto("/");
});

Given("test data with categorized tasks exists", async ({ request }) => {
  const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

  const lagos = await (await request.post("/api/places", { headers, data: { name: "Lagos" } })).json();
  const bordeira = await (await request.post("/api/places", { headers, data: { name: "Bordeira" } })).json();
  const renovations = await (await request.post("/api/projects", { headers, data: { name: "Renovations" } })).json();

  const task1 = await (await request.post("/api/tasks", { headers, data: { title: "Fix plumbing" } })).json();
  const task2 = await (await request.post("/api/tasks", { headers, data: { title: "Paint walls" } })).json();

  await request.post(`/api/tasks/${task1.id}/categorize`, {
    headers, data: { "category-type": "place", "category-id": lagos.id },
  });
  await request.post(`/api/tasks/${task1.id}/categorize`, {
    headers, data: { "category-type": "project", "category-id": renovations.id },
  });
  await request.post(`/api/tasks/${task2.id}/categorize`, {
    headers, data: { "category-type": "place", "category-id": lagos.id },
  });
  await request.post(`/api/tasks/${task2.id}/categorize`, {
    headers, data: { "category-type": "place", "category-id": bordeira.id },
  });
});

When("I click the {string} tab", async ({ page }, name: string) => {
  await page.getByRole("button", { name }).click();
});

When("I type {string} in the search field", async ({ page }, text: string) => {
  await page.locator("#tasks-filter-search").fill(text);
});

When("I click the add button", async ({ page }) => {
  await page.locator(".combined-search-add-form button").first().click();
});

When("I add a place called {string}", async ({ page }, name: string) => {
  await page.locator(".add-entity-form input").last().fill(name);
  await page.locator(".add-entity-form button").last().click();
});

When("I add a task called {string}", async ({ page }, title: string) => {
  await page.locator("#tasks-filter-search").fill(title);
  await page.locator(".combined-search-add-form button").first().click();
  await page.locator("#tasks-filter-search").fill("");
});

When(
  "I assign the place {string} to task {string}",
  async ({ page }, place: string, task: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: task }).first();
    const placeBtn = taskRow.getByRole("button", { name: "+ Place" });
    if (!(await placeBtn.isVisible())) await taskRow.click();
    await placeBtn.click();
    await page.locator(".category-selector-item").filter({ hasText: place }).first().click();
  },
);

When("I filter by place {string}", async ({ page }, place: string) => {
  await page.locator(".filter-section.places .collapse-toggle").click();
  await page.locator(".filter-section.places .filter-item").filter({ hasText: place }).click();
});

When("I switch to {string}", async ({ page }, tab: string) => {
  await page.getByRole("button", { name: tab }).click();
});

When("I add a project called {string}", async ({ page }, name: string) => {
  await page.locator(".add-entity-form input").first().fill(name);
  await page.locator(".add-entity-form button").first().click();
});

When(
  "I assign the project {string} to task {string}",
  async ({ page }, project: string, task: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: task }).first();
    const projectBtn = taskRow.getByRole("button", { name: "+ Project" });
    if (!(await projectBtn.isVisible())) {
      await taskRow.locator(".item-header").click();
      await projectBtn.waitFor({ state: "visible" });
    }
    await projectBtn.click();
    await page.locator(".category-selector-item").filter({ hasText: project }).first().click();
  },
);

When(
  "I click the {string} badge on task {string}",
  async ({ page }, badge: string, task: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge })
      .click();
  },
);

Then(
  "the {string} badge on task {string} should be clickable",
  async ({ page }, badge: string, task: string) => {
    const tag = page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge });
    await expect(tag).toHaveCSS("cursor", "pointer");
  },
);

Then(
  "the {string} badge on task {string} should not be clickable",
  async ({ page }, badge: string, task: string) => {
    const tag = page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge });
    await expect(tag).not.toHaveCSS("cursor", "pointer");
  },
);

Then("I should see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text);
});

Then("I should not see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).not.toContainText(text);
});
