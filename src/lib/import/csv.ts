import Papa from "papaparse";
import { parseAmountToAgorot } from "@/lib/money";
import { parseReportDate, parseReportTime, weekdayOf } from "@/lib/time";
import type { RecordType } from "@/lib/types";
import { classifyRow, DEFAULT_RULES, hasAmbiguousComma, isBundle, isPlaceholder, type ClassificationRules } from "./classify";
import { assignRowKeys, reportNumberFromFileName } from "./deduplicate";

const HEADER_ALIASES: Record<string, string[]> = {
  date: ["תאריך", "date"],
  time: ["שעה", "time"],
  merchant: ["בית העסק", "בית עסק", "merchant"],
  notes: ["הערות", "notes"],
  amount: ["סכום ללא מעמ", "סכום ללא מע״מ", "סכום ללא מע\"מ", "סכום", "amount"],
  source: ["קובץ מקור", "source", "source_file"],
};

const REQUIRED = ["date", "time", "merchant", "notes", "amount"] as const;

export interface ParsedRow {
  csvLine: number; // 1-based line in the CSV including the header
  rowIndex: number; // 0-based index within its source file
  eventDate: string;
  eventTime: string;
  eventTimestampLocal: string;
  weekday: number;
  merchantRaw: string;
  notes: string;
  amountAgorot: number;
  type: RecordType;
  isBundle: boolean;
  sourceFileName: string;
  reportNumber: string | null;
  externalRowKey: string;
  occurrenceIndex: number;
}

export interface RowError {
  csvLine: number;
  message: string;
}

export interface ParseResult {
  rows: ParsedRow[];
  errors: RowError[];
  headers: string[];
}

function normalizeHeader(h: string): string {
  return h.replace(/^﻿/, "").trim();
}

export function parseWoltCsv(text: string, uploadFileName: string, rules: ClassificationRules = DEFAULT_RULES): ParseResult {
  const parsed = Papa.parse<string[]>(text.replace(/^﻿/, ""), { skipEmptyLines: "greedy" });
  const errors: RowError[] = parsed.errors.map((e) => ({ csvLine: (e.row ?? 0) + 1, message: `שגיאת CSV: ${e.message}` }));
  const [headerRow, ...body] = parsed.data;
  if (!headerRow) return { rows: [], errors: [{ csvLine: 1, message: "הקובץ ריק" }], headers: [] };
  const headers = headerRow.map(normalizeHeader);
  const col: Record<string, number> = {};
  for (const [field, aliases] of Object.entries(HEADER_ALIASES)) {
    const idx = headers.findIndex((h) => aliases.some((a) => a.toLowerCase() === h.toLowerCase()));
    if (idx >= 0) col[field] = idx;
  }
  const missing = REQUIRED.filter((f) => col[f] === undefined);
  if (missing.length) {
    return {
      rows: [],
      errors: [{ csvLine: 1, message: `עמודות חסרות: ${missing.map((f) => HEADER_ALIASES[f][0]).join(", ")}` }],
      headers,
    };
  }

  const rowIndexByFile = new Map<string, number>();
  const draft: Omit<ParsedRow, "externalRowKey" | "occurrenceIndex">[] = [];
  body.forEach((cells, i) => {
    const csvLine = i + 2;
    const get = (f: string) => (col[f] === undefined ? "" : (cells[col[f]] ?? "").trim());
    try {
      if (cells.length < headers.length) throw new Error(`מספר עמודות חסר (${cells.length}/${headers.length})`);
      const eventDate = parseReportDate(get("date"));
      const eventTime = parseReportTime(get("time"));
      const amountAgorot = parseAmountToAgorot(get("amount"));
      const merchantRaw = get("merchant");
      const notes = get("notes");
      const sourceRaw = get("source");
      const sourceFileName = isPlaceholder(sourceRaw) ? uploadFileName : sourceRaw;
      const rowIndex = rowIndexByFile.get(sourceFileName) ?? 0;
      rowIndexByFile.set(sourceFileName, rowIndex + 1);
      draft.push({
        csvLine,
        rowIndex,
        eventDate,
        eventTime,
        eventTimestampLocal: `${eventDate}T${eventTime}`,
        weekday: weekdayOf(eventDate),
        merchantRaw,
        notes,
        amountAgorot,
        type: classifyRow({ merchantRaw, notes, amountAgorot }, rules),
        isBundle: isBundle(merchantRaw),
        sourceFileName,
        reportNumber: reportNumberFromFileName(sourceFileName),
      });
    } catch (e) {
      errors.push({ csvLine, message: (e as Error).message });
    }
  });
  return { rows: assignRowKeys(draft), errors, headers };
}

