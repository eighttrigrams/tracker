import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// With the custom keymap on, the field is a CodeMirror standing in front of a
// transparent <input> that keeps the class — so the thing that holds the text is
// `.cm-content`, and `input.inline-title-edit` is its mirror. Both are asserted
// against below: the editor for what the caret did, the mirror for the value
// every other reader of this field still sees.
const editor = ".cm-input-host .cm-content";
const mirror = "input.inline-title-edit";

When(
  "I option-click the title of task {string}",
  async ({ page }, title: string) => {
    const row = page.locator(".items li").filter({ hasText: title }).first();
    await row.locator(".item-title-text").first().click({ modifiers: ["Alt"] });
  },
);

Then("the in-place title editor should be open", async ({ page }) => {
  await expect(page.locator(editor)).toBeVisible();
  // Focused, not merely mounted: every chord below is aimed at the document,
  // and a box that opened without taking the keyboard would swallow them.
  await expect(page.locator(editor)).toBeFocused();
});

Then("the in-place title editor should be closed", async ({ page }) => {
  await expect(page.locator(".cm-input-host")).toHaveCount(0);
  await expect(page.locator(mirror)).toHaveCount(0);
});

When(
  "I type {string} in the in-place title editor",
  async ({ page }, text: string) => {
    await page.locator(editor).pressSequentially(text);
  },
);

Then(
  "the in-place title editor should read {string}",
  async ({ page }, text: string) => {
    await expect(page.locator(editor)).toHaveText(text);
    // The mirrored <input> is written on every document change, and things
    // outside this component read the field through it — so it has to agree.
    await expect(page.locator(mirror)).toHaveValue(text);
  },
);

// Cmd+J is cursor-left in the scheme's one-line layout. Pressed n times rather
// than once with a count, because what is being pinned is that each press moves
// the caret by one — a binding that fired once and then stopped would pass a
// single-press assertion.
When(
  "I press the cursor-left chord {int} times",
  async ({ page }, times: number) => {
    for (let i = 0; i < times; i++) await page.keyboard.press("Meta+KeyJ");
  },
);

When("I press the save combo in the in-place title editor", async ({ page }) => {
  await page.keyboard.press("Meta+Digit9");
  await page.waitForLoadState("networkidle");
});

When("I press Escape in the in-place title editor", async ({ page }) => {
  await page.keyboard.press("Escape");
});
