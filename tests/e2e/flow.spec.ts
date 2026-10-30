import { expect, test } from "@playwright/test";
import { resolve } from "node:path";

const FIXTURE = resolve(__dirname, "../../fixtures/wolt_deliveries_sorted.csv");
const OFFER_EXPORT = resolve(__dirname, "../../fixtures/offer-collector-export-2026-10-29.json");

test.describe.configure({ mode: "serial" });

test("empty dashboard asks for an import", async ({ page }) => {
  await page.goto("/dashboard");
  await expect(page.getByText("עדיין לא יובאו נתונים")).toBeVisible();
});

test("import fixture with preview + confirm, then re-import is deduplicated", async ({ page }) => {
  await page.goto("/imports");
  await page.getByTestId("csv-input").setInputFiles(FIXTURE);
  await page.getByRole("button", { name: "תצוגה מקדימה" }).click();
  await expect(page.getByTestId("import-preview")).toBeVisible();
  await expect(page.getByTestId("preview-unique")).toHaveText("44");
  await expect(page.getByTestId("preview-new")).toHaveText("44");
  await expect(page.getByText("₪1,925.36").first()).toBeVisible();
  await page.getByRole("button", { name: /אשר ייבוא של 44/ }).click();
  await expect(page.getByTestId("import-result")).toContainText("44 נוספו");

  // Second import of the same file.
  await page.getByTestId("csv-input").setInputFiles(FIXTURE);
  await page.getByRole("button", { name: "תצוגה מקדימה" }).click();
  await expect(page.getByTestId("preview-new")).toHaveText("0");
  await expect(page.getByText("קובץ בתוכן זהה כבר יובא")).toBeVisible();
  await page.getByRole("button", { name: /אשר ייבוא/ }).click();
  await expect(page.getByTestId("import-result")).toContainText("0 נוספו");
  await expect(page.getByTestId("import-result")).toContainText("44 נמנעו מכפילות");
  await expect(page.getByTestId("reconciliation")).toContainText("44");
  await expect(page.getByTestId("reconciliation")).toContainText("₪1,925.36");
});

test("dashboard KPIs match control totals; no-shift KPIs show 'אין נתונים'", async ({ page }) => {
  await page.goto("/dashboard");
  await expect(page.getByTestId("kpi-delivery-income")).toContainText("₪1,518.36");
  await expect(page.getByTestId("kpi-delivery-count")).toContainText("41");
  await expect(page.getByTestId("kpi-avg")).toContainText("₪37.03");
  await expect(page.getByTestId("kpi-extras")).toContainText("₪407.00");
  await expect(page.getByTestId("kpi-per-hour")).toContainText("אין נתונים");
  await expect(page.getByTestId("kpi-per-km")).toContainText("אין נתונים");
  await expect(page.getByTestId("kpi-per-hour")).not.toContainText("₪0.00");
  await expect(page.getByTestId("insight-card").first()).toBeVisible();
});

test("records table sorted by amount desc; extras separable by filter", async ({ page }) => {
  await page.goto("/deliveries");
  const amounts = page.getByTestId("delivery-row").getByTestId("amount");
  await expect(amounts).toHaveCount(44);
  await expect(amounts.first()).toHaveText("₪300.00");
  await page.getByTestId("type-filter").selectOption("DELIVERY");
  await page.getByRole("button", { name: "סנן" }).click();
  await expect(amounts).toHaveCount(41);
  await expect(amounts.first()).toHaveText("₪71.42");
  await page.getByTestId("type-filter").selectOption("BONUS,ADJUSTMENT");
  await page.getByRole("button", { name: "סנן" }).click();
  await expect(amounts).toHaveCount(3);
});

test("shift logging, confirmed linking and experiment from an insight", async ({ page }) => {
  await page.goto("/shifts");
  await page.locator('input[name="date"]').fill("2026-10-21");
  await page.locator('input[name="startTime"]').fill("19:00");
  await page.locator('input[name="endTime"]').fill("22:00");
  await page.locator('input[name="areaText"]').fill("ראשון לציון");
  await expect(page.getByTestId("duration-preview")).toContainText("3.00 שעות");
  await page.getByRole("button", { name: "שמור משמרת" }).click();
  const card = page.getByTestId("shift-card").first();
  await expect(card.getByTestId("shift-duration")).toContainText("3.00");

  await card.getByRole("button", { name: "שיוך רשומות" }).click();
  await expect(card.getByTestId("link-panel")).toBeVisible();
  await card.getByRole("button", { name: /אשר שיוך של \d+ רשומות/ }).click();
  await expect(card.getByText(/שויכו \d+ רשומות/)).toBeVisible();

  // Overnight shift crosses midnight.
  await page.locator('input[name="date"]').first().fill("2026-10-22");
  await page.locator('input[name="startTime"]').first().fill("22:00");
  await page.locator('input[name="endTime"]').first().fill("01:00");
  await expect(page.getByTestId("duration-preview").first()).toContainText("3.00 שעות");
  await expect(page.getByTestId("duration-preview").first()).toContainText("חוצה חצות");

  await page.goto("/dashboard");
  await expect(page.getByTestId("kpi-per-hour")).not.toContainText("אין נתונים");
  await page.getByTestId("insight-card").first().getByRole("button", { name: "בדוק במשמרת" }).click();
  await expect(page).toHaveURL(/\/experiments\?id=/);
  const detail = page.getByTestId("experiment-detail");
  await expect(detail).toBeVisible();
  await detail.getByRole("combobox", { name: "קבוצה" }).first().selectOption("CONTROL");
  await expect(detail.getByTestId("verdict")).toHaveText("לא ניתן לקבוע שיפור");
});

