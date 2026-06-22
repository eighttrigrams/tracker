import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("a YouTube inbox message {string} exists", async ({ request }, title: string) => {
  await request.post("/api/messages", { headers, data: { sender: "YouTube", title } });
});

When("I expand the message {string}", async ({ page }, text: string) => {
  await page.locator(".items li").filter({ hasText: text }).locator(".item-header").click();
});

Then("the footer delete button has corner radius {string}", async ({ page }, radius: string) => {
  const btn = page.locator(".item-actions .delete-btn");
  await expect(btn).toHaveCSS("border-top-left-radius", radius);
  await expect(btn).toHaveCSS("border-top-right-radius", radius);
});

Then("the footer convert button has a solid right border", async ({ page }) => {
  await expect(page.locator(".item-actions-left .combined-main-btn")).toHaveCSS("border-right-style", "solid");
});

Then("the footer convert button has corner radius {string}", async ({ page }, radius: string) => {
  await expect(page.locator(".item-actions-left .combined-main-btn")).toHaveCSS("border-top-left-radius", radius);
});

Then("the footer main button has left corner radius {string}", async ({ page }, radius: string) => {
  await expect(page.locator(".item-actions-right .combined-main-btn")).toHaveCSS("border-top-left-radius", radius);
});

Then("the footer dropdown toggle has right corner radius {string}", async ({ page }, radius: string) => {
  await expect(page.locator(".item-actions-right .combined-dropdown-btn")).toHaveCSS("border-top-right-radius", radius);
});

When("I open the footer dropdown on task {string}", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".combined-dropdown-btn").click();
});

Then("I see the footer dropdown menu", async ({ page }) => {
  await expect(page.locator(".task-dropdown-menu")).toBeVisible();
});

When("I click the footer dropdown item {string}", async ({ page }, label: string) => {
  await page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: label }).click();
});

Then("I see the delete confirmation", async ({ page }) => {
  await expect(page.locator(".modal-overlay .confirm-delete")).toBeVisible();
});

Then("the recurring delete button uses the unified footer geometry", async ({ page }) => {
  const btn = page.locator(".item-actions .combined-main-btn.delete-btn");
  await expect(btn).toBeVisible();
  await expect(btn).toHaveCSS("padding-top", "4px");
  await expect(btn).toHaveCSS("padding-left", "12px");
  await expect(btn).toHaveCSS("border-top-left-radius", "8px");
  await expect(btn).toHaveCSS("border-top-right-radius", "8px");
});
