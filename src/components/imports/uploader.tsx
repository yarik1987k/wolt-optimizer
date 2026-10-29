"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Money, Num } from "@/components/num";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, Td, Th } from "@/components/ui/table";
import type { ImportPreview, ImportResult } from "@/lib/import/service";
import { formatDateHe } from "@/lib/time";
import { RECORD_TYPE_LABELS, RECORD_TYPES } from "@/lib/types";

const STATUS_LABEL = { NEW: "חדשה", DUPLICATE_IN_FILE: "עותק בקובץ", ALREADY_IMPORTED: "כבר קיימת", POSSIBLE_DUPLICATE: "כפילות אפשרית" } as const;

export function Uploader() {
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [includePossible, setIncludePossible] = useState(false);

  async function send(url: string) {
    if (!file) return null;
    const fd = new FormData();
    fd.set("file", file);
    if (includePossible) fd.set("includePossibleDuplicates", "true");
    setBusy(true);
    setError(null);
    const res = await fetch(url, { method: "POST", body: fd });
    const body = await res.json();
    setBusy(false);
    if (!res.ok) {
      setError(body.error ?? "שגיאה");
      return null;
    }
    return body;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>העלאת CSV של תשלומי וולט</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="file"
            accept=".csv,text/csv"
            aria-label="קובץ CSV"
            data-testid="csv-input"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setIncludePossible(false);
              setPreview(null);
              setResult(null);
            }}
            className="text-sm"
          />
          <Button disabled={!file || busy} onClick={async () => {
              setResult(null);
              setPreview(await send("/api/imports/preview"));
            }}>
            תצוגה מקדימה
          </Button>
        </div>
        {error && <Alert level="error">{error}</Alert>}
        {result && (
          <Alert level="success" data-testid="import-result">
            הייבוא הושלם: <Num>{result.parsedRowCount}</Num> שורות בקובץ, <Num>{result.insertedCount}</Num> נוספו,{" "}
            <Num>{result.skippedCount}</Num> נמנעו מכפילות
            {result.skippedInFile ? <> (<Num>{result.skippedInFile}</Num> עותקים בתוך הקובץ, <Num>{result.skippedAlreadyImported}</Num> שכבר יובאו)</> : null}.
          </Alert>
        )}
        {preview && !result && (
          <div className="space-y-4" data-testid="import-preview">
            <div className="grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <div>שורות בקובץ: <strong><Num>{preview.parsedRowCount}</Num></strong></div>
              <div>רשומות ייחודיות: <strong data-testid="preview-unique"><Num>{preview.uniqueRowCount}</Num></strong></div>
              <div>חדשות לייבוא: <strong data-testid="preview-new"><Num>{preview.newRowCount}</Num></strong></div>
              <div>כבר קיימות / עותקים: <strong><Num>{preview.alreadyImportedCount}</Num> / <Num>{preview.inFileDuplicateCount}</Num></strong></div>
              {RECORD_TYPES.map((t) => (
                <div key={t}>{RECORD_TYPE_LABELS[t]}: <strong><Num>{preview.countsByType[t]}</Num></strong></div>
              ))}
              <div>סה״כ כל השורות: <strong><Money agorot={preview.totalAgorot} /></strong></div>
              <div>סה״כ משלוחים בלבד: <strong><Money agorot={preview.deliveryAgorot} /></strong></div>
            </div>
            <div>
              <h3 className="mb-1 text-sm font-semibold">התאמה לפי דוח</h3>
              <Table>
                <thead><tr><Th>דוח</Th><Th>קבצי מקור</Th><Th>שורות ייחודיות</Th><Th>סכום</Th></tr></thead>
                <tbody>
                  {preview.reports.map((r) => (
                    <tr key={r.reportKey}>
                      <Td><Num>{r.reportKey}</Num></Td>
                      <Td className="text-xs">{r.sourceFiles.join(", ")}</Td>
                      <Td><Num>{r.rowCount}</Num></Td>
                      <Td><Money agorot={r.totalAgorot} /></Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </div>
            <div className="space-y-2">
              <h3 className="text-sm font-semibold">ממצאי איכות נתונים</h3>
              {preview.findings.map((f, i) => (
                <Alert key={i} level={f.level}>
                  {f.message}
                  {f.csvLines?.length ? <span className="opacity-75"> (שורות: <Num>{f.csvLines.join(", ")}</Num>)</span> : null}
                </Alert>
              ))}
            </div>
            <details>
              <summary className="cursor-pointer text-sm">כל השורות (<Num>{preview.rows.length}</Num>)</summary>
              <Table>
                <thead><tr><Th>שורה</Th><Th>תאריך</Th><Th>שעה</Th><Th>בית עסק</Th><Th>סכום</Th><Th>סוג</Th><Th>מקבץ</Th><Th>סטטוס</Th></tr></thead>
                <tbody>
                  {preview.rows.map((r) => (
                    <tr key={`${r.csvLine}`}>
                      <Td><Num>{r.csvLine}</Num></Td>
                      <Td><Num>{formatDateHe(r.eventDate)}</Num></Td>
                      <Td><Num>{r.eventTime}</Num></Td>
                      <Td>{r.merchantRaw}</Td>
                      <Td><Money agorot={r.amountAgorot} /></Td>
                      <Td>{RECORD_TYPE_LABELS[r.type]}</Td>
                      <Td>{r.isBundle ? "כן" : ""}</Td>
                      <Td><Badge variant={r.status === "NEW" ? "success" : "default"}>{STATUS_LABEL[r.status]}</Badge></Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </details>
            {preview.possibleDuplicateCount > 0 && (
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  data-testid="include-possible-duplicates"
                  checked={includePossible}
                  onChange={(e) => setIncludePossible(e.target.checked)}
                />
                ייבא גם <Num>{preview.possibleDuplicateCount}</Num> שורות שסומנו ככפילות אפשרית (רק אם ידוע שאלה תשלומים נפרדים)
              </label>
            )}
            <Button
              disabled={busy || preview.hasErrors}
              onClick={async () => {
                const r = await send("/api/imports");
                if (r) {
                  setResult(r);
                  router.refresh();
                }
              }}
            >
              {(() => {
                const n = preview.newRowCount + (includePossible ? preview.possibleDuplicateCount : 0);
                return n === 0 ? "אשר ייבוא (אין שורות חדשות)" : `אשר ייבוא של ${n} שורות`;
              })()}
            </Button>
            {preview.hasErrors && <Alert level="error">יש שורות לא תקינות — הייבוא חסום. תקן את הקובץ ונסה שוב.</Alert>}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
