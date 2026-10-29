import { prisma } from "@/lib/db";

/** Import history plus a per-report reconciliation of what is stored in the DB. */
export async function getImportHistory() {
  const [batches, byReport, byType, total] = await Promise.all([
    prisma.importBatch.findMany({ orderBy: { importedAt: "desc" } }),
    prisma.deliveryRecord.groupBy({ by: ["reportNumber"], _count: true, _sum: { amountAgorot: true }, orderBy: { reportNumber: "asc" } }),
    prisma.deliveryRecord.groupBy({ by: ["type"], _count: true, _sum: { amountAgorot: true } }),
    prisma.deliveryRecord.aggregate({ _count: true, _sum: { amountAgorot: true } }),
  ]);
  return {
    batches,
    reconciliation: {
      total_records: total._count,
      total_agorot: total._sum.amountAgorot ?? 0,
      by_report: byReport.map((r) => ({ report_number: r.reportNumber, records: r._count, sum_agorot: r._sum.amountAgorot ?? 0 })),
      by_type: byType.map((r) => ({ type: r.type, records: r._count, sum_agorot: r._sum.amountAgorot ?? 0 })),
    },
  };
}
