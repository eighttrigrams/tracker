import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue, selectSidebarFilter } from "./helpers";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

// No Group is named in this file. constants/category-groups renders one
// .filter-section per Group into every page's sidebar, so reading the sidebar is
// reading that constant — the seeding, the filtering and the assertions all size
// themselves to whatever Groups the client has. Spelling the six out here would
// be the very mistake these scenarios exist to catch.
let groupKeys: string[] = [];
// The seeded category per Group key, as the API returned it — {id,
// category_type, …}. `category_type` comes from the server rather than from a
// key->type table written out here, for the same reason.
let seeded: Record<string, any> = {};

const seedName = (key: string) => `Inh-${key}`;

// The list endpoint each add path's Item comes back from. The key is the URL
// segment the client posts its categorize to, so a wrong segment at a call site
// shows up as a missing category rather than as a passing test.
const listUrl: Record<string, string> = {
  tasks: "/api/tasks?sort=recent",
  issues: "/api/issues",
  meets: "/api/meets",
  "meeting-series": "/api/meeting-series",
  "recurring-tasks": "/api/recurring-tasks",
  resources: "/api/resources",
  journals: "/api/journals",
};

// Which input the title goes into, per add path. Every one of these forms adds
// on its button; only some add on plain Enter (the recurring form wants
// Alt+Enter), so the button is what these steps click.
const searchInput: Record<string, string> = {
  tasks: "#tasks-filter-search",
  issues: "#issues-filter-search",
  meets: "#meets-filter-search",
  "meeting-series": "#meets-filter-search",
  "recurring-tasks": "#tasks-filter-search",
  resources: "#resources-filter-search",
  // Journals mode is the Resources page, so it is the resources input — the id
  // is shared and the two forms are never mounted together.
  journals: "#resources-filter-search",
};

// Counted from the moment an add step starts, so "six Groups selected sends six
// posts" is asserted rather than assumed — an enumeration that posts for a Group
// with nothing selected would show up here and nowhere else.
let categorizePosts: string[] = [];

async function readGroupKeys(page: any): Promise<string[]> {
  const classes: string[] = await page
    .locator(".sidebar .filter-section")
    .evaluateAll((els: Element[]) => els.map((e) => e.getAttribute("class") ?? ""));
  const keys = classes
    .map((c) =>
      c
        .split(/\s+/)
        .filter((token) => token && token !== "filter-section" && token !== "exclusion-filter")[0],
    )
    .filter(Boolean);
  expect(keys.length, "no category filter sections in the sidebar").toBeGreaterThan(0);
  return keys;
}

function countCategorizePosts(page: any) {
  categorizePosts = [];
  page.on("request", (req: any) => {
    if (req.method() === "POST" && req.url().includes("/categorize")) {
      categorizePosts.push(`${new URL(req.url()).pathname} ${req.postData() ?? ""}`);
    }
  });
  page.on("response", (res: any) => {
    if (res.request().method() === "POST" && res.url().includes("/categorize") && !res.ok()) {
      categorizePosts.push(`FAILED ${res.status()} ${new URL(res.url()).pathname}`);
    }
  });
}

async function readItem(request: any, collection: string, title: string) {
  const items = await (await request.get(listUrl[collection], { headers })).json();
  return items.find((i: any) => i.title === title);
}

// The Item is created by a POST the click fired, so it can arrive a beat after
// the step that clicked returned. Polling for its existence — never for its
// categories, which is what is under test — keeps that timing out of the
// assertions.
async function fetchItem(request: any, collection: string, title: string) {
  let item: any;
  await expect(async () => {
    item = await readItem(request, collection, title);
    expect(item, `no ${collection} item titled "${title}"`).toBeTruthy();
  }).toPass({ timeout: 10000 });
  return item;
}

Given("a category exists in every Group", async ({ page, request }) => {
  groupKeys = await readGroupKeys(page);
  seeded = {};
  for (const key of groupKeys) {
    const res = await request.post(`/api/${key}`, { headers, data: { name: seedName(key) } });
    expect(res.ok(), `POST /api/${key} failed`).toBeTruthy();
    seeded[key] = await res.json();
  }
});

