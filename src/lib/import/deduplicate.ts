import { createHash } from "node:crypto";

/**
 * "116000001 (1).pdf", "116000001(1).pdf" and "116000001.pdf" are the same report.
 * Returns null when no report number can be identified.
 */
export function reportNumberFromFileName(fileName: string): string | null {
  const base = fileName.trim().replace(/^.*[\\/]/, "").replace(/\.[a-z0-9]+$/i, "");
  const withoutCopySuffix = base
    .replace(/\s*\(\d+\)\s*$/, "")
    .replace(/\s*-\s*(copy|עותק)(\s*\d+)?\s*$/i, "")
    .trim();
  if (/^\d{6,}$/.test(withoutCopySuffix)) return withoutCopySuffix;
  const m = /(\d{6,})/.exec(withoutCopySuffix);
  return m ? m[1] : null;
}

export function sha256(text: string): string {
  return createHash("sha256").update(text).digest("hex");
}

/** Normalized content hash: BOM stripped, line endings unified, trailing whitespace removed. */
export function contentHash(text: string): string {
  const normalized = text.replace(/^﻿/, "").replace(/\r\n?/g, "\n").replace(/[ \t]+$/gm, "").trim();
  return sha256(normalized);
}

export interface KeyInput {
  sourceFileName: string;
  reportNumber: string | null;
  eventDate: string;
  eventTime: string;
  amountAgorot: number;
  merchantRaw: string;
  notes: string;
}

/**
 * CSV-only key: report identity + date + time + amount + merchant_raw + notes + occurrence_index.
 * Report identity is the report number when known (so a copied PDF maps onto the same
 * report) and otherwise the file name. occurrence_index counts identical rows *within one
 * source file*, so genuinely repeated rows in a report are kept while the same rows from a
 * duplicate copy of the report produce identical keys.
 */
export function assignRowKeys<T extends KeyInput>(rows: T[]): (T & { externalRowKey: string; occurrenceIndex: number })[] {
  const counters = new Map<string, number>();
  return rows.map((r) => {
    const reportIdentity = r.reportNumber ? `report:${r.reportNumber}` : `file:${r.sourceFileName.trim()}`;
    const content = [reportIdentity, r.eventDate, r.eventTime, r.amountAgorot, r.merchantRaw.trim(), r.notes.trim()].join("\u001f");
    const counterKey = `${r.sourceFileName.trim()}\u001f${content}`;
    const occurrenceIndex = counters.get(counterKey) ?? 0;
    counters.set(counterKey, occurrenceIndex + 1);
    return { ...r, occurrenceIndex, externalRowKey: sha256(`${content}\u001f${occurrenceIndex}`) };
  });
}
