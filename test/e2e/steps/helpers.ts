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

export async function setFieldValue(locator: Locator, value: string) {
  await expect(async () => {
    await locator.fill(value);
    await expect(locator).toHaveValue(value, { timeout: 1000 });
  }).toPass({ timeout: 10000 });
}

const apiHeaders = { "Content-Type": "application/json", "X-User-Id": "null" };
const categoryKey: Record<string, string> = {
  person: "people",
  place: "places",
  project: "projects",
  goal: "goals",
};

export async function apiCategorize(
  request: APIRequestContext,
  itemUrl: string,
  categoryType: string,
  categoryId: number,
) {
  const key = categoryKey[categoryType];
  const attached = async () => {
    const item = await (await request.get(itemUrl, { headers: apiHeaders })).json();
    return (item[key] ?? []).some((c: any) => c.id === categoryId);
  };
  for (let attempt = 1; attempt <= 8; attempt++) {
    await request.post(`${itemUrl}/categorize`, {
      headers: apiHeaders,
      data: { "category-type": categoryType, "category-id": categoryId },
    });
    if (await attached()) {
      if (attempt > 1) console.log(`apiCategorize: recovered ${itemUrl} ${categoryType}:${categoryId} after ${attempt} attempts`);
      return;
    }
    console.log(`apiCategorize: MISS ${itemUrl} ${categoryType}:${categoryId} attempt ${attempt}`);
  }
  throw new Error(`apiCategorize: failed to persist ${categoryType}:${categoryId} on ${itemUrl}`);
}
