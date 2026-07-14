import { createBdd } from "playwright-bdd";
import { FAKE_TODAY } from "./helpers";

const { Before } = createBdd();

// Freeze the browser's wall clock to the pinned fake day so the frontend's
// notion of "today" (Today page, journal day-tabs, meets week sections, the
// reports CW label) agrees with the backend clock (et.tr.clock) and the seeded
// data. setFixedTime keeps timers running, so debounces/animations are
// unaffected. Runs before the "I am on the app" navigation. No-op when
// TRACKER_FAKE_TODAY is unset (a bare `npx playwright test`).
Before(async ({ page }) => {
  if (FAKE_TODAY) {
    await page.clock.setFixedTime(new Date(`${FAKE_TODAY}T12:00:00Z`));
  }
});
