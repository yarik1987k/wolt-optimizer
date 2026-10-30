/**
 * Records Wolt's official day statistics and prints the reconciliation for a day.
 * Usage: npm run reconcile -- 2026-10-29 [official-stats.json]
 * Default stats file: fixtures/official-day-stats.json (typed in from Wolt's "Ваша статистика").
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { prisma } from "../src/lib/db";
import { agorotToShekelString } from "../src/lib/money";
import { reconcileDay, recordOfficialDay, type OfficialDay } from "../src/lib/reconcile/day";

async function main() {
  const day = process.argv[2];
  if (!day || !/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error("Usage: npm run reconcile -- YYYY-MM-DD [stats.json]");
  const statsPath = resolve(process.argv[3] ?? "fixtures/official-day-stats.json");
  const stats = JSON.parse(readFileSync(statsPath, "utf8")) as { days: OfficialDay[] };
  for (const d of stats.days) await recordOfficialDay(d);

  const r = await reconcileDay(day);
  const range = (a: number | null, b: number | null) => (a === null || b === null ? "לא ידוע" : a === b ? `${a}` : `${a}–${b}`);
  console.log(`התאמה מול Wolt — ${day}: ${r.status}`);
  console.log(`  משלוחים שהושלמו לפי Wolt: ${r.official?.completedDeliveries ?? "לא הוזן"}`);
  console.log(`  הזמנות שזוהו (חדשות לכל הצעה, בלי כפילות של הזמנה שנישאה): ${range(r.detectedOrdersLow, r.detectedOrdersHigh)}`);
  console.log(`  הצעות שהתקבלו: ${r.detectedTrips} · שרשראות משלוח: ${r.detectedChains}`);
  console.log(`  הפרש (Wolt פחות זוהה): ${r.deliveryDifference ? range(r.deliveryDifference.min, r.deliveryDifference.max) : "לא ידוע"}`);
  console.log(`  רווח בלי טיפים לפי Wolt: ₪${r.official?.earningsWithoutTipsAgorot == null ? "—" : agorotToShekelString(r.official.earningsWithoutTipsAgorot)} · סכום ההצעות שהתקבלו: ₪${agorotToShekelString(r.acceptedOfferEarningsAgorot)}`);
  console.log("ממצאים:");
  for (const f of r.findings) console.log(`  • ${f}`);
  console.log("לא נפתר:");
  for (const u of r.unresolved) console.log(`  • ${u}`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
