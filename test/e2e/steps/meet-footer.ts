import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

When("I expand the upcoming meet {string}", async ({ page }, title: string) => {
  await page
    .locator(".today-section.upcoming .today-task-item.meet-item")
    .filter({ hasText: title })
    .locator(".today-task-header")
    .click();
});

When("I open the meet footer dropdown on {string}", async ({ page }, title: string) => {
  await page
    .locator(".today-task-item.meet-item")
    .filter({ hasText: title })
    .locator(".combined-dropdown-btn")
    .click();
});

When("I click the meet footer anchor button", async ({ page }) => {
  await page.locator(".item-actions-right .combined-main-btn").click();
  await page.waitForLoadState("networkidle");
});

Then("the meet footer anchor button shows {string}", async ({ page }, label: string) => {
  await expect(page.locator(".item-actions-right .combined-main-btn")).toContainText(label);
});

Then("the meet footer anchor button is blue", async ({ page }) => {
  await expect(page.locator(".item-actions-right .combined-main-btn")).toHaveCSS(
    "border-color",
    "rgb(0, 122, 255)",
  );
});

Then("the meet footer has no flat archive button", async ({ page }) => {
  await expect(page.locator(".archive-meet-btn")).toHaveCount(0);
});

Then("the meet footer dropdown shows {string}", async ({ page }, label: string) => {
  await expect(
    page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: label }),
  ).toBeVisible();
});

Then("the meet footer dropdown delete item is red", async ({ page }) => {
  await expect(
    page.locator(".task-dropdown-menu .dropdown-item").filter({ hasText: "Delete" }),
  ).toHaveCSS("color", "rgb(255, 59, 48)");
});

Then("the today section should no longer show {string}", async ({ page }, text: string) => {
  await expect(page.locator(".today-section.today")).not.toContainText(text, { timeout: 5000 });
});

Then("the meet footer is a standalone delete button", async ({ page }) => {
  const btn = page.locator(".item-actions-right .combined-main-btn.standalone.delete-btn");
  await expect(btn).toBeVisible();
  await expect(btn).toHaveCSS("border-top-left-radius", "8px");
  await expect(btn).toHaveCSS("border-top-right-radius", "8px");
  await expect(page.locator(".item-actions-right .combined-dropdown-btn")).toHaveCount(0);
});
