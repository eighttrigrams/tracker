import { expect, Locator, APIRequestContext } from "@playwright/test";

// Single "today" for all e2e date math. When TRACKER_FAKE_TODAY (yyyy-MM-dd) is
// set (Makefile `e2e` target), the whole suite — backend clock (et.tr.clock),
// the browser clock (Before hook in _hooks.ts), and these seeds — is anchored
// to that pinned day so date-sensitive specs are weekday-independent. Unset (a
// bare `npx playwright test`) falls back to the real clock. Noon UTC keeps the
// date stable under ± day arithmetic regardless of container timezone.
export const FAKE_TODAY = process.env.TRACKER_FAKE_TODAY;

function baseDate(): Date {
  if (FAKE_TODAY) return new Date(`${FAKE_TODAY}T12:00:00Z`);
  const d = new Date();
  d.setUTCHours(12, 0, 0, 0);
  return d;
}

export function offsetDateStr(daysOffset: number): string {
  const d = baseDate();
  d.setUTCDate(d.getUTCDate() + daysOffset);
  return d.toISOString().slice(0, 10);
}

export const today = () => offsetDateStr(0);
export const daysAgo = (n: number) => offsetDateStr(-n);

export const latestPastDayThisWeek = () => {
  const daysSinceMonday = (baseDate().getUTCDay() + 6) % 7;
  return offsetDateStr(daysSinceMonday === 0 ? 0 : -1);
};

export const previousWeekDay = () => {
  const daysSinceMonday = (baseDate().getUTCDay() + 6) % 7;
  return offsetDateStr(-daysSinceMonday - 5);
};

export async function setFieldValue(locator: Locator, value: string) {
  await expect(async () => {
    await locator.fill(value);
    await expect(locator).toHaveValue(value, { timeout: 1000 });
  }).toPass({ timeout: 10000 });
}

// HTML5 drag-and-drop is not driven by Playwright's mouse-based dragTo, so the
// native events are dispatched by hand, sharing one DataTransfer. clientY
// decides the side: the app reads the upper half of the target as "before" and
// the lower half as "after". Looking the element up and dispatching on it has to
// happen in one page.evaluate — a drop can take the card out of the list it came
// from, and a check from the test side would race that re-render. Waiting for
// the source's "dragging" class after dragstart lets reagent re-render the drop
// targets so their handlers see the drag; a drop before that render is a no-op.
async function dispatchDrag(page: any, selector: string, title: string, type: string, frac: number) {
  await page.evaluate(
    ({ selector, title, type, frac }: any) => {
      const el = [...document.querySelectorAll(selector)].find((e) =>
        (e as HTMLElement).innerText.includes(title),
      );
      if (!el) throw new Error(`drag element not found: ${selector} / ${title}`);
      (window as any).__dt = (window as any).__dt || new DataTransfer();
      const rect = el.getBoundingClientRect();
      el.dispatchEvent(
        new DragEvent(type, {
          bubbles: true,
          cancelable: true,
          dataTransfer: (window as any).__dt,
          clientX: rect.left + rect.width / 2,
          clientY: rect.top + rect.height * frac,
        }),
      );
    },
    { selector, title, type, frac },
  );
}

// A gesture is not finished when the page goes quiet. waitForLoadState
// ("networkidle") only promises the page made no requests for 500ms, and it can
// resolve before playwright has even registered a request the drop handler
// started — so a step could return with a write still in flight. That is
// invisible to page-side assertions, which retry, but not to the ones that read
// the API through the `request` fixture: that context is not synchronised with
// the page at all, so it can read the row before the write commits. One drop
// fires two writes at once (set-task-today and acknowledge-task-reminder, see
// views/today.cljs handle-add-to-day-drop), which is what made the window wide
// enough to lose.
//
// So watch the writes the gesture actually fires and wait for their responses:
// a write's response is only sent after the server has committed it, which is
// the exact point the assertions depend on. A gesture that writes nothing
// collects nothing and resolves immediately, so a refused drop does not hang.
// The loop re-checks because a landing write can trigger another one.
export async function withWritesSettled(page: any, gesture: () => Promise<void>) {
  const writes: Promise<unknown>[] = [];
  const onRequest = (r: any) => {
    if (r.method() !== "GET" && r.url().includes("/api/")) {
      writes.push(r.response().catch(() => null));
    }
  };
  page.on("request", onRequest);
  try {
    await gesture();
    await page.waitForLoadState("networkidle");
    for (let seen = -1; seen !== writes.length; ) {
      seen = writes.length;
      await Promise.all(writes);
    }
  } finally {
    page.off("request", onRequest);
  }
}

