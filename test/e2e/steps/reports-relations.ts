import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { daysAgo } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const ids = new Map<string, number>();

function reportCard(page: any, title: string) {
  return page.locator(".report-item").filter({
    has: page.locator(".item-title-text", { hasText: title }),
  });
}

Given("a done report task {string} exists", async ({ request }, title: string) => {
  const task = await (
    await request.post("/api/tasks", { headers, data: { title } })
  ).json();
  await request.put(`/api/tasks/${task.id}/done`, { headers, data: { done: true } });
  await request.put(`/api/tasks/${task.id}/done-at`, {
    headers,
    data: { "done-date": daysAgo(1) },
  });
  ids.set(`tsk:${title}`, task.id);
});

Given("a past report meet {string} exists", async ({ request }, title: string) => {
  const meet = await (
    await request.post("/api/meets/", { headers, data: { title } })
  ).json();
  await request.put(`/api/meets/${meet.id}/start-date`, {
    headers,
    data: { "start-date": daysAgo(1) },
  });
  ids.set(`met:${title}`, meet.id);
});

Given(
  "a relation links report task {string} to report meet {string}",
  async ({ request }, src: string, tgt: string) => {
    await request.post("/api/relations", {
      headers,
      data: {
        "source-type": "tsk",
        "source-id": ids.get(`tsk:${src}`),
        "target-type": "met",
        "target-id": ids.get(`met:${tgt}`),
      },
    });
  },
);

When("I expand report card {string}", async ({ page }, title: string) => {
  await reportCard(page, title).locator(".item-header").click();
  await page
    .locator(".report-item.expanded")
    .filter({ has: page.locator(".item-title-text", { hasText: title }) })
    .waitFor({ state: "visible", timeout: 5000 });
});

When(
  "I remove the relation badge for {string} from report card {string}",
  async ({ page }, target: string, title: string) => {
    await reportCard(page, title)
      .locator(".relation-badges-expanded .tag.relation", { hasText: target })
      .locator(".remove-tag")
      .click();
    await page.waitForLoadState("networkidle");
  },
);

Then(
  "report item {string} shows a relation badge for {string}",
  async ({ page }, title: string, target: string) => {
    await expect(
      reportCard(page, title).locator(".tag.relation", { hasText: target }),
    ).toHaveCount(1, { timeout: 5000 });
  },
);

Then(
  "report item {string} shows no relation badge for {string}",
  async ({ page }, title: string, target: string) => {
    await expect(
      reportCard(page, title).locator(".tag.relation", { hasText: target }),
    ).toHaveCount(0, { timeout: 5000 });
  },
);
