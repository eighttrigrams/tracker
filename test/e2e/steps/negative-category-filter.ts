import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { apiCategorize } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// The sidebar's per-Group category lists load once at app start, and the positive
// filter is picked out of them and resolves its ids to names against them, so a
// reload after seeding is what puts the seeded categories within reach of the
// "I filter by project" step. The negative filter needs no reload: it stores the
// name off the badge it was shift-clicked on.
async function reloadForSeededCategories(page: any) {
  await page.reload();
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".top-bar .tabs")).toBeVisible();
}

// Plurama -> Tracker is the rule the negative filter has to follow: "Implied
// task" carries only the target, so it never carries the seed and can only be
// hidden by expanding the closure server-side.
Given("test data for negative filtering exists", async ({ page, request }) => {
  const plurama = await (await request.post("/api/projects", { headers, data: { name: "Plurama" } })).json();
  const tracker = await (await request.post("/api/projects", { headers, data: { name: "Tracker" } })).json();
  const other = await (await request.post("/api/projects", { headers, data: { name: "Other" } })).json();
  const home = await (await request.post("/api/places", { headers, data: { name: "Home" } })).json();

  await request.post("/api/category-rules", {
    headers,
    data: {
      "source-type": "project",
      "source-id": plurama.id,
      "target-type": "project",
      "target-id": tracker.id,
    },
  });

  const seeded = await (await request.post("/api/tasks", { headers, data: { title: "Seeded task" } })).json();
  const implied = await (await request.post("/api/tasks", { headers, data: { title: "Implied task" } })).json();
  const plain = await (await request.post("/api/tasks", { headers, data: { title: "Plain task" } })).json();

  await apiCategorize(request, `/api/tasks/${seeded.id}`, "project", plurama.id);
  await apiCategorize(request, `/api/tasks/${implied.id}`, "project", tracker.id);
  await apiCategorize(request, `/api/tasks/${plain.id}`, "project", other.id);
  await apiCategorize(request, `/api/tasks/${plain.id}`, "place", home.id);

  await reloadForSeededCategories(page);
});

Given(
  "a work-only place {string} on task {string} exists",
  async ({ request }, place: string, task: string) => {
    const created = await (await request.post("/api/places", { headers, data: { name: place } })).json();
    await request.put(`/api/places/${created.id}/scope`, { headers, data: { scope: "work" } });
    const item = await (await request.post("/api/tasks", { headers, data: { title: task } })).json();
    await apiCategorize(request, `/api/tasks/${item.id}`, "place", created.id);
  },
);

// Seeded without a reload, so the sidebar's projects list never sees this one:
// only the name the shift-click stores off the badge can carry the exclusion.
Given(
  "a project {string} on task {string} exists",
  async ({ request }, project: string, task: string) => {
    const created = await (await request.post("/api/projects", { headers, data: { name: project } })).json();
    const item = await (await request.post("/api/tasks", { headers, data: { title: task } })).json();
    await apiCategorize(request, `/api/tasks/${item.id}`, "project", created.id);
  },
);

When(
  "I shift-click the {string} badge on task {string}",
  async ({ page }, badge: string, task: string) => {
    await page
      .locator(".items li")
      .filter({ hasText: task })
      .locator(".tag", { hasText: badge })
      .first()
      .click({ modifiers: ["Shift"] });
    await page.waitForLoadState("networkidle");
  },
);

When("I press Option+Escape", async ({ page }) => {
  await page.keyboard.press("Alt+Escape");
  await page.waitForLoadState("networkidle");
});

When("I remove the excluded chip {string}", async ({ page }, name: string) => {
  await page
    .locator(".sidebar .exclusion-filters .filter-item-label")
    .filter({ hasText: name })
    .locator(".remove-item")
    .click();
  await page.waitForLoadState("networkidle");
});

Then("the sidebar should show {string} as excluded", async ({ page }, name: string) => {
  const chip = page
    .locator(".sidebar .exclusion-filters .filter-item-label")
    .filter({ hasText: name });
  await expect(chip.first()).toBeVisible();
  await expect(chip.first()).toHaveCSS("text-decoration-line", "line-through");
  await expect(page.locator(".sidebar .filter-section .collapse-toggle")).toHaveCount(0);
});

When("I clear the {string} filter group", async ({ page }, group: string) => {
  const clear = page.locator(`.sidebar .filter-section.${group} .clear-filter`);
  await clear.click();
  await expect(clear).toHaveCount(0);
  await page.waitForLoadState("networkidle");
});

// A refused shift-click must not fall through to the positive toggle, so the
// badge's own group has to come out untouched: the clear "x" renders for any
// selection, the label chip for one in a collapsed group, .active for one in an
// open group. Only assert this behind a step whose own outcome depends on the
// group being empty — the selection would render a frame after the click, so on
// its own an empty group proves nothing.
Then(
  "nothing should be selected in the {string} filter group",
  async ({ page }, group: string) => {
    const section = page.locator(`.sidebar .filter-section.${group}`);
    await expect(section.locator(".clear-filter")).toHaveCount(0);
    await expect(section.locator(".filter-item-label")).toHaveCount(0);
    await expect(section.locator(".filter-item.active")).toHaveCount(0);
  },
);

Then("the sidebar should show the category filter groups", async ({ page }) => {
  await expect(page.locator(".sidebar .exclusion-filters")).toHaveCount(0);
  // One per Category Group: People, Places, Workstreams, Projects, Goals, Assets.
  await expect(page.locator(".sidebar .filter-section .collapse-toggle")).toHaveCount(6);
});
