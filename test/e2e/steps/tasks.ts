import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue, apiCategorize } from "./helpers";

const { Given, When, Then } = createBdd();

Given("I am on the app", async ({ page, request }) => {
  await request.post("/api/test/reset");
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".top-bar .tabs")).toBeVisible();
});

Given("test data with categorized tasks exists", async ({ request }) => {
  const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

  const lagos = await (await request.post("/api/places", { headers, data: { name: "Lagos" } })).json();
  const bordeira = await (await request.post("/api/places", { headers, data: { name: "Bordeira" } })).json();
  const renovations = await (await request.post("/api/projects", { headers, data: { name: "Renovations" } })).json();

  const task1 = await (await request.post("/api/tasks", { headers, data: { title: "Fix plumbing" } })).json();
  const task2 = await (await request.post("/api/tasks", { headers, data: { title: "Paint walls" } })).json();

  await apiCategorize(request, `/api/tasks/${task1.id}`, "place", lagos.id);
  await apiCategorize(request, `/api/tasks/${task1.id}`, "project", renovations.id);
  await apiCategorize(request, `/api/tasks/${task2.id}`, "place", lagos.id);
  await apiCategorize(request, `/api/tasks/${task2.id}`, "place", bordeira.id);
});

Then(
  "task {string} should show category add buttons",
  async ({ page }, task: string) => {
    const triggers = page
      .locator(".items li")
      .filter({ hasText: task })
      .first()
      .locator(".category-selector-trigger");
    await expect(triggers.first()).toBeVisible();
  },
);

Then(
  "task {string} should not show category add buttons",
  async ({ page }, task: string) => {
    const triggers = page
      .locator(".items li")
      .filter({ hasText: task })
      .first()
      .locator(".category-selector-trigger");
    await expect(triggers).toHaveCount(0);
  },
);

Then(
  "task {string} should show badge remove buttons",
  async ({ page }, task: string) => {
    const removes = page
      .locator(".items li")
      .filter({ hasText: task })
      .first()
      .locator(".remove-tag");
    await expect(removes.first()).toBeVisible();
  },
);

Then(
  "task {string} should not show badge remove buttons",
  async ({ page }, task: string) => {
    const removes = page
      .locator(".items li")
      .filter({ hasText: task })
      .first()
      .locator(".remove-tag");
    await expect(removes).toHaveCount(0);
  },
);

Then(
  "task {string} should not show category remove buttons",
  async ({ page }, task: string) => {
    const removes = page
      .locator(".items li")
      .filter({ hasText: task })
      .first()
      .locator(".tag-selector .remove-tag");
    await expect(removes).toHaveCount(0);
  },
);

Then(
  "task {string} should show relation remove buttons",
  async ({ page }, task: string) => {
    const removes = page
      .locator(".items li")
      .filter({ hasText: task })
      .first()
      .locator(".relation-badges-expanded .remove-tag");
    await expect(removes.first()).toBeVisible();
  },
);

When("I reload the page", async ({ page }) => {
  await page.reload();
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".top-bar .tabs")).toBeVisible();
});

When("I click the {string} tab", async ({ page }, name: string) => {
  const tab = page.locator(".top-bar .tabs").getByRole("button", { name });
  await tab.click();
  await expect(tab).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

When("I click the category badges toggle", async ({ page }) => {
  await page.locator(".sidebar .category-badge-toggle").first().click();
});

Then(
  "the {string} badge on task {string} should be visible",
  async ({ page }, badge: string, task: string) => {
    const tag = page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge });
    await expect(tag).toBeVisible();
  },
);

Then(
  "the {string} badge on task {string} should not be visible",
  async ({ page }, badge: string, task: string) => {
    const tag = page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge });
    await expect(tag).toHaveCount(0);
  },
);

When("I type {string} in the search field", async ({ page }, text: string) => {
  await page.locator("#tasks-filter-search").fill(text);
});

When("I click the add button", async ({ page }) => {
  await page.locator(".combined-search-add-form button").first().click();
  await page.waitForLoadState("networkidle");
});

When("I add a place called {string}", async ({ page }, name: string) => {
  await setFieldValue(page.locator(".add-entity-form input").last(), name);
  await page.locator(".add-entity-form button").last().click();
  await page.waitForLoadState("networkidle");
});

When("I add a task called {string}", async ({ page }, title: string) => {
  const search = page.locator("#tasks-filter-search");
  await setFieldValue(search, title);
  await page.locator(".combined-search-add-form button").first().click();
  await expect(page.locator(".items li").filter({ hasText: title }).first()).toBeVisible();
  await expect(search).toHaveValue("");
});

When(
  "I assign the place {string} to task {string}",
  async ({ page }, place: string, task: string) => {
    const taskRow = page.locator(".items li").filter({ hasText: task }).first();
    const placeBtn = taskRow.getByRole("button", { name: "+ Place" });
    if (!(await placeBtn.isVisible())) {
      await taskRow.locator(".item-header").click();
      await placeBtn.waitFor({ state: "visible" });
    }
    await placeBtn.click();
    await page.locator(".category-selector-item").filter({ hasText: place }).first().click();
    await expect(taskRow.locator(".tag", { hasText: place })).toBeVisible();
  },
);

When("I filter by place {string}", async ({ page }, place: string) => {
  await page.locator(".filter-section.places .collapse-toggle").click();
  await page.locator(".filter-section.places .filter-item").filter({ hasText: place }).click();
  await page.waitForLoadState("networkidle");
});

When("I filter by project {string}", async ({ page }, project: string) => {
  await page.locator(".filter-section.projects .collapse-toggle").click();
  await page.locator(".filter-section.projects .filter-item").filter({ hasText: project }).click();
  await page.waitForLoadState("networkidle");
});

When("I switch to {string}", async ({ page }, tab: string) => {
  const btn = page.locator(".top-bar .tabs").getByRole("button", { name: tab });
  await btn.click();
  await expect(btn).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

When("I add a project called {string}", async ({ page }, name: string) => {
  await setFieldValue(page.locator(".add-entity-form input").first(), name);
  await page.locator(".add-entity-form button").first().click();
  await page.waitForLoadState("networkidle");
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
    await expect(taskRow.locator(".tag", { hasText: project })).toBeVisible();
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

const apiHeaders = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("an urgent task {string} was added earlier", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers: apiHeaders, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/urgency`, { headers: apiHeaders, data: { urgency: "superurgent" } });
});

Given("a normal task {string} was added later", async ({ request }, title: string) => {
  await new Promise((resolve) => setTimeout(resolve, 1500));
  await request.post("/api/tasks", { headers: apiHeaders, data: { title } });
});

Then("the {int}nd sort button should read {string}", async ({ page }, position: number, label: string) => {
  await expect(page.locator(".sort-toggle button").nth(position - 1)).toHaveText(label);
});

When("I click the {string} sort button", async ({ page }, label: string) => {
  const btn = page.locator(".sort-toggle").getByRole("button", { name: label });
  await btn.click();
  await expect(btn).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

Then(
  "{string} should appear above {string} in the task list",
  async ({ page }, above: string, below: string) => {
    await expect(async () => {
      const titles = await page.locator(".items li .item-title-text").allInnerTexts();
      const aboveIdx = titles.findIndex((t) => t.includes(above));
      const belowIdx = titles.findIndex((t) => t.includes(below));
      expect(aboveIdx).toBeGreaterThanOrEqual(0);
      expect(belowIdx).toBeGreaterThan(aboveIdx);
    }).toPass({ timeout: 10000 });
  },
);

Then("I should see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text);
});

Then("I should not see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).not.toContainText(text);
});
