import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { offsetDateStr } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const dayStr = (offset: number) => offsetDateStr(offset);

const createMeetOn = async (request: any, title: string, date: string) => {
  const meet = await (await request.post("/api/meets", { headers, data: { title } })).json();
  await request.put(`/api/meets/${meet.id}/start-date`, { headers, data: { "start-date": date } });
};

Given(
  "a meet {string} starting {int} days from now exists",
  async ({ request }, title: string, days: number) => {
    await createMeetOn(request, title, dayStr(days));
  },
);

Given(
  "a meet {string} that started {int} days ago exists",
  async ({ request }, title: string, days: number) => {
    await createMeetOn(request, title, dayStr(-days));
  },
);

When("I switch the meets sort to past", async ({ page }) => {
  await page.getByRole("button", { name: "Past", exact: true }).click();
  await page.waitForLoadState("networkidle");
});

When("I click the meets See more button", async ({ page }) => {
  await page.locator(".load-more-btn").click();
  await page.waitForLoadState("networkidle");
});

When("meets responses are delayed", async ({ page }) => {
  await page.route("**/api/meets**", async (route) => {
    await new Promise((r) => setTimeout(r, 400));
    await route.continue();
  });
});

When("the first meets See more append fails", async ({ page }) => {
  let failed = false;
  await page.route("**/api/meets**", async (route) => {
    if (!failed && /weekOffset=[1-9]/.test(route.request().url())) {
      failed = true;
      await route.abort();
    } else {
      await route.continue();
    }
  });
});

When("I rapid-double-click the meets See more button", async ({ page }) => {
  const btn = page.locator(".load-more-btn");
  await btn.click();
  await btn.click();
  await page.waitForLoadState("networkidle");
});

Then("I should see {string} in the meets weeks", async ({ page }, text: string) => {
  await expect(page.locator(".meets-page .report-weeks")).toContainText(text, { timeout: 5000 });
});

Then("I should not see {string} in the meets weeks", async ({ page }, text: string) => {
  await expect(page.locator(".meets-page .report-weeks .items li").filter({ hasText: text })).toHaveCount(0);
});

Then("I should see the meets See more button", async ({ page }) => {
  await expect(page.locator(".load-more-btn")).toBeVisible();
});

Then("I should not see the meets See more button", async ({ page }) => {
  await expect(page.locator(".load-more-btn")).toHaveCount(0);
});
