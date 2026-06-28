import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { apiCategorize } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given("the place {string} exists", async ({ request }, name: string) => {
  await request.post("/api/places", { headers, data: { name } });
});

Given(
  "task {string} is assigned to place {string}",
  async ({ request }, taskTitle: string, placeName: string) => {
    const tasks = await (await request.get("/api/tasks?sort=recent", { headers })).json();
    const task = tasks.find((t: any) => t.title === taskTitle);
    const places = await (await request.get("/api/places", { headers })).json();
    const place = places.find((p: any) => p.name === placeName);
    await apiCategorize(request, `/api/tasks/${task.id}`, "place", place.id);
  },
);

Given(
  "meet {string} is assigned to place {string}",
  async ({ request }, meetTitle: string, placeName: string) => {
    const meets = await (await request.get("/api/meets", { headers })).json();
    const meet = meets.find((m: any) => m.title === meetTitle);
    const places = await (await request.get("/api/places", { headers })).json();
    const place = places.find((p: any) => p.name === placeName);
    await apiCategorize(request, `/api/meets/${meet.id}`, "place", place.id);
  },
);

Then(
  'the "Today" sidebar should show the {string} filter section',
  async ({ page }, sectionClass: string) => {
    await expect(page.locator(`.sidebar .filter-section.${sectionClass}`)).toBeVisible();
  },
);

Then("I should not see {string} in the today view", async ({ page }, text: string) => {
  await expect(page.locator(".today-content")).not.toContainText(text);
});

async function expectActiveFilter(page: any, section: string, name: string) {
  const item = page
    .locator(`.sidebar .filter-section.${section} .filter-item-label, .sidebar .filter-section.${section} .filter-item.active`)
    .filter({ hasText: name });
  await expect(item.first()).toBeVisible();
}

Then(
  "the {string} filter should be active in the places sidebar",
  async ({ page }, name: string) => {
    await expectActiveFilter(page, "places", name);
  },
);

Then(
  "the {string} filter should be active in the projects sidebar",
  async ({ page }, name: string) => {
    await expectActiveFilter(page, "projects", name);
  },
);

When(
  "I click the {string} badge on the today item {string}",
  async ({ page }, badge: string, item: string) => {
    await page
      .locator(".today-task-item")
      .filter({ hasText: item })
      .locator(".tag", { hasText: badge })
      .first()
      .click();
    await page.waitForLoadState("networkidle");
  },
);
