import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

function futureDateStr(daysFromNow: number = 7): string {
  const d = new Date();
  d.setDate(d.getDate() + daysFromNow);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

Given(
  "a meet {string} with start date 7 days from now exists",
  async ({ request }, title: string) => {
    const meet = await (await request.post("/api/meets", { headers, data: { title } })).json();
    await request.put(`/api/meets/${meet.id}/start-date`, {
      headers,
      data: { "start-date": futureDateStr() },
    });
  },
);

When("I expand {string} in the meets list", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".item-header").click();
  await page.waitForLoadState("networkidle");
});

When(
  "I set the start date to 7 days from now on meet {string}",
  async ({ page }, title: string) => {
    const meetRow = page.locator(".items li").filter({ hasText: title });
    await meetRow.locator(".date-picker-input").fill(futureDateStr());
    await meetRow.locator(".date-picker-input").dispatchEvent("change");
    await page.waitForLoadState("networkidle");
  },
);

Then("I should see {string} in the meets list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("the meet {string} should show a date", async ({ page }, title: string) => {
  const meetRow = page.locator(".items li").filter({ hasText: title });
  await expect(meetRow.locator(".due-date")).toBeVisible({ timeout: 5000 });
});
