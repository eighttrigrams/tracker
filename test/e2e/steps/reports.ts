import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("report data with categorized items exists", async ({ request }) => {
  const alice = await (await request.post("/api/people", { headers, data: { name: "Alice" } })).json();
  const apollo = await (await request.post("/api/projects", { headers, data: { name: "Apollo" } })).json();
  await request.post("/api/places", { headers, data: { name: "Office" } });

  const task = await (await request.post("/api/tasks", { headers, data: { title: "Buy paint" } })).json();
  await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
  await request.post(`/api/tasks/${task.id}/categorize`, {
    headers, data: { "category-type": "person", "category-id": alice.id },
  });
  await request.post(`/api/tasks/${task.id}/categorize`, {
    headers, data: { "category-type": "project", "category-id": apollo.id },
  });

  const journal = await (await request.post("/api/journals", { headers, data: { title: "Daily log" } })).json();
  const entry = await (await request.post(`/api/journals/${journal.id}/create-entry`, {
    headers, data: { date: new Date().toISOString().slice(0, 10) },
  })).json();
  await request.post(`/api/journal-entries/${entry.id}/categorize`, {
    headers, data: { "category-type": "person", "category-id": alice.id },
  });
  await request.post(`/api/journal-entries/${entry.id}/categorize`, {
    headers, data: { "category-type": "project", "category-id": apollo.id },
  });

  const meet = await (await request.post("/api/meets/", { headers, data: { title: "Standup" } })).json();
  const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  await request.put(`/api/meets/${meet.id}/start-date`, { headers, data: { "start-date": yesterday } });
  await request.post(`/api/meets/${meet.id}/categorize`, {
    headers, data: { "category-type": "person", "category-id": alice.id },
  });
  await request.post(`/api/meets/${meet.id}/categorize`, {
    headers, data: { "category-type": "project", "category-id": apollo.id },
  });
});

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
