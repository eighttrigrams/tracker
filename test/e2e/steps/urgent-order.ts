import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { dragCard } from "./helpers";

const { When, Then } = createBdd();

// Issue cards in the urgency blocks carry both classes, so the task selector has
// to exclude them.
const URGENT_TASKS = ".urgency-subsection.urgent .draggable-urgent-task:not(.draggable-urgent-issue)";
const URGENT_ISSUES = ".urgency-subsection.urgent .draggable-urgent-issue";
const SUPERURGENT_TASKS =
  ".urgency-subsection.superurgent .draggable-urgent-task:not(.draggable-urgent-issue)";

async function expectOrder(page: any, selector: string, csv: string) {
  const expected = csv.split(",").map((s) => s.trim());
  const items = page.locator(selector);
  await expect(items).toHaveCount(expected.length);
  for (let i = 0; i < expected.length; i++) {
    await expect(items.nth(i)).toContainText(expected[i]);
  }
}

When(
  "I drag the urgent task {string} before the urgent task {string}",
  async ({ page }, source: string, target: string) => {
    await dragCard(page, URGENT_TASKS, source, target, "before");
  },
);

When(
  "I drag the urgent issue {string} before the urgent issue {string}",
  async ({ page }, source: string, target: string) => {
    await dragCard(page, URGENT_ISSUES, source, target, "before");
  },
);

// The Issues page has a plain button group rather than the Tasks page's
// sort dropdown.
When("I click the manual sort button on the Issues page", async ({ page }) => {
  await page.locator(".sort-toggle button", { hasText: "Manual" }).click();
  await page.waitForLoadState("networkidle");
});

// What a drop wrote, so the next step can say "once". Two writes racing over one
// column is what the cross-block drag used to do, and which of them won decided
// where the card landed — so the count is the assertion that holds every run,
// where the resulting order only disagrees about half the time.
let writesDuringDrop: string[] = [];

async function recordingWrites(page: any, drag: () => Promise<void>) {
  writesDuringDrop = [];
  const listener = (req: any) => {
    if (req.method() !== "GET" && req.url().includes("/api/")) {
      writesDuringDrop.push(`${req.method()} ${new URL(req.url()).pathname}`);
    }
  };
  page.on("request", listener);
  try {
    await drag();
  } finally {
    page.off("request", listener);
  }
}

// The block dropped on decides the urgency, and the endpoint sets it together
// with the position — this is the drag that used to race itself.
When(
  "I drag the urgent task {string} after the superurgent task {string}",
  async ({ page }, source: string, target: string) => {
    await recordingWrites(page, () =>
      dragCard(page, URGENT_TASKS, source, target, "after", SUPERURGENT_TASKS),
    );
  },
);

Then("the drop made exactly one write", async () => {
  expect(writesDuringDrop).toHaveLength(1);
  expect(writesDuringDrop[0]).toMatch(/^POST \/api\/tasks\/\d+\/reorder-urgent$/);
});

Then("the urgent task list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, URGENT_TASKS, csv);
});

Then("the superurgent task list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, SUPERURGENT_TASKS, csv);
});

Then("the urgent task list is empty", async ({ page }) => {
  await expect(page.locator(URGENT_TASKS)).toHaveCount(0);
});

Then("the urgent issue list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, URGENT_ISSUES, csv);
});

Then("the issue list reads {string}", async ({ page }, csv: string) => {
  await expectOrder(page, ".items li", csv);
});
