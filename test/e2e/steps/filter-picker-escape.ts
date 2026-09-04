import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// Whether the app left the browser a default action is not visible in the DOM,
// so it has to be read off the event itself — and the obvious way does not work
// here. A listener on `window` never fires, because the picker's handler calls
// `stopPropagation` and React's own listener sits on the root container, below
// document and window: the same stopPropagation that this scenario is about
// also keeps any outer listener from ever seeing the key. (That was the first
// version of this step, and it failed with the fix in place.)
//
// So: keep a reference to the event from a *capture* listener, which runs
// top-down before anything can stop it, and read `defaultPrevented` off the
// retained object after the dispatch has finished. The flag stays true if
// preventDefault was called anywhere along the way.
When(
  "I press Option+Shift+Escape in the {string} picker",
  async ({ page }, group: string) => {
    await page.evaluate(() => {
      (window as any).__lastEscape = null;
      document.addEventListener(
        "keydown",
        (e: KeyboardEvent) => {
          if (e.code === "Escape") (window as any).__lastEscape = e;
        },
        true,
      );
    });
    await page.locator(`#tasks-filter-${group}`).focus();
    await page.keyboard.press("Alt+Shift+Escape");
    await page.waitForLoadState("networkidle");
  },
);

Then("the keystroke should have been consumed by the app", async ({ page }) => {
  await expect
    .poll(
      () =>
        page.evaluate(() => {
          const e = (window as any).__lastEscape;
          return e ? e.defaultPrevented : "no Escape keydown was seen at all";
        }),
      { timeout: 5000 },
    )
    .toBe(true);
});

Then("the page search box should be empty", async ({ page }) => {
  await expect(page.locator("#tasks-filter-search")).toHaveValue("");
});
