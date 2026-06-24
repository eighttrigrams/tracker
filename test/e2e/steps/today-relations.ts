import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

function todayItem(page: any, title: string) {
  return page
    .locator(".today-task-item")
    .filter({ has: page.locator(".task-title", { hasText: title }) });
}

async function findTaskByTitle(request: any, title: string): Promise<number> {
  const tasks = await (
    await request.get("/api/tasks?sort=recent", { headers })
  ).json();
  const t = tasks.find((x: any) => x.title === title);
  if (!t) throw new Error(`task not found: ${title}`);
  return t.id;
}

async function findResourceByTitle(request: any, title: string): Promise<number> {
  const resources = await (await request.get("/api/resources/", { headers })).json();
  const r = resources.find((x: any) => x.title === title);
  if (!r) throw new Error(`resource not found: ${title}`);
  return r.id;
}

function todayBadge(page: any, title: string, target: string) {
  return todayItem(page, title).locator(".tag.relation", { hasText: target });
}

Given(
  "task {string} has relation badge title {string}",
  async ({ request }, title: string, badge: string) => {
    const id = await findTaskByTitle(request, title);
    await request.put(`/api/tasks/${id}/relation-badge-title`, {
      headers,
      data: { "relation-badge-title": badge },
    });
  },
);

Given(
  "a relation links task {string} to task {string}",
  async ({ request }, src: string, tgt: string) => {
    const srcId = await findTaskByTitle(request, src);
    const tgtId = await findTaskByTitle(request, tgt);
    await request.post("/api/relations", {
      headers,
      data: {
        "source-type": "tsk",
        "source-id": srcId,
        "target-type": "tsk",
        "target-id": tgtId,
      },
    });
  },
);

Given(
  "a relation links task {string} to resource {string}",
  async ({ request }, src: string, tgt: string) => {
    const srcId = await findTaskByTitle(request, src);
    const tgtId = await findResourceByTitle(request, tgt);
    await request.post("/api/relations", {
      headers,
      data: {
        "source-type": "tsk",
        "source-id": srcId,
        "target-type": "res",
        "target-id": tgtId,
      },
    });
  },
);

Given(
  "task {string} is categorized as person {string}",
  async ({ request }, title: string, person: string) => {
    const taskId = await findTaskByTitle(request, title);
    const people = await (await request.get("/api/people/", { headers })).json();
    const p = people.find((x: any) => x.name === person);
    if (!p) throw new Error(`person not found: ${person}`);
    await request.post(`/api/tasks/${taskId}/categorize`, {
      headers,
      data: { "category-type": "person", "category-id": p.id },
    });
  },
);

Given("task {string} is done", async ({ request }, title: string) => {
  const id = await findTaskByTitle(request, title);
  await request.put(`/api/tasks/${id}/done`, { headers, data: { done: true } });
});

When("I activate relation mode", async ({ page }) => {
  await page.locator(".relation-mode-toggle").click();
  await page.waitForLoadState("networkidle");
});

When(
  "I click the link button on the today item {string}",
  async ({ page }, title: string) => {
    await todayItem(page, title).locator(".relation-link-btn").click();
    await page.waitForLoadState("networkidle");
  },
);

When(
  "I click the link button on the resource {string}",
  async ({ page }, title: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: title })
      .locator(".relation-link-btn")
      .click();
    await page.waitForLoadState("networkidle");
  },
);

Then("the relation-mode toggle should be visible", async ({ page }) => {
  await expect(page.locator(".relation-mode-toggle")).toBeVisible({ timeout: 5000 });
});

Then(
  "today item {string} should show a relation badge for {string}",
  async ({ page }, title: string, target: string) => {
    await expect(
      todayItem(page, title).locator(".tag.relation"),
    ).toContainText(target, { timeout: 5000 });
  },
);

Then(
  "today item {string} should not show a relation badge for {string}",
  async ({ page }, title: string, target: string) => {
    await expect(
      todayItem(page, title).locator(".tag.relation", { hasText: target }),
    ).toHaveCount(0, { timeout: 5000 });
  },
);

Then(
  "today item {string} relation badge for {string} shows {string}",
  async ({ page }, title: string, target: string, glyph: string) => {
    await expect(todayBadge(page, title, target)).toContainText(glyph, {
      timeout: 5000,
    });
  },
);

Then(
  "today item {string} relation badge for {string} is grayed",
  async ({ page }, title: string, target: string) => {
    await expect(todayBadge(page, title, target)).toHaveClass(/task-done/, {
      timeout: 5000,
    });
  },
);

Then(
  "today item {string} relation badge for {string} is not grayed",
  async ({ page }, title: string, target: string) => {
    await expect(todayBadge(page, title, target)).not.toHaveClass(/task-done/, {
      timeout: 5000,
    });
  },
);

Then(
  "today item {string} relation badges are stacked one per row",
  async ({ page }, title: string) => {
    const badges = todayItem(page, title).locator(".tag.relation");
    const count = await badges.count();
    expect(count).toBeGreaterThan(1);
    const boxes = [];
    for (let i = 0; i < count; i++) {
      boxes.push(await badges.nth(i).boundingBox());
    }
    boxes.sort((a: any, b: any) => a.y - b.y);
    for (let i = 1; i < boxes.length; i++) {
      const prev: any = boxes[i - 1];
      const cur: any = boxes[i];
      expect(cur.y).toBeGreaterThanOrEqual(prev.y + prev.height - 1);
      expect(Math.abs(cur.x - prev.x)).toBeLessThan(2);
    }
  },
);

Then(
  "today item {string} relation badges appear above its category tags",
  async ({ page }, title: string) => {
    const item = todayItem(page, title);
    const relations = item.locator(".tag.relation");
    const categories = item.locator(".tag:not(.relation)");
    const relCount = await relations.count();
    const catCount = await categories.count();
    expect(relCount).toBeGreaterThan(0);
    expect(catCount).toBeGreaterThan(0);
    let lowestRelationBottom = 0;
    for (let i = 0; i < relCount; i++) {
      const box: any = await relations.nth(i).boundingBox();
      lowestRelationBottom = Math.max(lowestRelationBottom, box.y + box.height);
    }
    let highestCategoryTop = Infinity;
    for (let i = 0; i < catCount; i++) {
      const box: any = await categories.nth(i).boundingBox();
      highestCategoryTop = Math.min(highestCategoryTop, box.y);
    }
    expect(highestCategoryTop).toBeGreaterThanOrEqual(lowestRelationBottom - 1);
  },
);

Then(
  "today item {string} relation badge for {string} appears before the one for {string}",
  async ({ page }, title: string, first: string, second: string) => {
    await expect(todayBadge(page, title, first)).toBeVisible({ timeout: 5000 });
    await expect(todayBadge(page, title, second)).toBeVisible({ timeout: 5000 });
    const texts = await todayItem(page, title)
      .locator(".tag.relation")
      .allTextContents();
    const firstIdx = texts.findIndex((t) => t.includes(first));
    const secondIdx = texts.findIndex((t) => t.includes(second));
    expect(firstIdx).toBeGreaterThanOrEqual(0);
    expect(secondIdx).toBeGreaterThanOrEqual(0);
    expect(firstIdx).toBeLessThan(secondIdx);
  },
);
