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

Then("I should see {string} in the task list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text);
});
