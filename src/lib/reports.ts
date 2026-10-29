import { prisma } from "@/lib/db";

export async function listReportNumbers(): Promise<string[]> {
  const rows = await prisma.deliveryRecord.findMany({ distinct: ["reportNumber"], select: { reportNumber: true }, orderBy: { reportNumber: "asc" } });
  return rows.map((r) => r.reportNumber).filter((r): r is string => !!r);
}