test("pages fit a 375px phone screen without horizontal page scroll", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  for (const path of ["/plan", "/review", "/learn", "/route", "/advanced", "/dashboard", "/deliveries", "/imports", "/shifts", "/experiments", "/settings", "/offers", "/simulator", "/hotspots"]) {
    await page.goto(path);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow, `horizontal overflow on ${path}`).toBeLessThanOrEqual(0);
  }
});

test("Phase 2: import the real Collector export; re-import adds nothing; manual verification needs a source", async ({ page }) => {
  await page.goto("/offers");
  await page.getByTestId("offer-json-input").setInputFiles(OFFER_EXPORT);
  await page.getByRole("button", { name: "ייבוא הצעות" }).click();
  await expect(page.getByTestId("offer-import-result")).toContainText("8 כרטיסי הצעה");
  await expect(page.getByTestId("offer-import-result")).toContainText("סה״כ הצעות שנצפו: 2");
  await expect(page.getByTestId("offer-amount")).toHaveText(["₪35.02", "₪27.49"]);
  await expect(page.getByTestId("offers-count")).toContainText("2");
  await expect(page.getByTestId("offers-avg")).toContainText("₪31.26");
  await expect(page.getByTestId("thresholds")).toContainText("100%");

  // Same file again: no new observations, still two offers.
  await page.getByTestId("offer-json-input").setInputFiles(OFFER_EXPORT);
  await page.getByRole("button", { name: "ייבוא הצעות" }).click();
  await expect(page.getByTestId("offer-import-result")).toContainText("0 חדשים, 8 כבר יובאו");
  await expect(page.getByTestId("offer-row")).toHaveCount(2);

  // Imported offers are NOT verified; verifying needs a typed source.
  await expect(page.getByText("מאומתת ידנית")).toHaveCount(0);
  await page.getByTestId("verify-offer").first().click();
  await page.getByRole("button", { name: "אשר" }).click();
  await expect(page.getByText(/מחייב לציין מקור/)).toBeVisible();
  await page.getByPlaceholder("מקור האימות (מה ראית)").fill("ראיתי את ההצעה ב-Wolt, הסכום והמרחק תואמים");
  await page.getByRole("button", { name: "אשר" }).click();
  await expect(page.getByText("מאומתת ידנית")).toHaveCount(1);
  await expect(page.getByTestId("offer-hypotheses")).toContainText("מדגם קטן — לא להמלצה");
});

test("Phase 2: simulator — math-only without time, feasibility with time", async ({ page }) => {
  await page.goto("/simulator");
  // Preset from the reports (ex-VAT): 6 deliveries × ₪37.03.
  await expect(page.getByTestId("gross")).toHaveText("₪222.18");
  await expect(page.getByTestId("feasibility")).toHaveText("סימולציה מתמטית בלבד");
  await expect(page.getByTestId("needed")).toHaveText("11");
  await page.locator('input[name="deliveries"]').fill("11");
  await expect(page.getByTestId("gross")).toHaveText("₪407.33");
  await expect(page.getByTestId("gap")).toContainText("היעד הושג");
  await page.locator('input[name="minutes"]').fill("20");
  await expect(page.getByTestId("feasibility")).toHaveText("נכנס בזמן המשמרת (לפי הזמן שהוזן)");
  await page.locator('input[name="minutes"]').fill("25");
  await expect(page.getByTestId("feasibility")).toHaveText("חורג מזמן המשמרת");
});

test("Hotspots: without location data the page shows the empty state (no map, no numbers)", async ({ page }) => {
  await page.goto("/hotspots");
  await expect(page.getByRole("heading", { name: "מפת הזדמנויות", level: 1 })).toBeVisible();
  await expect(page.getByTestId("hotspots-empty")).toContainText("עדיין אין נתוני מיקום");
  await expect(page.getByTestId("hotspots-empty")).toContainText("משמרות שהופעלו ב-");
  await expect(page.getByTestId("opportunity-map")).toHaveCount(0);
  await expect(page.getByTestId("shift-planner")).toHaveCount(0);
  // The old areas page now lives under "מתקדם".
  await expect(page.getByRole("navigation", { name: "ניווט ראשי" }).getByRole("link", { name: "מתקדם" })).toHaveAttribute("aria-current", "page");
});

