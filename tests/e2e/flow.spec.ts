import { expect, test } from "@playwright/test";
import { resolve } from "node:path";

const FIXTURE = resolve(__dirname, "../../fixtures/wolt_deliveries_sorted.csv");

test.describe.configure({ mode: "serial" });

test("empty dashboard asks for an import", async ({ page }) => {
  await page.goto("/");
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
  await page.goto("/");
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

  await page.goto("/");
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
  for (const path of ["/", "/deliveries", "/imports", "/shifts", "/experiments", "/settings"]) {
    await page.goto(path);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow, `horizontal overflow on ${path}`).toBeLessThanOrEqual(0);
  }
});
