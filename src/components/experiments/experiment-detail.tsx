"use client";
import { useRouter } from "next/navigation";
import { Money, Num } from "@/components/num";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Select } from "@/components/ui/form";
import { Table, Td, Th } from "@/components/ui/table";
import type { Comparison, GroupStats } from "@/lib/analytics/experiments";
import { formatDateHe } from "@/lib/time";
import { EXPERIMENT_STATUS_LABELS, EXPERIMENT_STATUSES, TARGET_METRICS } from "@/lib/types";

export interface ShiftOption {
  id: string;
  date: string;
  startTime: string;
  endTime: string;
  areaText: string;
  durationHours: number;
  linkedRecordCount: number;
  group: "CONTROL" | "TEST" | null;
}

function GroupColumn({ g }: { g: GroupStats }) {
  return (
    <>
      <Td><Num>{g.shift_count}</Num>{g.shifts_missing_records ? <span className="text-xs text-muted"> (<Num>{g.shifts_missing_records}</Num> ללא רשומות)</span> : null}</Td>
      <Td><Num>{g.total_hours.toFixed(2)}</Num></Td>
      <Td><Money agorot={g.total_delivery_ex_vat_agorot} /></Td>
      <Td data-testid="group-rate"><Money agorot={g.delivery_per_hour_agorot} /></Td>
      <Td><Money agorot={g.contribution_per_hour_agorot} /></Td>
    </>
  );
}

export function ExperimentDetail({
  experiment,
  comparison,
  shifts,
}: {
  experiment: { id: string; title: string; hypothesisText: string; status: string; startDate: string | null; targetMetric: string };
  comparison: Comparison;
  shifts: ShiftOption[];
}) {
  const router = useRouter();
  async function patch(body: unknown) {
    await fetch(`/api/experiments/${experiment.id}`, { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    router.refresh();
  }
  async function assign(shiftId: string, group: string) {
    await fetch(`/api/experiments/${experiment.id}/shifts`, {
      method: group ? "POST" : "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(group ? { shiftId, group } : { shiftId }),
    });
    router.refresh();
  }
  const c = comparison;
  return (
    <Card data-testid="experiment-detail">
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle>{experiment.title}</CardTitle>
          <Select aria-label="סטטוס" className="w-auto" value={experiment.status} onChange={(e) => patch({ status: e.target.value })}>
            {EXPERIMENT_STATUSES.map((s) => (
              <option key={s} value={s}>{EXPERIMENT_STATUS_LABELS[s]}</option>
            ))}
          </Select>
        </div>
        <p className="text-sm whitespace-pre-line text-muted">{experiment.hypothesisText}</p>
        <p className="text-xs text-muted">
          מדד יעד: {TARGET_METRICS[experiment.targetMetric as keyof typeof TARGET_METRICS] ?? experiment.targetMetric}
          {experiment.startDate && <> · התחלה: <Num>{formatDateHe(experiment.startDate)}</Num></>}
        </p>
      </CardHeader>
      <CardContent className="space-y-5">
        <section className="space-y-2">
          <h3 className="font-semibold">השוואה</h3>
          <Table>
            <thead>
              <tr><Th>קבוצה</Th><Th>משמרות</Th><Th>סה״כ שעות</Th><Th>סכום משלוחים</Th><Th>₪/שעה (סכום÷שעות)</Th><Th>תרומה אחרי רכב/שעה</Th></tr>
            </thead>
            <tbody>
              <tr><Td>ביקורת</Td><GroupColumn g={c.control} /></tr>
              <tr><Td>ניסוי</Td><GroupColumn g={c.test} /></tr>
            </tbody>
          </Table>
          <p className="text-sm">
            הפרש ₪/שעה (ניסוי מול ביקורת):{" "}
            <strong data-testid="pct-diff">{c.pct_diff_delivery_per_hour == null ? "אין נתונים" : <Num>{`${c.pct_diff_delivery_per_hour > 0 ? "+" : ""}${c.pct_diff_delivery_per_hour.toFixed(1)}%`}</Num>}</strong>
            {c.pct_diff_contribution_per_hour != null && (
              <> · הפרש בתרומה אחרי עלות רכב: <Num>{`${c.pct_diff_contribution_per_hour.toFixed(1)}%`}</Num></>
            )}
          </p>
          <Alert level={c.can_determine ? "info" : "warning"} data-testid="verdict">{c.verdict}</Alert>
          {c.warnings.map((w) => (
            <Alert key={w} level="warning">{w}</Alert>
          ))}
          <p className="text-xs text-muted">
            יעד ראשוני: שיפור של ≥{c.target_improvement_pct}% ב-₪ לשעת משמרת (ללא בונוסים ותוספות) מול ביקורת, שחוזר במספר משמרות. תוצאה היא אינדיקציה ולא הוכחה סיבתית;
            עדיף להשוות משמרות ביקורת וניסוי בתנאים דומים (יום, שעות, מזג אוויר). אין להכריז על הצלחה על סמך משמרת יחידה.
          </p>
        </section>
        <section className="space-y-2">
          <h3 className="font-semibold">שיוך משמרות לקבוצות</h3>
          {shifts.length === 0 ? (
            <p className="text-sm text-muted">אין משמרות מתועדות. תעד משמרות בעמוד ״משמרות״.</p>
          ) : (
            <Table>
              <thead><tr><Th>משמרת</Th><Th>אזור</Th><Th>שעות</Th><Th>רשומות מאושרות</Th><Th>קבוצה</Th></tr></thead>
              <tbody>
                {shifts.map((s) => (
                  <tr key={s.id}>
                    <Td><Num>{formatDateHe(s.date)} {s.startTime}–{s.endTime}</Num></Td>
                    <Td>{s.areaText}</Td>
                    <Td><Num>{s.durationHours.toFixed(2)}</Num></Td>
                    <Td>{s.linkedRecordCount ? <Num>{s.linkedRecordCount}</Num> : <Badge variant="warning">אין</Badge>}</Td>
                    <Td>
                      <Select aria-label="קבוצה" className="w-auto" value={s.group ?? ""} onChange={(e) => assign(s.id, e.target.value)}>
                        <option value="">לא משויכת</option>
                        <option value="CONTROL">ביקורת</option>
                        <option value="TEST">ניסוי</option>
                      </Select>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </section>
      </CardContent>
    </Card>
  );
}
