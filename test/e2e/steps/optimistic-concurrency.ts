import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue } from "./helpers";

const { When, Then } = createBdd();

const apiHeaders = { "Content-Type": "application/json", "X-User-Id": "null" };

async function taskIdByTitle(request: any, title: string): Promise<number> {
  const tasks = await (await request.get("/api/tasks", { headers: apiHeaders })).json();
  const match = tasks.find((t: any) => t.title === title);
  if (!match) throw new Error(`no task titled "${title}"`);
  return match.id;
}

// The item-edit-form holds several inputs (title, tags, …); the title is first.
const modalTitle = ".edit-item-modal .item-edit-form input";

When("I open the edit modal for task {string}", async ({ page }, title: string) => {
  const row = page.locator(".items li").filter({ hasText: title }).first();
  await row.locator(".item-header").click();
  await row.locator(".edit-icon.description-placeholder").click();
  await expect(page.locator(".edit-item-modal")).toBeVisible();
});

// Simulates another tab / the API writing the row while our modal is open:
// a plain PUT (no expected-modified-at) that bumps modified_at server-side.
// modified_at has 1-second resolution (an accepted tradeoff), so a conflict is
// only detectable when the competing write lands in a strictly later second
// than the modal's open-time fetch. We wait past a full second first so the
// out-of-band write is deterministically newer — otherwise the "stale" save
// would legitimately match the unchanged timestamp and never conflict.
When(
  "the task {string} is changed to {string} out of band",
  async ({ request }, title: string, newTitle: string) => {
    await new Promise((resolve) => setTimeout(resolve, 1100));
    const id = await taskIdByTitle(request, title);
    const resp = await request.put(`/api/tasks/${id}`, {
      headers: apiHeaders,
      data: { title: newTitle, description: "", tags: "" },
    });
    expect(resp.ok()).toBeTruthy();
  },
);

When(
  "I change the modal title to {string} and save",
  async ({ page }, newTitle: string) => {
    await setFieldValue(page.locator(modalTitle).first(), newTitle);
    await page.locator(".edit-item-modal .modal-footer .confirm").click();
    await page.waitForLoadState("networkidle");
  },
);

Then("the conflict banner should be visible", async ({ page }) => {
  await expect(page.locator(".error")).toBeVisible();
});

Then("the conflict banner should not be visible", async ({ page }) => {
  await expect(page.locator(".error")).toHaveCount(0);
});

Then(
  "the modal title field should show {string}",
  async ({ page }, value: string) => {
    await expect(page.locator(modalTitle).first()).toHaveValue(value);
  },
);
