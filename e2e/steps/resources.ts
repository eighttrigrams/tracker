import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given(
  "a resource {string} with link {string} exists",
  async ({ request }, title: string, link: string) => {
    await request.post("/api/resources", { headers, data: { title, link } });
  },
);

Given("a YouTube resource exists", async ({ request }) => {
  await request.post("/api/resources", {
    headers,
    data: { title: "Test Video", link: "https://www.youtube.com/watch?v=dQw4w9WgXcQ" },
  });
});

When("I type {string} in the resources search field", async ({ page }, text: string) => {
  await page.locator("#resources-filter-search").fill(text);
});

When("I click the resources add button", async ({ page }) => {
  await page.locator(".combined-search-add-form button").first().click();
  await page.waitForLoadState("networkidle");
});

When("I expand resource {string}", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".item-header").click();
  await page.waitForLoadState("networkidle");
});

When("I expand the first resource", async ({ page }) => {
  await page.locator(".items li .item-header").first().click();
  await page.waitForLoadState("networkidle");
});

Then("I should see {string} in the resources list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("I should see a YouTube embed", async ({ page }) => {
  await expect(page.locator(".youtube-preview iframe")).toBeVisible({ timeout: 5000 });
});
