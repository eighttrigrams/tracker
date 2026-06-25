import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a person {string} exists", async ({ request }, name: string) => {
  await request.post("/api/people", { headers, data: { name } });
});

Given("a place {string} exists", async ({ request }, name: string) => {
  await request.post("/api/places", { headers, data: { name } });
});

Given("a project {string} exists", async ({ request }, name: string) => {
  await request.post("/api/projects", { headers, data: { name } });
});

Given("a goal {string} exists", async ({ request }, name: string) => {
  await request.post("/api/goals", { headers, data: { name } });
});

When("I click the {string} category tab", async ({ page }, name: string) => {
  const tab = page.locator(".tabs").getByRole("button", { name });
  await tab.click();
  await expect(tab).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

When("I add a category entry called {string}", async ({ page }, name: string) => {
  const input = page.locator(".combined-search-add-form input");
  await setFieldValue(input, name);
  await page.locator(".combined-search-add-form button").first().click();
  await expect(page.locator(".category-cards-grid")).toContainText(name);
  await expect(input).toHaveValue("");
});

When("I expand the card {string}", async ({ page }, name: string) => {
  await page.locator(".category-card-header").filter({ hasText: name }).click();
  await page.waitForLoadState("networkidle");
});

When("I click the edit pencil button", async ({ page }) => {
  await page.locator(".category-card.expanded .edit-icon.description-placeholder").click();
  await page.waitForLoadState("networkidle");
});

Then("the left nav should show category tabs", async ({ page }) => {
  const tabs = page.locator(".tabs");
  await expect(tabs).toContainText("People");
  await expect(tabs).toContainText("Places");
  await expect(tabs).toContainText("Projects");
  await expect(tabs).toContainText("Goals");
});

Then("the left nav should show the normal tabs", async ({ page }) => {
  const tabs = page.locator(".tabs");
  await expect(tabs).toContainText("Tasks");
});

Then("the {string} button should be active", async ({ page }, name: string) => {
  const btn = page.getByRole("button", { name });
  await expect(btn).toHaveClass(/active/);
});

Then("I should see {string} in the category cards", async ({ page }, name: string) => {
  await expect(page.locator(".category-cards-grid")).toContainText(name);
});

Then("the card {string} should be expanded", async ({ page }, name: string) => {
  const card = page.locator(".category-card").filter({ hasText: name });
  await expect(card).toHaveClass(/expanded/);
});

Then("I should see the edit pencil button", async ({ page }) => {
  await expect(
    page.locator(".category-card.expanded .edit-icon.description-placeholder")
  ).toBeVisible();
});

Then("the category edit modal should be open with {string}", async ({ page }, name: string) => {
  const modal = page.locator(".modal, [role='dialog']").first();
  await expect(modal).toBeVisible();
  await expect(modal.locator("input").first()).toHaveValue(name);
});
