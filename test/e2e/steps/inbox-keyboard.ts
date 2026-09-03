import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { setFieldValue } from "./helpers";

const { When, Then } = createBdd();

const addBox = "#mail-add-input";
const addButton = ".mail-add-form button";

// The Inbox add box has no clear-x to wait on, unlike the combined search-add
// bars. The Add button is the equivalent honest signal: it carries the same
// `disabled?` the key handler closes over, so once it is enabled the handler is
// looking at the typed text rather than at the empty box it rendered with.
async function readyToAdd(page: any) {
  await expect(page.locator(addButton)).toBeEnabled();
}

When("I type {string} in the inbox add box", async ({ page }, text: string) => {
  await setFieldValue(page.locator(addBox), text);
  await readyToAdd(page);
});

// Both variants are sent because which one is the combo depends on the user's
// keymap — Digit9 with it, KeyS without. The one that does not match falls
// through to nothing. See et.tr.ui.keys/save-combo?.
async function pressSaveCombo(page: any) {
  await page.locator(addBox).focus();
  await page.keyboard.press("Meta+KeyS");
  await page.keyboard.press("Meta+Digit9");
  await page.waitForLoadState("networkidle");
}

When("I press the save combo in the inbox", async ({ page }) => {
  await pressSaveCombo(page);
});

// Same keys, but the box is empty, so there is no enabled Add button to wait
// for and nothing should happen.
When("I press the save combo in the inbox, expecting nothing", async ({ page }) => {
  await expect(page.locator(addButton)).toBeDisabled();
  await pressSaveCombo(page);
});

When("I press Enter in the inbox add box", async ({ page }) => {
  await page.locator(addBox).press("Enter");
  await page.waitForLoadState("networkidle");
});

Then("the inbox add box should be empty", async ({ page }) => {
  await expect(page.locator(addBox)).toHaveValue("");
});

Then("I should see {string} in the message list", async ({ page }, text: string) => {
  await expect(page.locator(".items")).toContainText(text);
});

Then("the message list should be empty", async ({ page }) => {
  await expect(page.locator(".empty-message")).toBeVisible();
});
