import { Uploader } from "@/components/imports/uploader";
import { Money, Num } from "@/components/num";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, Td, Th } from "@/components/ui/table";
import { getImportHistory } from "@/lib/imports";
import { RECORD_TYPE_LABELS, type RecordType } from "@/lib/types";

export const dynamic = "force-dynamic";

const dt = new Intl.DateTimeFormat("he-IL", { timeZone: "Asia/Jerusalem", dateStyle: "short", timeStyle: "short" });

export default async function ImportsPage() {
  const { batches, reconciliation } = await getImportHistory();
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">ייבוא</h1>
      <Uploader />
      <Card>
        <CardHeader><CardTitle>התאמה — מה שמור במערכת</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm" data-testid="reconciliation">
          <p>
            סה״כ <Num>{reconciliation.total_records}</Num> רשומות, <Money agorot={reconciliation.total_agorot} />
          </p>
          <div className="grid gap-4 md:grid-cols-2">
            <Table>
              <thead><tr><Th>דוח</Th><Th>רשומות</Th><Th>סכום</Th></tr></thead>
              <tbody>
                {reconciliation.by_report.map((r) => (
                  <tr key={r.report_number ?? "none"}><Td><Num>{r.report_number ?? "לא ידוע"}</Num></Td><Td><Num>{r.records}</Num></Td><Td><Money agorot={r.sum_agorot} /></Td></tr>
                ))}
              </tbody>
            </Table>
            <Table>
              <thead><tr><Th>סוג</Th><Th>רשומות</Th><Th>סכום</Th></tr></thead>
              <tbody>
                {reconciliation.by_type.map((r) => (
                  <tr key={r.type}><Td>{RECORD_TYPE_LABELS[r.type as RecordType]}</Td><Td><Num>{r.records}</Num></Td><Td><Money agorot={r.sum_agorot} /></Td></tr>
                ))}
              </tbody>
            </Table>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>היסטוריית ייבוא</CardTitle></CardHeader>
        <CardContent>
          {batches.length === 0 ? (
            <p className="text-sm text-muted">עדיין לא בוצע ייבוא.</p>
          ) : (
            <Table data-testid="import-history">
              <thead><tr><Th>מועד</Th><Th>קובץ</Th><Th>דוחות</Th><Th>שורות בקובץ</Th><Th>נוספו</Th><Th>נמנעו מכפילות</Th></tr></thead>
              <tbody>
                {batches.map((b) => (
                  <tr key={b.id}>
                    <Td className="whitespace-nowrap"><Num>{dt.format(b.importedAt)}</Num></Td>
                    <Td>{b.sourceFileName}</Td>
                    <Td className="text-xs"><Num>{b.reportNumbers}</Num></Td>
                    <Td><Num>{b.rowCount}</Num></Td>
                    <Td><Num>{b.insertedCount}</Num></Td>
                    <Td><Num>{b.skippedCount}</Num></Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
