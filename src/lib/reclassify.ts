import { prisma } from "@/lib/db";
import { classifyRow } from "@/lib/import/classify";
import { getRules } from "@/lib/settings";

/** Re-applies the current rules to every record that was not manually classified. */
export async function reclassifyAll() {
  const rules = await getRules();
  const rows = await prisma.deliveryRecord.findMany({ where: { typeManuallySet: false } });
  let changed = 0;
  for (const r of rows) {
    const type = classifyRow(r, rules);
    if (type !== r.type) {
      await prisma.deliveryRecord.update({ where: { id: r.id }, data: { type, typeAutoClassified: type } });
      changed++;
    }
  }
  return { checked: rows.length, changed };
}