// So the Issue survives its own page's filters: an Item that carries none of
// the selected categories is not in the filtered list, and the affordance under
// test is on its card.
Given(
  "the issue {string} carries the seeded category of every Group",
  async ({ request }, title: string) => {
    const issue = await fetchItem(request, "issues", title);
    for (const key of groupKeys) {
      const res = await request.post(`/api/issues/${issue.id}/categorize`, {
        headers,
        data: { "category-type": seeded[key].category_type, "category-id": seeded[key].id },
      });
      expect(res.ok(), `categorize ${key} failed`).toBeTruthy();
    }
  },
);

When("I filter by the seeded category in every Group", async ({ page }) => {
  for (const key of groupKeys) {
    await selectSidebarFilter(page, key, seedName(key));
  }
  // Every section still shows its clear button, i.e. all the filters are up at
  // once when the Item is added. Without this a filter that quietly failed to
  // apply would read as a category the add path dropped.
  for (const key of groupKeys) {
    await expect(
      page.locator(`.sidebar .filter-section.${key} .clear-filter`),
      `the ${key} filter is not active`,
    ).toBeVisible();
  }
});

When(
  "I filter by the seeded category in the {string} Group",
  async ({ page }, key: string) => {
    expect(groupKeys, `${key} is not one of the client's Groups`).toContain(key);
    await selectSidebarFilter(page, key, seedName(key));
  },
);

// The add fires the categorize posts from the POST's own handler and then a
// refetch 500ms later, so the wait is for both to have gone out before anything
// is counted or read back.
When("I add {string} on the {string} page", async ({ page }, title: string, collection: string) => {
  countCategorizePosts(page);
  await setFieldValue(page.locator(searchInput[collection]), title);
  await page.locator(".combined-search-add-form button").first().click();
  // The Journals form asks for a schedule type before it creates anything. Keyed
  // off the modal being on screen rather than off a list of collections that open
  // one, so a second form that asks something needs no entry here.
  const scheduleChoice = page.locator(".modal .schedule-mode-selector .toggle-option").first();
  if (await scheduleChoice.isVisible().catch(() => false)) {
    await scheduleChoice.click();
  }
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(1500);
});

Then(
  "the {string} item {string} carries a category from every Group",
  async ({ request }, collection: string, title: string) => {
    let missing: string[] = [];
    await expect(async () => {
      const item = await fetchItem(request, collection, title);
      missing = groupKeys.filter((key) => (item[key] ?? []).length === 0);
      expect(
        missing,
        `${title} lost these Groups: ${missing.join(", ")} — posts seen: ${categorizePosts.join(" | ")}`,
      ).toEqual([]);
    }).toPass({ timeout: 10000 });
  },
);

Then(
  "the {string} item {string} carries the seeded category of the {string} Group",
  async ({ request }, collection: string, title: string, key: string) => {
    await expect(async () => {
      const item = await fetchItem(request, collection, title);
      const names = (item[key] ?? []).map((c: any) => c.name);
      expect(names).toContain(seedName(key));
    }).toPass({ timeout: 10000 });
  },
);

Then(
  "the {string} item {string} carries no categories",
  async ({ request }, collection: string, title: string) => {
    const item = await fetchItem(request, collection, title);
    const present = groupKeys.filter((key) => (item[key] ?? []).length > 0);
    expect(present, `${title} picked up categories in ${present.join(", ")}`).toEqual([]);
  },
);

Then("one categorize request was sent per Group", async () => {
  expect(categorizePosts.length, `posts: ${categorizePosts.join(", ")}`).toBe(groupKeys.length);
});

Then("exactly {int} categorize request was sent", async ({}, n: number) => {
  expect(categorizePosts.length, `posts: ${categorizePosts.join(", ")}`).toBe(n);
});

Then("exactly {int} categorize requests were sent", async ({}, n: number) => {
  expect(categorizePosts.length, `posts: ${categorizePosts.join(", ")}`).toBe(n);
});
