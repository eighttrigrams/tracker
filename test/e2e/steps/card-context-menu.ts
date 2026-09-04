import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

const headers = { "Content-Type": "application/json", "X-User-Id": "null" };

const menu = ".card-context-menu";
const entries = `${menu} .dropdown-item`;

Given("a resource {string} without a link exists", async ({ request }, title: string) => {
  await request.post("/api/resources", { headers, data: { title } });
});

Given("an inbox message {string} exists", async ({ request }, title: string) => {
  await request.post("/api/messages", { headers, data: { sender: "Mailer", title } });
});

Then("the message {string} is saved for later", async ({ page, request }, title: string) => {
  // The card leaves the inbox list on its way to the saved view, which is the
  // re-render this waits on before reading the row back.
  await expect(page.locator(".items li").filter({ hasText: title })).toHaveCount(0);
  const saved = await (await request.get("/api/messages?view=saved", { headers })).json();
  expect(saved.map((m: any) => m.title)).toContain(title);
});

// The toggle groups are rendered here (asserted first, so this is not an absence
// racing a render) and must not be entries: the menu is stated per card type
// through :main-actions and :menu-extra, never read off the footer's layout.
Then(
  "the footer of {string} shows the scope, importance and urgency toggle groups",
  async ({ page }, title: string) => {
    const groups = page
      .locator(".items li")
      .filter({ hasText: title })
      .first()
      .locator(".item-actions-left .toggle-group");
    await expect(groups).toHaveCount(3);
  },
);

Then("no card menu entry is one of those toggle options", async ({ page }) => {
  const options = await page.locator(".item-actions-left .toggle-group .toggle-option").allInnerTexts();
  expect(options.length).toBeGreaterThan(0);
  const labels = await page.locator(entries).allInnerTexts();
  expect(labels.length).toBeGreaterThan(0);
  expect(labels.filter((l) => options.includes(l))).toEqual([]);
});

// Alt+R (core.cljs' document keydown) leaves the tab without a mousedown
// anywhere, so the menu's outside-click dismiss stays out of it and the card is
// destroyed with its menu still open — which is the path this has to exercise.
When("I press the keyboard shortcut for the {string} tab", async ({ page }, name: string) => {
  const keys: Record<string, string> = { Resources: "Alt+R", Issues: "Alt+I", Meets: "Alt+M" };
  await page.keyboard.press(keys[name]);
  const tab = page.locator(".top-bar .tabs").getByRole("button", { name });
  await expect(tab).toHaveClass(/active/);
  await page.waitForLoadState("networkidle");
});

// The header toggles, so collapsing is the click that expanded the card — only
// asserted the other way round.
When("I collapse the task card {string}", async ({ page }, title: string) => {
  const card = page.locator(".items li").filter({ hasText: title }).first();
  await card.locator(".item-header").click();
  await expect(card).not.toHaveClass(/expanded/);
});

When("I right-click the card {string}", async ({ page }, title: string) => {
  await page
    .locator(".items li")
    .filter({ hasText: title })
    .first()
    .locator(".item-header")
    .click({ button: "right" });
});

Then("the card menu offers {string}", async ({ page }, labels: string) => {
  await expect(page.locator(entries)).toHaveText(labels.split(", "));
});

When("I click the card menu entry {string}", async ({ page }, label: string) => {
  await page.locator(entries).filter({ hasText: label }).click();
});

When("I press Escape", async ({ page }) => {
  await page.keyboard.press("Escape");
});

When("I click outside the card menu", async ({ page }) => {
  await page.locator("body").click({ position: { x: 2, y: 2 } });
});

Then("no card menu is open", async ({ page }) => {
  await expect(page.locator(menu)).toHaveCount(0);
});

// Whether the app took the right-click over is not visible in the DOM, so these
// two dispatch a cancelable contextmenu by hand and read defaultPrevented back.
// That is the assertion that actually diverges: a document-level handler — the
// thing this feature must not have — would swallow the browser's own menu
// everywhere, and the second step would see it prevented.
async function rightClickPrevented(page: any, selector: string, title?: string, inner?: string) {
  return await page.evaluate(
    ({ selector, title, inner }: any) => {
      const candidates = [...document.querySelectorAll(selector)];
      const el = title
        ? candidates.find((e) => (e as HTMLElement).innerText.includes(title))
        : candidates[0];
      if (!el) throw new Error(`no element for ${selector} / ${title}`);
      const within = inner ?? (title ? ".item-header" : null);
      const target = within ? el.querySelector(within)! : el;
      if (!target) throw new Error(`no ${within} in ${selector} / ${title}`);
      const rect = target.getBoundingClientRect();
      const event = new MouseEvent("contextmenu", {
        bubbles: true,
        cancelable: true,
        clientX: rect.left + 20,
        clientY: rect.top + 10,
      });
      target.dispatchEvent(event);
      return event.defaultPrevented;
    },
    { selector, title, inner },
  );
}

Then(
  "a right-click on the card {string} is taken over by the app",
  async ({ page }, title: string) => {
    expect(await rightClickPrevented(page, ".items li", title)).toBe(true);
    await expect(page.locator(entries).first()).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator(menu)).toHaveCount(0);
  },
);

// An open card's menu hangs off the title, so that is where this dispatches.
// It has to be the title element itself and not the header around it: a
// synthesised event goes straight to the element it is dispatched on and then
// upwards, so aiming at the header would miss a handler sitting on a child of
// it — where a real right-click at those coordinates would hit the title and
// bubble up through the header just fine.
Then(
  "a right-click on the title of {string} is taken over by the app",
  async ({ page }, title: string) => {
    expect(await rightClickPrevented(page, ".items li", title, ".item-title")).toBe(true);
    await expect(page.locator(entries).first()).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator(menu)).toHaveCount(0);
  },
);

// The open card below its header: the description, the links in it and the
// category selectors, where the native menu is what is worth having.
Then(
  "a right-click on the body of {string} is left to the browser",
  async ({ page }, title: string) => {
    expect(await rightClickPrevented(page, ".items li", title, ".item-details")).toBe(false);
    await expect(page.locator(menu)).toHaveCount(0);
  },
);

Then("a right-click on the page background is left to the browser", async ({ page }) => {
  expect(await rightClickPrevented(page, ".main-content")).toBe(false);
  await expect(page.locator(menu)).toHaveCount(0);
});

Then("the copy signal flashes", async ({ page }) => {
  await expect(page.locator("#save-flash")).toBeVisible();
});

Then("the clipboard holds {string}", async ({ page, context }, expected: string) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await expect(async () => {
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(expected);
  }).toPass({ timeout: 5000 });
});

// The open card's handle. A real right-click anywhere on the title lands here;
// `.item-title` is the element the handler sits on.
When("I right-click the title of {string}", async ({ page }, title: string) => {
  await page
    .locator(".items li")
    .filter({ hasText: title })
    .first()
    .locator(".item-title")
    .click({ button: "right" });
});

Then("the card {string} is still open", async ({ page }, title: string) => {
  await expect(page.locator(".items li").filter({ hasText: title }).first()).toHaveClass(/expanded/);
});

Then("the card {string} is collapsed", async ({ page }, title: string) => {
  await expect(page.locator(".items li").filter({ hasText: title }).first()).not.toHaveClass(/expanded/);
});
