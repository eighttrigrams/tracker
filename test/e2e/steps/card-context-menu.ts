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
async function rightClickPrevented(page: any, selector: string, title?: string) {
  return await page.evaluate(
    ({ selector, title }: any) => {
      const candidates = [...document.querySelectorAll(selector)];
      const el = title
        ? candidates.find((e) => (e as HTMLElement).innerText.includes(title))
        : candidates[0];
      if (!el) throw new Error(`no element for ${selector} / ${title}`);
      const target = title ? el.querySelector(".item-header")! : el;
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
    { selector, title },
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
