import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

function dateStr(daysOffset: number = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + daysOffset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

async function meetIdByTitle(request: any, title: string): Promise<number> {
  const meets = await (await request.get("/api/meets", { headers })).json();
  const meet = meets.find((m: any) => m.title === title);
  if (!meet) throw new Error(`No meet titled ${title}`);
  return meet.id;
}

Given("a meet {string} with start date tomorrow exists", async ({ request }, title: string) => {
  const meet = await (await request.post("/api/meets", { headers, data: { title } })).json();
  await request.put(`/api/meets/${meet.id}/start-date`, {
    headers,
    data: { "start-date": dateStr(1) },
  });
});

Given("meet {string} is marked as maybe", async ({ request }, title: string) => {
  const id = await meetIdByTitle(request, title);
  await request.put(`/api/meets/${id}/maybe`, { headers, data: { maybe: true } });
});

When("I expand the today meet {string}", async ({ page }, title: string) => {
  await page
    .locator(".today-task-item.meet-item")
    .filter({ hasText: title })
    .locator(".today-task-header")
    .click();
});

When("I toggle maybe on the today meet {string}", async ({ page }, title: string) => {
  const card = page.locator(".today-task-item.meet-item").filter({ hasText: title });
  await card.locator(".combined-dropdown-btn").click();
  await page.locator(".task-dropdown-menu .dropdown-item.toggle-maybe").click();
  await page.waitForLoadState("networkidle");
});

Then(
  "the meet {string} in the today section should be grayed",
  async ({ page }, title: string) => {
    const item = page
      .locator(".today-section.today .today-task-item.meet-item")
      .filter({ hasText: title });
    await expect(item).toHaveClass(/\bmaybe\b/, { timeout: 5000 });
  },
);

Then(
  "the meet {string} in the upcoming section should not be grayed",
  async ({ page }, title: string) => {
    const item = page
      .locator(".today-section.upcoming .today-task-item.meet-item")
      .filter({ hasText: title });
    await expect(item).toBeVisible({ timeout: 5000 });
    await expect(item).not.toHaveClass(/\bmaybe\b/);
  },
);
