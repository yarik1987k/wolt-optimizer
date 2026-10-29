import { z } from "zod";
import type { Prisma } from "@prisma/client";
import { prisma } from "@/lib/db";
import { buildRecordWhere, type Filters } from "@/lib/analytics/filters";
import { agorotToShekelString } from "@/lib/money";
import { RECORD_TYPE_LABELS, type RecordType } from "@/lib/types";

export const SORTS = {
  amount_desc: [{ amountAgorot: "desc" }, { eventTimestampLocal: "asc" }],
  amount_asc: [{ amountAgorot: "asc" }, { eventTimestampLocal: "asc" }],
  date_desc: [{ eventTimestampLocal: "desc" }],
  date_asc: [{ eventTimestampLocal: "asc" }],
} satisfies Record<string, Prisma.DeliveryRecordOrderByWithRelationInput[]>;
export type SortKey = keyof typeof SORTS;
export const sortSchema = z.enum(Object.keys(SORTS) as [SortKey, ...SortKey[]]).catch("amount_desc");

export const PAGE_SIZE = 50;

export async function listDeliveries(filters: Filters, sort: SortKey = "amount_desc", page = 1, pageSize = PAGE_SIZE) {
  const where = buildRecordWhere(filters);
  const [total, rows, sum] = await Promise.all([
    prisma.deliveryRecord.count({ where }),
    prisma.deliveryRecord.findMany({
      where,
      orderBy: SORTS[sort],
      skip: (Math.max(1, page) - 1) * pageSize,
      take: pageSize,
      include: { shiftLinks: { select: { shiftId: true } } },
    }),
    prisma.deliveryRecord.aggregate({ where, _sum: { amountAgorot: true } }),
  ]);
  return { total, page, pageSize, sort, filtered_sum_agorot: sum._sum.amountAgorot ?? 0, rows };
}

function csvCell(v: string | number | boolean) {
  const s = String(v);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export async function exportDeliveriesCsv(filters: Filters, sort: SortKey = "amount_desc"): Promise<string> {
  const rows = await prisma.deliveryRecord.findMany({ where: buildRecordWhere(filters), orderBy: SORTS[sort] });
  const header = ["תאריך", "שעה", "בית העסק", "סכום ללא מעמ", "סוג רשומה", "הערות", "קובץ מקור", "מקבץ", "מספר דוח"];
  const lines = rows.map((r) =>
    [
      r.eventDate.split("-").reverse().join("."),
      r.eventTime,
      r.merchantRaw,
      agorotToShekelString(r.amountAgorot),
      RECORD_TYPE_LABELS[r.type as RecordType],
      r.notes,
      r.sourceFileName,
      r.isBundle ? "כן" : "לא",
      r.reportNumber ?? "",
    ]
      .map(csvCell)
      .join(","),
  );
  return "﻿" + [header.join(","), ...lines].join("\n") + "\n";
}
