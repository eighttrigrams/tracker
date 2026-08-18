import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

const description = "ul.items > li.expanded .item-description";

// Playwright's own modifier names, so the feature's Examples table reads as the
// keys a user actually holds down.
const modifiers = ["Meta", "Control", "Shift", "Alt"] as const;
type Modifier = (typeof modifiers)[number];

// Both click steps aim at the top-left corner of the description rather than at
// its centre, which in this fixture is the markdown link itself: a plain click
// there would navigate instead of opening the editor, and a modified one would
// hand chromium a second window to clean up. The corner is the card's own click
// target and nothing else.
const corner = { position: { x: 5, y: 5 } };

function descriptionOf(page: any, title: string) {
  return page.locator(".items li").filter({ hasText: title }).locator(".item-description");
}

When("I click the description of resource {string}", async ({ page }, title: string) => {
  await descriptionOf(page, title).click(corner);
});

When(
  "I {word}-click the description of resource {string}",
  async ({ page }, modifier: string, title: string) => {
    if (!modifiers.includes(modifier as Modifier)) throw new Error(`unknown modifier ${modifier}`);
    await descriptionOf(page, title).click({ ...corner, modifiers: [modifier as Modifier] });
  },
);

// A shift-click leaves a selection behind, and chromium clears one on mouseup
// rather than on mousedown when the next click lands inside it — so at the time
// the card reads the selection that click still sees it, and the description's
// rule against opening the editor over selected text, not the modifier under
// test, is what would keep the confirming click's modal shut. Dropping the
// selection first keeps the two rules from being mistaken for each other.
When("I clear the text selection", async ({ page }) => {
  await page.evaluate(() => window.getSelection()?.removeAllRanges());
});

// The modal renders only once open-edit-modal's fetch lands, which is after the
// click returns — so an absence read straight away could be that fetch still in
// flight rather than a click that was let through. Waiting for the network to go
// quiet first is what makes this a real absence; the scenarios then follow it
// with a plain click that does open the editor, so a description that had gone
// inert cannot pass here either.
Then("no edit modal opens", async ({ page }) => {
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".edit-item-modal")).toHaveCount(0);
});

Then("the description editor holds {string}", async ({ page }, text: string) => {
  await expect(page.getByRole("textbox", { name: "Description (optional)" })).toHaveValue(text);
});

// The link in the fixture points at the app's own root, so the tab chromium opens
// loads without a network. What is asserted is that a tab opened at all: that is
// the browser acting on a cmd/ctrl-click the card no longer swallows.
//
// How far that tab has got by the time the "page" event fires is up to the
// chromium build, though, and the two this suite runs on disagree: a full
// chromium (what PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH selects) fires the event
// with the link's url already in place, while the headless shell bundled into
// Dockerfile.e2e fires it at tab creation, with the tab still on about:blank —
// whose pathname reads "blank". A url taken once, right here, is therefore not
// flaky but simply wrong on the docker target, retries included. toHaveURL
// retries until the navigation lands, which is what makes the two agree, and it
// names where the tab went rather than only that its path was a slash.
Then("cmd-clicking {string} in the description opens a new tab", async ({ page, context }, label: string) => {
  const link = page.locator(`${description} a`).filter({ hasText: label });
  const [tab] = await Promise.all([
    context.waitForEvent("page"),
    link.click({ modifiers: ["ControlOrMeta"] }),
  ]);
  await expect(tab).toHaveURL(new URL("/", page.url()).href);
  await tab.close();
});
