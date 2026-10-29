import { prisma } from "@/lib/db";
import { getRules } from "@/lib/settings";
import type { RecordType } from "@/lib/types";
import { dataQualityFindings, parseWoltCsv, type DataQualityFinding, type ParsedRow } from "./csv";
import { contentHash } from "./deduplicate";

export interface ReportSummary {
  reportKey: string; // report number, or file name when no number is detected
  sourceFiles: string[];
  rowCount: number; // unique rows after in-file dedup
  totalAgorot: number;
}

export interface ImportPreview {
  fileName: string;
  sourceHash: string;
  identicalFileImportedAt: string | null;
  parsedRowCount: number;
  uniqueRowCount: number;
  inFileDuplicateCount: number; // rows that are copies of other rows in the same upload (e.g. a duplicated report)
  alreadyImportedCount: number;
  /** Rows without a report number whose content matches an existing record (e.g. a renamed export). */
  possibleDuplicateCount: number;
  newRowCount: number;
  countsByType: Record<RecordType, number>;
  totalAgorot: number;
  deliveryAgorot: number;
  reports: ReportSummary[];
  existingReportsInDb: string[];
  findings: DataQualityFinding[];
  hasErrors: boolean;
  rows: (ParsedRow & { status: RowStatus })[];
}

export type RowStatus = "NEW" | "DUPLICATE_IN_FILE" | "ALREADY_IMPORTED" | "POSSIBLE_DUPLICATE";

/** Report-independent identity of a row's content. */
const contentKey = (r: { eventDate: string; eventTime: string; amountAgorot: number; merchantRaw: string; notes: string }) =>
  [r.eventDate, r.eventTime, r.amountAgorot, r.merchantRaw.trim(), r.notes.trim()].join("\u001f");

export async function previewImport(text: string, fileName: string): Promise<ImportPreview> {
  const rules = await getRules();
  const parsed = parseWoltCsv(text, fileName, rules);
  const findings = dataQualityFindings(parsed);
  const sourceHash = contentHash(text);

  const seen = new Set<string>();
  const keys = [...new Set(parsed.rows.map((r) => r.externalRowKey))];
  const existing = new Set(
    (await prisma.deliveryRecord.findMany({ where: { externalRowKey: { in: keys } }, select: { externalRowKey: true } })).map(
      (r) => r.externalRowKey,
    ),
  );
  const rows = parsed.rows.map((r): ParsedRow & { status: RowStatus } => {
    let status: RowStatus = "NEW";
    if (seen.has(r.externalRowKey)) status = "DUPLICATE_IN_FILE";
    else if (existing.has(r.externalRowKey)) status = "ALREADY_IMPORTED";
    seen.add(r.externalRowKey);
    return { ...r, status };
  });

  // Without a report number the key depends on the file name, so a renamed export would get
  // new keys. Rows whose content matches existing records are held back for a manual decision.
  const unidentified = rows.filter((r) => r.status === "NEW" && !r.reportNumber);
  if (unidentified.length) {
    const candidates = await prisma.deliveryRecord.findMany({
      where: { eventTimestampLocal: { in: [...new Set(unidentified.map((r) => r.eventTimestampLocal))] } },
      select: { eventDate: true, eventTime: true, amountAgorot: true, merchantRaw: true, notes: true },
    });
    const remaining = new Map<string, number>();
    for (const c of candidates) remaining.set(contentKey(c), (remaining.get(contentKey(c)) ?? 0) + 1);
    for (const r of unidentified) {
      const left = remaining.get(contentKey(r)) ?? 0;
      if (left > 0) {
        r.status = "POSSIBLE_DUPLICATE";
        remaining.set(contentKey(r), left - 1);
      }
    }
  }
  const possibleDuplicates = rows.filter((r) => r.status === "POSSIBLE_DUPLICATE");
  if (possibleDuplicates.length)
    findings.unshift({
      level: "warning",
      message: `${possibleDuplicates.length} שורות ללא מספר דוח זהות בתוכנן לרשומות קיימות (ייתכן שזה אותו דוח בשם קובץ אחר). הן לא ייובאו אלא אם תאשר זאת במפורש.`,
      csvLines: possibleDuplicates.map((r) => r.csvLine),
    });
  const unique = rows.filter((r) => r.status !== "DUPLICATE_IN_FILE");

  const countsByType: Record<RecordType, number> = { DELIVERY: 0, BONUS: 0, ADJUSTMENT: 0, UNKNOWN: 0 };
  for (const r of unique) countsByType[r.type]++;

  const reportMap = new Map<string, ReportSummary>();
  for (const r of rows) {
    const key = r.reportNumber ?? r.sourceFileName;
    if (!reportMap.has(key)) reportMap.set(key, { reportKey: key, sourceFiles: [], rowCount: 0, totalAgorot: 0 });
    const rep = reportMap.get(key)!;
    if (!rep.sourceFiles.includes(r.sourceFileName)) rep.sourceFiles.push(r.sourceFileName);
    if (r.status !== "DUPLICATE_IN_FILE") {
      rep.rowCount++;
      rep.totalAgorot += r.amountAgorot;
    }
  }

  const reportNumbers = [...reportMap.keys()];
  const existingReportsInDb = (
    await prisma.deliveryRecord.findMany({
      where: { reportNumber: { in: reportNumbers } },
      distinct: ["reportNumber"],
      select: { reportNumber: true },
    })
  ).map((r) => r.reportNumber!);
  if (existingReportsInDb.length)
    findings.unshift({
      level: "warning",
      message: `דוחות שכבר קיימים במערכת: ${existingReportsInDb.join(", ")}. שורות שכבר יובאו ידולגו; לא נוצר דוח חדש.`,
    });

  const identical = await prisma.importBatch.findFirst({ where: { sourceHash }, orderBy: { importedAt: "asc" } });
  if (identical)
    findings.unshift({ level: "warning", message: `קובץ בתוכן זהה כבר יובא (${identical.sourceFileName}). ייבוא חוזר לא יכפיל שורות.` });

  return {
    fileName,
    sourceHash,
    identicalFileImportedAt: identical?.importedAt.toISOString() ?? null,
    parsedRowCount: rows.length,
    uniqueRowCount: unique.length,
    inFileDuplicateCount: rows.length - unique.length,
    alreadyImportedCount: rows.filter((r) => r.status === "ALREADY_IMPORTED").length,
    possibleDuplicateCount: possibleDuplicates.length,
    newRowCount: rows.filter((r) => r.status === "NEW").length,
    countsByType,
    totalAgorot: unique.reduce((a, r) => a + r.amountAgorot, 0),
    deliveryAgorot: unique.filter((r) => r.type === "DELIVERY").reduce((a, r) => a + r.amountAgorot, 0),
    reports: [...reportMap.values()],
    existingReportsInDb,
    findings,
    hasErrors: parsed.errors.length > 0,
    rows,
  };
}

