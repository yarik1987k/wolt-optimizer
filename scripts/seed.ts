/**
 * Seeds the database by running the real importer on the CSV fixture
 * (no hard-coded rows). Safe to run repeatedly: the importer is idempotent.
 * Usage: npm run db:seed [-- path/to/file.csv]
 */
import { readFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { commitImport } from "../src/lib/import/service";
import { prisma } from "../src/lib/db";

async function main() {
  const file = resolve(process.argv[2] ?? "fixtures/wolt_deliveries_sorted.csv");
  const result = await commitImport(readFileSync(file, "utf8"), basename(file));
  const total = await prisma.deliveryRecord.count();
  console.log(
    `ייבוא ${basename(file)}: ${result.parsedRowCount} שורות בקובץ, ${result.insertedCount} נוספו, ${result.skippedCount} דולגו ככפילות. סה״כ רשומות במסד: ${total}`,
  );
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
