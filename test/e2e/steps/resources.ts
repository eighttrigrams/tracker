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

Given("{int} resources exist", async ({ request }, count: number) => {
  for (let i = 1; i <= count; i++) {
    const n = String(i).padStart(2, "0");
    await request.post("/api/resources", { headers, data: { title: `Resource ${n}` } });
  }
});

Given(
  "a resource {string} with description {string} exists",
  async ({ request }, title: string, description: string) => {
    const created = await (await request.post("/api/resources", { headers, data: { title } })).json();
    await request.put(`/api/resources/${created.id}`, { headers, data: { title, description } });
  },
);

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

When("I click the resources See more button", async ({ page }) => {
  await page.locator(".load-more-btn").click();
  await page.waitForLoadState("networkidle");
});

When(
  "I edit the description of resource {string} to {string}",
  async ({ page }, title: string, text: string) => {
    await page.locator(".items li").filter({ hasText: title }).locator(".item-description").click();
    await page.getByRole("textbox", { name: "Description (optional)" }).fill(text);
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".modal-overlay")).toHaveCount(0);
    await page.waitForLoadState("networkidle");
  },
);

Then("I should see {string} in the resources list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("I should see {int} resources in the list", async ({ page }, count: number) => {
  await expect(page.locator("ul.items > li")).toHaveCount(count);
});

Then("I should see the resources See more button", async ({ page }) => {
  await expect(page.locator(".load-more-btn")).toBeVisible();
});

Then("I should not see the resources See more button", async ({ page }) => {
  await expect(page.locator(".load-more-btn")).toHaveCount(0);
});

Then("I should see {string} in the expanded resource", async ({ page }, text: string) => {
  await expect(page.locator("ul.items > li.expanded .item-description")).toContainText(text, {
    timeout: 5000,
  });
});

Then("I should see a YouTube embed", async ({ page }) => {
  await expect(page.locator(".youtube-preview iframe")).toBeVisible({ timeout: 5000 });
});
