import { expect, Locator, APIRequestContext } from "@playwright/test";

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