// targetSelector differs from selector when the drop crosses lists — dragging
// between the two urgency blocks, say.
export async function dragCard(
  page: any,
  selector: string,
  source: string,
  target: string,
  position: "before" | "after",
  targetSelector: string = selector,
) {
  await page.evaluate(() => {
    (window as any).__dt = new DataTransfer();
  });
  await expect(page.locator(selector).filter({ hasText: source })).toBeVisible({ timeout: 5000 });
  await dispatchDrag(page, selector, source, "dragstart", 0.5);
  await expect(page.locator(".dragging").filter({ hasText: source })).toBeVisible({ timeout: 5000 });
  const frac = position === "before" ? 0.25 : 0.75;
  await withWritesSettled(page, async () => {
    for (const type of ["dragenter", "dragover", "drop"]) {
      await dispatchDrag(page, targetSelector, target, type, frac);
    }
  });
  await dispatchDrag(page, selector, source, "dragend", 0.5);
}

// Activate a sidebar category filter (places/projects/…) robustly. The picker
// starts collapsed, so expand it, then click the item. The sidebar re-renders
// as categories load in and as the selected item bumps to the top of the
// most-recently-modified ordering, which can swallow the click; retry until the
// filter is actually applied — the "x" clear button only renders once a filter
// is active — so downstream steps never race an un-applied filter.
export async function selectSidebarFilter(page: any, section: string, name: string) {
  const sec = page.locator(`.filter-section.${section}`);
  if ((await sec.locator(".filter-item").count()) === 0) {
    await sec.locator(".collapse-toggle").click();
  }
  const clear = sec.locator(".clear-filter");
  await expect(async () => {
    if (await clear.isVisible()) return;
    await sec.locator(".filter-item").filter({ hasText: name }).click({ timeout: 3000 });
    await expect(clear).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 15000 });
  await page.waitForLoadState("networkidle");
}

const apiHeaders = { "Content-Type": "application/json", "X-User-Id": "null" };

// A category badge as the API hands it back: exactly {id, name, badge_title}, one
// list per Group on every Item. Recognising it by that shape is how the check
// below finds the category without a category-type -> plural-key table. There was
// such a table here and it named four of the six Groups, so apiCategorize with a
// "workstream" or an "asset" looked up undefined, found nothing, and threw
// "did not persist" at a categorize that had in fact persisted.
const CATEGORY_FIELDS = ["id", "name", "badge_title"];
const isCategoryList = (v: unknown): v is Array<{ id: number }> =>
  Array.isArray(v) &&
  v.every((c) => c && typeof c === "object" && Object.keys(c).every((k) => CATEGORY_FIELDS.includes(k)));

// POSTs the categorize and reads the item back once to confirm it landed. The
// read-back used to sit in a retry loop, added when categorize writes were
// rarely lost under the full suite; the cause was the shared in-memory DB
// connection interleaving transactions, fixed since by giving every request its
// own connection (see et.tr.db/init-conn). Retrying would hide a regression of
// that, so a single check that fails loudly at the setup step is what we keep.
export async function apiCategorize(
  request: APIRequestContext,
  itemUrl: string,
  categoryType: string,
  categoryId: number,
) {
  await request.post(`${itemUrl}/categorize`, {
    headers: apiHeaders,
    data: { "category-type": categoryType, "category-id": categoryId },
  });
  const item = await (await request.get(itemUrl, { headers: apiHeaders })).json();
  const attached = Object.values(item)
    .filter(isCategoryList)
    .some((list) => list.some((c) => c.id === categoryId));
  if (!attached) {
    throw new Error(`apiCategorize: ${categoryType}:${categoryId} did not persist on ${itemUrl}`);
  }
}
