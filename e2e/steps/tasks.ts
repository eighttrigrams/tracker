import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

Given("I am on the app", async ({ page }) => {
  await page.goto("/");
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
    await page.locator(".items li").filter({ hasText: task }).click();
    await page.getByRole("button", { name: "+ Place" }).click();
    await page.locator(".category-selector-item").filter({ hasText: place }).click();
  },
);

When("I filter by place {string}", async ({ page }, place: string) => {
  await page.locator(".filter-section.places .collapse-toggle").click();
  await page.locator(".filter-section.places .filter-item").filter({ hasText: place }).click();
});

Then("I should see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text);
});

Then("I should not see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).not.toContainText(text);
});
