import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a done task {string} exists", async ({ request }, title: string) => {
  const task = await (await request.post("/api/tasks", { headers, data: { title } })).json();
  await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
});

When("I expand task {string}", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".item-header").click();
  await page.waitForLoadState("networkidle");
});

When("I click the done button on task {string}", async ({ page }, title: string) => {
  const taskRow = page.locator(".items li").filter({ hasText: title });
  await taskRow.locator(".combined-main-btn.done").click();
  await page.waitForLoadState("networkidle");
});

When("I click the undone button on task {string}", async ({ page }, title: string) => {
  const taskRow = page.locator(".items li").filter({ hasText: title });
  await taskRow.locator(".combined-main-btn.undone").click();
  const responsePromise = page.waitForResponse(
    (resp) => resp.url().includes("/done") && resp.request().method() === "PUT",
  );
  await page.locator(".modal-overlay").getByRole("button", { name: "Reopen" }).click();
  await responsePromise;
  await page.waitForLoadState("networkidle");
});

When("I switch to sort mode {string}", async ({ page }, mode: string) => {
  await page.locator(".sort-toggle button").filter({ hasText: mode }).click();
  await page.waitForLoadState("networkidle");
});

Then("the done tasks API returns no results", async ({ page }) => {
  const resp = await page.evaluate(async () => {
    const r = await fetch("/api/tasks?sort=done");
    return r.json();
  });
  expect(resp).toHaveLength(0);
});
