import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

Given(
  "an issue {string} with urgency {string} exists",
  async ({ request }, title: string, urgency: string) => {
    const issue = await (await request.post("/api/issues", { headers, data: { title } })).json();
    await request.put(`/api/issues/${issue.id}/urgency`, { headers, data: { urgency } });
  },
);

When("I set the urgency of issue {string} to superurgent", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title });
  await card.locator(".item-header").click();
  const button = card.locator(".task-urgency-selector button.superurgent");
  await expect(button).toBeVisible({ timeout: 5000 });
  await button.click();
  await page.waitForLoadState("networkidle");
});

// HTML5 drag-and-drop is not driven by Playwright's mouse-based dragTo, so we
// dispatch the native drag events ourselves, sharing one DataTransfer across
// them. A short pause after dragstart lets reagent re-render the drop target so
// its handler sees the active drag source. The source is found by its urgency
// subsection + title text (native querySelector has no :has-text).
async function fireDragOnIssue(page: any, section: string, title: string, type: string) {
  await page.evaluate(
    ({ section, title, type }: { section: string; title: string; type: string }) => {
      const el = [...document.querySelectorAll(`.urgency-subsection.${section} .draggable-urgent-issue`)].find(
        (e) => (e as HTMLElement).innerText.includes(title),
      );
      if (!el) throw new Error(`drag source issue not found: ${section} / ${title}`);
      (window as any).__dt = (window as any).__dt || new DataTransfer();
      const rect = el.getBoundingClientRect();
      el.dispatchEvent(
        new DragEvent(type, {
          bubbles: true,
          cancelable: true,
          dataTransfer: (window as any).__dt,
          clientX: rect.left + rect.width / 2,
          clientY: rect.top + rect.height / 2,
        }),
      );
    },
    { section, title, type },
  );
}

async function fireDragOnTarget(page: any, selector: string, type: string) {
  await page.evaluate(
    ({ selector, type }: { selector: string; type: string }) => {
      const el = document.querySelector(selector);
      if (!el) throw new Error(`drop target not found: ${selector}`);
      (window as any).__dt = (window as any).__dt || new DataTransfer();
      const rect = el.getBoundingClientRect();
      el.dispatchEvent(
        new DragEvent(type, {
          bubbles: true,
          cancelable: true,
          dataTransfer: (window as any).__dt,
          clientX: rect.left + rect.width / 2,
          clientY: rect.top + rect.height / 2,
        }),
      );
    },
    { selector, type },
  );
}

async function dragIssueTo(page: any, title: string, targetSelector: string) {
  await expect(
    page.locator(".urgency-subsection.urgent .draggable-urgent-issue").filter({ hasText: title }),
  ).toBeVisible({ timeout: 5000 });
  await fireDragOnIssue(page, "urgent", title, "dragstart");
  await page.waitForTimeout(250);
  await fireDragOnTarget(page, targetSelector, "dragenter");
  await fireDragOnTarget(page, targetSelector, "dragover");
  await fireDragOnTarget(page, targetSelector, "drop");
  await page.waitForTimeout(250);
  await page.waitForLoadState("networkidle");
}

When(
  "I drag the issue {string} from the urgent to the superurgent subsection",
  async ({ page }, title: string) => {
    await dragIssueTo(page, title, ".urgency-subsection.superurgent .urgency-task-list");
  },
);

When(
  "I drag the issue {string} onto the due-or-happening section",
  async ({ page }, title: string) => {
    await dragIssueTo(page, title, ".today-section.today .today-subsection:not(.other-things)");
  },
);

When("I start dragging the issue {string}", async ({ page }, title: string) => {
  await expect(
    page.locator(".urgency-subsection.urgent .draggable-urgent-issue").filter({ hasText: title }),
  ).toBeVisible({ timeout: 5000 });
  await fireDragOnIssue(page, "urgent", title, "dragstart");
  await page.waitForTimeout(250);
});

When("I drop the dragged issue on the due-or-happening section", async ({ page }) => {
  const target = ".today-section.today .today-subsection:not(.other-things)";
  await fireDragOnTarget(page, target, "dragenter");
  await fireDragOnTarget(page, target, "dragover");
  await fireDragOnTarget(page, target, "drop");
  await page.waitForTimeout(250);
  await page.waitForLoadState("networkidle");
});

Then("the Today section is shown as a disabled drop target", async ({ page }) => {
  await expect(page.locator(".today-section.today")).toHaveClass(/drag-disabled/, { timeout: 5000 });
});

Then("the Days section is shown as a disabled drop target", async ({ page }) => {
  await expect(page.locator(".day-selector")).toHaveClass(/drag-disabled/, { timeout: 5000 });
});

Then("no warning is shown", async ({ page }) => {
  await expect(page.locator(".error")).toHaveCount(0);
});

Then(
  "I should see the issue {string} in the urgent subsection",
  async ({ page }, title: string) => {
    await expect(
      page.locator(".urgency-subsection.urgent .draggable-urgent-issue").filter({ hasText: title }),
    ).toBeVisible({ timeout: 5000 });
  },
);

Then(
  "I should see the issue {string} in the superurgent subsection",
  async ({ page }, title: string) => {
    await expect(
      page.locator(".urgency-subsection.superurgent .draggable-urgent-issue").filter({ hasText: title }),
    ).toBeVisible({ timeout: 5000 });
  },
);

Then(
  "I should not see the issue {string} in the urgent subsection",
  async ({ page }, title: string) => {
    await expect(
      page.locator(".urgency-subsection.urgent .draggable-urgent-issue").filter({ hasText: title }),
    ).toHaveCount(0, { timeout: 5000 });
  },
);

Then(
  "I should not see {string} in the due-or-happening section",
  async ({ page }, text: string) => {
    await expect(
      page.locator(".today-section.today .today-subsection:not(.other-things)"),
    ).not.toContainText(text);
  },
);