export interface ImportResult {
  batchId: string;
  parsedRowCount: number;
  insertedCount: number;
  skippedCount: number;
  skippedInFile: number;
  skippedAlreadyImported: number;
  skippedPossibleDuplicates: number;
}

export class ImportValidationError extends Error {}

/**
 * Idempotent: rows whose key already exists (or repeats within the upload) are skipped.
 * Possible duplicates (see previewImport) are skipped unless `includePossibleDuplicates`.
 */
export async function commitImport(
  text: string,
  fileName: string,
  opts: { includePossibleDuplicates?: boolean } = {},
): Promise<ImportResult> {
  const preview = await previewImport(text, fileName);
  if (preview.hasErrors) {
    throw new ImportValidationError("הקובץ מכיל שורות לא תקינות. תקן את הקובץ וייבא שוב — לא יובאו שורות חלקיות.");
  }
  if (preview.parsedRowCount === 0) throw new ImportValidationError("לא נמצאו שורות לייבוא");
  const toInsert = preview.rows.filter((r) => r.status === "NEW" || (opts.includePossibleDuplicates && r.status === "POSSIBLE_DUPLICATE"));
  const skippedPossible = opts.includePossibleDuplicates ? 0 : preview.possibleDuplicateCount;
  return prisma.$transaction(async (tx) => {
    const batch = await tx.importBatch.create({
      data: {
        sourceFileName: fileName,
        sourceHash: preview.sourceHash,
        reportNumbers: preview.reports.map((r) => r.reportKey).join(","),
        rowCount: preview.parsedRowCount,
        insertedCount: 0,
        skippedCount: preview.parsedRowCount - toInsert.length,
      },
    });
    let inserted = 0;
    for (const r of toInsert) {
      // Guard against a concurrent import having inserted the same key meanwhile.
      const exists = await tx.deliveryRecord.findUnique({ where: { externalRowKey: r.externalRowKey }, select: { id: true } });
      if (exists) continue;
      await tx.deliveryRecord.create({
        data: {
          importBatchId: batch.id,
          externalRowKey: r.externalRowKey,
          reportNumber: r.reportNumber,
          eventDate: r.eventDate,
          eventTime: r.eventTime,
          eventTimestampLocal: r.eventTimestampLocal,
          weekday: r.weekday,
          merchantRaw: r.merchantRaw,
          notes: r.notes,
          amountAgorot: r.amountAgorot,
          type: r.type,
          typeAutoClassified: r.type,
          isBundle: r.isBundle,
          sourceFileName: r.sourceFileName,
          rowIndex: r.rowIndex,
        },
      });
      inserted++;
    }
    const skipped = preview.parsedRowCount - inserted;
    await tx.importBatch.update({ where: { id: batch.id }, data: { insertedCount: inserted, skippedCount: skipped } });
    return {
      batchId: batch.id,
      parsedRowCount: preview.parsedRowCount,
      insertedCount: inserted,
      skippedCount: skipped,
      skippedInFile: preview.inFileDuplicateCount,
      skippedAlreadyImported: skipped - preview.inFileDuplicateCount - skippedPossible,
      skippedPossibleDuplicates: skippedPossible,
    };
  });
}
