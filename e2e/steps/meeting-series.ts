import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

function todayIsoDayNum(): string {
  const d = new Date();
  const jsDay = d.getDay();
  return String(jsDay === 0 ? 7 : jsDay);
}

function futureDateStr(): string {
  const d = new Date();
  d.setDate(d.getDate() + 7);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

Given("a meeting series {string} exists", async ({ request }, title: string) => {
  await request.post("/api/meeting-series", { headers, data: { title } });
});

Given(
  "a meeting series {string} exists with a future meeting",
  async ({ request }, title: string) => {
    const series = await (
      await request.post("/api/meeting-series", { headers, data: { title } })
    ).json();
    await request.put(`/api/meeting-series/${series.id}/schedule`, {
      headers,
      data: {
        "schedule-days": todayIsoDayNum(),
        "schedule-time": "09:00",
      },
    });
    const meetResp = await request.post(`/api/meeting-series/${series.id}/create-meeting`, {
      headers,
      data: { date: futureDateStr(), time: "09:00" },
    });
    expect(meetResp.ok()).toBeTruthy();
  },
);

When("I click the {string} button", async ({ page }, name: string) => {
  await page.getByRole("button", { name }).click();
});

When("I type {string} in the meets search field", async ({ page }, text: string) => {
  await page.locator("#meets-filter-search").fill(text);
});

When("I clear the meets search field", async ({ page }) => {
  await page.locator("#meets-filter-search").fill("");
});

When("I expand {string} in the series list", async ({ page }, title: string) => {
  await page.locator(".items li").filter({ hasText: title }).locator(".item-header").click();
});

When("I click the edit button on the expanded series", async ({ page }) => {
  await page.locator(".edit-icon").click();
});

When("I click the {string} tab in the modal", async ({ page }, tab: string) => {
  await page.locator(".edit-modal-tabs button").filter({ hasText: tab }).click();
});

When("I toggle day {string} in the schedule", async ({ page }, day: string) => {
  await page.locator(".schedule-days button").filter({ hasText: day }).click();
});

When("I click {string} in the modal", async ({ page }, label: string) => {
  await page.locator(".modal-footer button").filter({ hasText: label }).click();
});

When(
  "I click {string} for {string}",
  async ({ page }, buttonText: string, seriesTitle: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: seriesTitle })
      .getByRole("button", { name: buttonText })
      .click();
  },
);

Then("I should see {string} in the series list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text, { timeout: 5000 });
});

Then("I should see the sort mode toggle", async ({ page }) => {
  await expect(page.locator(".sort-mode-toggle")).toBeVisible();
});

Then("I should not see the sort mode toggle", async ({ page }) => {
  await expect(page.locator(".sort-mode-toggle")).not.toBeVisible();
});

Then(
  "the series {string} should have schedule days {string}",
  async ({ request }, title: string, expectedDays: string) => {
    const series = await (await request.get("/api/meeting-series")).json();
    const found = series.find((s: any) => s.title === title);
    expect(found).toBeTruthy();
    expect(found.schedule_days).toBe(expectedDays);
  },
);

Then(
  "the {string} button for {string} should be enabled",
  async ({ page }, buttonText: string, seriesTitle: string) => {
    const btn = page
      .locator(".items li")
      .filter({ hasText: seriesTitle })
      .getByRole("button", { name: buttonText });
    await expect(btn).toBeEnabled();
  },
);

Then(
  "the {string} button for {string} should be disabled",
  async ({ page }, buttonText: string, seriesTitle: string) => {
    const btn = page
      .locator(".items li")
      .filter({ hasText: seriesTitle })
      .getByRole("button", { name: buttonText });
    await expect(btn).toBeDisabled();
  },
);

