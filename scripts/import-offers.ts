/**
 * Imports a Wolt Offer Collector export and (optionally) a file of manual verifications.
 * Usage: npm run offers:import -- [export.json] [verifications.json]
 * Defaults: the real fixture export and fixtures/offer-verifications.json.
 */
import { readFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { prisma } from "../src/lib/db";
import { applyVerifications, importCollectorExport, type VerificationEntry } from "../src/lib/offers/import";

async function main() {
  const exportPath = resolve(process.argv[2] ?? "fixtures/offer-collector-export-2026-10-29.json");
  const verPath = resolve(process.argv[3] ?? "fixtures/offer-verifications.json");
  const r = await importCollectorExport(readFileSync(exportPath, "utf8"), basename(exportPath));
  console.log(
    `ייבוא ${basename(exportPath)}: ${r.screenEventCount} צילומי מסך, ${r.offerScreenCount} כרטיסי הצעה, ${r.newObservationCount} חדשים, ${r.skippedObservationCount} כבר יובאו. הצעות במסד: ${r.offersTotal}`,
  );
  console.log(
    `מיקום: ${r.shiftSessionCount} משמרות Collector (${r.newShiftCount} חדשות), ${r.locationSampleCount} דגימות מיקום (${r.newLocationSampleCount} חדשות, ${r.locationSampleCount - r.newLocationSampleCount} כבר יובאו), ${r.deliveryEventCount} אירועי משלוח, ${r.deliveryTripCount} נסיעות`,
  );
  const ver = JSON.parse(readFileSync(verPath, "utf8")) as { verifications: VerificationEntry[] };
  for (const v of await applyVerifications(ver.verifications)) {
    console.log(v.offerId ? `אומתה ידנית: ₪${(v.entry.amountAgorot / 100).toFixed(2)}` : `לא נמצאה הצעה תואמת לאימות: ₪${(v.entry.amountAgorot / 100).toFixed(2)}`);
  }
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