test("Hotspots v2: map, time window, area details and comparison (synthetic location data, test DB only)", async ({ page }) => {
  // The real fixture's offers (09:40, 09:49 Israel time) + a SYNTHETIC location layer, written only for this test.
  const { readFileSync, writeFileSync, mkdtempSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const exp = JSON.parse(readFileSync(OFFER_EXPORT, "utf8"));
  const A = { cell_id: "g500:7123:6559", cell_center_lat: 31.99577, cell_center_lon: 34.7588 };
  const B = { cell_id: "g500:7125:6560", cell_center_lat: 32.00475, cell_center_lon: 34.76409 };
  const smp = (id: string, shift: string, at: string, trigger: string, cell: typeof A, state = "UNKNOWN", ev = "UNKNOWN") => ({
    id, shift_id: shift, observed_at_utc: at, trigger, state_candidate: state, state_evidence: ev, source: trigger === "PERIODIC" ? "PERIODIC" : "EVENT",
    status: "OK", unknown_reason: null, ...cell, accuracy_m: 9, fix_age_s: 1, provider: "fused",
  });
  exp.shift_sessions = [
    { id: "e2e-morning", started_at_utc: "2026-10-29T07:30:00.000Z", ended_at_utc: "2026-10-29T08:00:00.000Z", end_reason: "USER", location_consent: true },
    { id: "e2e-evening", started_at_utc: "2026-10-29T17:00:00.000Z", ended_at_utc: "2026-10-29T17:20:00.000Z", end_reason: "USER", location_consent: true },
  ];
  exp.location_samples = [
    smp("m1", "e2e-morning", "2026-10-29T07:30:00.000Z", "SHIFT_START", A),
    smp("m2", "e2e-morning", "2026-10-29T07:40:27.000Z", "OFFER_PRESENTED", A, "OFFER_PRESENTED", "OBSERVED"),
    smp("m3", "e2e-morning", "2026-10-29T07:49:28.000Z", "OFFER_PRESENTED", A, "OFFER_PRESENTED", "OBSERVED"),
    smp("e1", "e2e-evening", "2026-10-29T17:00:00.000Z", "SHIFT_START", B),
    smp("e2", "e2e-evening", "2026-10-29T17:05:00.000Z", "AVAILABLE_SCREEN", B, "AVAILABLE", "ESTIMATED"),
  ];
  const file = resolve(mkdtempSync(resolve(tmpdir(), "e2e-hotspots-")), "export-with-location.json");
  writeFileSync(file, JSON.stringify(exp));

  await page.goto("/offers");
  await page.getByTestId("offer-json-input").setInputFiles(file);
  await page.getByRole("button", { name: "ייבוא הצעות" }).click();
  await expect(page.getByTestId("offer-import-result")).toBeVisible();

  await page.goto("/hotspots");
  await expect(page.getByTestId("opportunity-map")).toBeVisible();
  await expect(page.getByTestId("map-legend")).toContainText("הצעות שנצפו");
  await expect(page.getByTestId("data-coverage")).toContainText("2 משמרות");
  await expect(page.getByTestId("data-coverage")).toContainText("הכנסה בפועל לשעה תופיע רק אחרי משלוחים שהושלמו");
  const list = page.getByRole("heading", { name: "כל האזורים בחלון הזמן" }).locator("..").getByRole("button");
  await expect(list).toHaveCount(2);

  // Details: the morning area has the 2 offers, but no rate (verified time < 30 min) and no income.
  await expect(page.getByTestId("area-details")).toContainText("הצעות");
  await expect(page.getByTestId("area-details")).toContainText("אין משלוחים שהושלמו");
  await expect(page.getByTestId("area-details")).toContainText("אי אפשר לחשב");

  // Compare the two areas.
  await page.getByTestId("compare-toggle").click();
  await list.nth(1).click();
  await page.getByTestId("compare-toggle").click();
  await expect(page.getByTestId("area-compare")).toContainText("גודל המדגם");
  await expect(page.getByTestId("area-compare")).toContainText("אף אזור לא מוכרז כמומלץ");

  // Time window 18–20 keeps only the evening area; metric choice is kept in the URL.
  await page.getByTestId("metric-select").selectOption("mean");
  await expect(page).toHaveURL(/metric=mean/);
  await page.getByTestId("time-presets").getByRole("link", { name: "18–20" }).click();
  await expect(page).toHaveURL(/from=18&to=20/);
  await expect(page.getByRole("heading", { name: "כל האזורים בחלון הזמן" }).locator("..").getByRole("button")).toHaveCount(1);
  await expect(page.getByTestId("shift-planner")).toContainText("עדיין לא פעיל");
});
