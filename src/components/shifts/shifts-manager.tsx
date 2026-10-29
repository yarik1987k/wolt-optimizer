"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Money, Num } from "@/components/num";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { ShiftMetrics } from "@/lib/analytics/shifts";
import { formatDateHe } from "@/lib/time";
import { EXPERIMENT_GROUP_LABELS, type ExperimentGroup } from "@/lib/types";
import { LinkPanel } from "./link-panel";
import { emptyShift, ShiftForm, type ShiftFormValues } from "./shift-form";

export interface ShiftRow {
  id: string;
  date: string;
  startTime: string;
  endTime: string;
  startedAt: string;
  endedAt: string;
  areaText: string;
  distanceKm: number | null;
  vehicleCostPerKmAgorot: number | null;
  followedRecommendation: boolean;
  notes: string | null;
  metrics: ShiftMetrics;
  experiments: { group: string; experiment: { id: string; title: string } }[];
}

async function send(url: string, method: string, body: unknown): Promise<string | null> {
  const res = await fetch(url, { method, headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  if (res.ok) return null;
  const b = await res.json();
  return [b.error, ...(b.issues ?? [])].filter(Boolean).join(" · ");
}

function toForm(s: ShiftRow): ShiftFormValues {
  return {
    date: s.date,
    startTime: s.startTime,
    endTime: s.endTime,
    areaText: s.areaText,
    distanceKm: s.distanceKm?.toString() ?? "",
    costPerKmShekel: s.vehicleCostPerKmAgorot != null ? (s.vehicleCostPerKmAgorot / 100).toFixed(2) : "",
    followedRecommendation: s.followedRecommendation,
    experimentId: s.experiments[0]?.experiment.id ?? "",
    experimentGroup: (s.experiments[0]?.group as ExperimentGroup) ?? "",
    notes: s.notes ?? "",
  };
}

function ShiftCard({ s, experiments }: { s: ShiftRow; experiments: { id: string; title: string }[] }) {
  const router = useRouter();
  const [mode, setMode] = useState<"view" | "edit" | "links">("view");
  const [withExtras, setWithExtras] = useState(false);
  const m = s.metrics;
  const perHour = withExtras ? m.gross_including_extras_per_hour_ex_vat_agorot : m.gross_delivery_per_hour_ex_vat_agorot;
  return (
    <Card data-testid="shift-card">
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle>
            <Num>{formatDateHe(s.date)}</Num> · <Num>{s.startTime}–{s.endTime}</Num>
            {s.endedAt.slice(0, 10) !== s.date && <span className="text-xs font-normal text-muted"> (עד למחרת)</span>} · {s.areaText}
          </CardTitle>
          <div className="flex flex-wrap gap-1">
            {s.followedRecommendation && <Badge variant="info">לפי המלצה</Badge>}
            {s.experiments.map((e) => (
              <Badge key={e.experiment.id}>{EXPERIMENT_GROUP_LABELS[e.group as ExperimentGroup]}: {e.experiment.title}</Badge>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <dl className="grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <div><dt className="text-muted">משך</dt><dd data-testid="shift-duration"><Num>{m.duration_hours.toFixed(2)}</Num> שעות</dd></div>
          <div><dt className="text-muted">רשומות מאושרות</dt><dd><Num>{m.linked_record_count}</Num></dd></div>
          <div><dt className="text-muted">משלוחים (ללא מע״מ)</dt><dd><Money agorot={m.linked_delivery_count ? m.delivery_amount_ex_vat_agorot : null} /></dd></div>
          <div><dt className="text-muted">בונוסים / תוספות</dt><dd><Money agorot={m.bonus_amount_agorot} /> / <Money agorot={m.adjustment_amount_agorot} /></dd></div>
          <div>
            <dt className="text-muted">₪ לשעת משמרת {withExtras ? "(כולל תוספות)" : "(משלוחים בלבד)"}</dt>
            <dd><Money agorot={perHour} /></dd>
          </div>
          <div><dt className="text-muted">עלות רכב משוערת</dt><dd><Money agorot={m.estimated_vehicle_cost_agorot} /></dd></div>
          <div>
            <dt className="text-muted">תרומה לשעה אחרי עלות רכב</dt>
            <dd><Money agorot={m.contribution_per_hour_ex_vat_agorot} /></dd>
          </div>
          <div><dt className="text-muted">₪ לק״מ</dt><dd><Money agorot={m.delivery_per_km_agorot} /></dd></div>
        </dl>
        <p className="text-xs text-muted">
          ״תרומה לשעה״ אינה רווח נקי: לא כוללת מס הכנסה, ביטוח, ביטוח לאומי ועלויות קבועות.
          {m.unknown_amount_agorot ? " רשומות לא מסווגות לא נכללו." : ""}
        </p>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={withExtras} onChange={(e) => setWithExtras(e.target.checked)} /> הצג ₪/שעה כולל בונוסים ותוספות
        </label>
        {s.notes && <p className="text-sm">הערות: {s.notes}</p>}
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant={mode === "links" ? "default" : "outline"} onClick={() => setMode(mode === "links" ? "view" : "links")}>
            שיוך רשומות
          </Button>
          <Button size="sm" variant="outline" onClick={() => setMode(mode === "edit" ? "view" : "edit")}>עריכה</Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={async () => {
              if (!confirm("למחוק את המשמרת? שיוכי הרשומות יבוטלו (הרשומות עצמן לא נמחקות).")) return;
              await fetch(`/api/shifts/${s.id}`, { method: "DELETE" });
              router.refresh();
            }}
          >
            מחיקה
          </Button>
        </div>
        {mode === "links" && <LinkPanel shiftId={s.id} onChanged={() => router.refresh()} />}
        {mode === "edit" && (
          <ShiftForm
            initial={toForm(s)}
            experiments={experiments}
            submitLabel="שמור"
            onCancel={() => setMode("view")}
            onSubmit={async (body) => {
              const err = await send(`/api/shifts/${s.id}`, "PATCH", body);
              if (!err) {
                setMode("view");
                router.refresh();
              }
              return err;
            }}
          />
        )}
      </CardContent>
    </Card>
  );
}

export function ShiftsManager({ shifts, experiments, defaultCostPerKmAgorot }: { shifts: ShiftRow[]; experiments: { id: string; title: string }[]; defaultCostPerKmAgorot: number | null }) {
  const router = useRouter();
  const [formKey, setFormKey] = useState(0);
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader><CardTitle>תיעוד משמרת חדשה</CardTitle></CardHeader>
        <CardContent>
          <ShiftForm
            key={formKey}
            initial={emptyShift(defaultCostPerKmAgorot)}
            experiments={experiments}
            submitLabel="שמור משמרת"
            onSubmit={async (body) => {
              const err = await send("/api/shifts", "POST", body);
              if (!err) {
                setFormKey((k) => k + 1);
                router.refresh();
              }
              return err;
            }}
          />
        </CardContent>
      </Card>
      <section className="space-y-3" aria-label="משמרות">
        <h2 className="text-lg font-semibold">משמרות מתועדות (<Num>{shifts.length}</Num>)</h2>
        {shifts.length === 0 && <p className="text-sm text-muted">עדיין לא תועדו משמרות. ₪/שעה ו-₪/ק״מ יוצגו כ״אין נתונים״ עד שיתועדו משמרות ויאושרו להן רשומות.</p>}
        {shifts.map((s) => (
          <ShiftCard key={s.id} s={s} experiments={experiments} />
        ))}
      </section>
    </div>
  );
}