export interface DataQualityFinding {
  level: "info" | "warning" | "error";
  message: string;
  csvLines?: number[];
}

export function dataQualityFindings(result: ParseResult): DataQualityFinding[] {
  const out: DataQualityFinding[] = [];
  for (const e of result.errors) out.push({ level: "error", message: `שורה ${e.csvLine}: ${e.message}`, csvLines: [e.csvLine] });
  const { rows } = result;

  const unknown = rows.filter((r) => r.type === "UNKNOWN");
  if (unknown.length)
    out.push({
      level: "warning",
      message: `${unknown.length} שורות לא סווגו (UNKNOWN). הן לא ייכללו בחישובי משלוח עד לסיווג ידני.`,
      csvLines: unknown.map((r) => r.csvLine),
    });

  const ambiguous = rows.filter((r) => hasAmbiguousComma(r.merchantRaw));
  if (ambiguous.length)
    out.push({
      level: "warning",
      message: `${ambiguous.length} שורות מכילות פסיק ללא רווח אחריו (למשל כתובת). הן לא סווגו כמקבץ — בדוק ידנית.`,
      csvLines: ambiguous.map((r) => r.csvLine),
    });

  const pipeEdge = rows.filter((r) => /^\s*\||\|\s*$/.test(r.merchantRaw));
  if (pipeEdge.length)
    out.push({
      level: "info",
      message: `${pipeEdge.length} שמות בתי עסק מתחילים/מסתיימים ב-"|" (ככל הנראה תוצר חילוץ טקסט מה-PDF). נשמרו כפי שהם, ללא תיקון.`,
      csvLines: pipeEdge.map((r) => r.csvLine),
    });

  const bundles = rows.filter((r) => r.isBundle);
  if (bundles.length)
    out.push({
      level: "info",
      message: `${bundles.length} רשומות מכילות מספר בתי עסק (מקבץ). הסכום נשמר ברמת הרשומה ואינו מחולק בין העסקים.`,
    });

  const noReport = rows.filter((r) => !r.reportNumber);
  if (noReport.length)
    out.push({
      level: "warning",
      message: `${noReport.length} שורות ללא מספר דוח מזוהה בשם קובץ המקור; כפילויות יזוהו לפי שם הקובץ בלבד.`,
      csvLines: noReport.map((r) => r.csvLine),
    });

  // Several source files that resolve to the same report number = duplicate copies.
  const filesByReport = new Map<string, Set<string>>();
  for (const r of rows) {
    if (!r.reportNumber) continue;
    if (!filesByReport.has(r.reportNumber)) filesByReport.set(r.reportNumber, new Set());
    filesByReport.get(r.reportNumber)!.add(r.sourceFileName);
  }
  for (const [report, files] of filesByReport) {
    if (files.size > 1)
      out.push({
        level: "warning",
        message: `דוח ${report} מופיע בכמה קבצים (${[...files].join(", ")}) — מטופל כאותו דוח; שורות זהות לא ייספרו פעמיים.`,
      });
  }

  const negatives = rows.filter((r) => r.amountAgorot < 0);
  if (negatives.length)
    out.push({ level: "info", message: `${negatives.length} שורות עם סכום שלילי (קיזוז).`, csvLines: negatives.map((r) => r.csvLine) });

  out.push({
    level: "info",
    message: "הקובץ אינו כולל טיפים, זמני ביצוע או מרחקים. שעת הרישום בדוח אינה בהכרח שעת ביצוע המשלוח.",
  });
  return out;
}
