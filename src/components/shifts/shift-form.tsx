"use client";
import { useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Hint, Input, Label, Select, Textarea } from "@/components/ui/form";
import { computeShiftWindow } from "@/lib/time";

export interface ShiftFormValues {
  date: string;
  startTime: string;
  endTime: string;
  areaText: string;
  distanceKm: string;
  costPerKmShekel: string;
  followedRecommendation: boolean;
  experimentId: string;
  experimentGroup: "" | "CONTROL" | "TEST";
  notes: string;
}

export const emptyShift = (costPerKmAgorot: number | null): ShiftFormValues => ({
  date: "",
  startTime: "",
  endTime: "",
  areaText: "",
  distanceKm: "",
  costPerKmShekel: costPerKmAgorot != null ? (costPerKmAgorot / 100).toFixed(2) : "",
  followedRecommendation: false,
  experimentId: "",
  experimentGroup: "",
  notes: "",
});

export function ShiftForm({
  initial,
  experiments,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initial: ShiftFormValues;
  experiments: { id: string; title: string }[];
  submitLabel: string;
  onSubmit: (body: Record<string, unknown>) => Promise<string | null>;
  onCancel?: () => void;
}) {
  const [v, setV] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (p: Partial<ShiftFormValues>) => setV((s) => ({ ...s, ...p }));

  let durationText = "";
  try {
    if (v.date && v.startTime && v.endTime) {
      const w = computeShiftWindow(v.date, v.startTime, v.endTime);
      durationText = `משך: ${w.durationHours.toFixed(2)} שעות${w.crossesMidnight ? " (חוצה חצות — מסתיימת למחרת)" : ""}`;
    }
  } catch (e) {
    durationText = (e as Error).message;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const cost = v.costPerKmShekel.trim();
    const km = v.distanceKm.trim();
    if (v.experimentId && !v.experimentGroup) return setError("בחר קבוצה (ביקורת / ניסוי) עבור הניסוי");
    setBusy(true);
    const err = await onSubmit({
      date: v.date,
      startTime: v.startTime,
      endTime: v.endTime,
      areaText: v.areaText,
      distanceKm: km ? Number(km) : null,
      vehicleCostPerKmAgorot: cost ? Math.round(Number(cost) * 100) : null,
      followedRecommendation: v.followedRecommendation,
      experimentId: v.experimentId || null,
      experimentGroup: v.experimentGroup || null,
      notes: v.notes || null,
    });
    setBusy(false);
    setError(err);
  }

  return (
    <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-label="טופס משמרת">
      <Label>
        תאריך
        <Input type="date" required value={v.date} onChange={(e) => set({ date: e.target.value })} name="date" />
      </Label>
      <Label>
        שעת התחלה בפועל
        <Input type="time" required value={v.startTime} onChange={(e) => set({ startTime: e.target.value })} name="startTime" />
      </Label>
      <Label>
        שעת סיום בפועל
        <Input type="time" required value={v.endTime} onChange={(e) => set({ endTime: e.target.value })} name="endTime" />
        <Hint>כולל המתנה, לא רק נסיעה פעילה</Hint>
      </Label>
      <Label>
        אזור עבודה עיקרי
        <Input required value={v.areaText} onChange={(e) => set({ areaText: e.target.value })} name="areaText" />
      </Label>
      <Label>
        ק״מ בנסיעה במשמרת <Hint>(אופציונלי)</Hint>
        <Input type="number" min="0" step="0.1" inputMode="decimal" value={v.distanceKm} onChange={(e) => set({ distanceKm: e.target.value })} name="distanceKm" />
      </Label>
      <Label>
        עלות רכב לק״מ (₪) <Hint>(אופציונלי, הגדרה אישית)</Hint>
        <Input type="number" min="0" step="0.01" inputMode="decimal" value={v.costPerKmShekel} onChange={(e) => set({ costPerKmShekel: e.target.value })} name="costPerKm" />
      </Label>
      <Label>
        שיוך לניסוי
        <Select value={v.experimentId} onChange={(e) => set({ experimentId: e.target.value })} name="experimentId">
          <option value="">ללא</option>
          {experiments.map((x) => (
            <option key={x.id} value={x.id}>{x.title}</option>
          ))}
        </Select>
      </Label>
      <Label>
        קבוצה בניסוי
        <Select value={v.experimentGroup} disabled={!v.experimentId} onChange={(e) => set({ experimentGroup: e.target.value as ShiftFormValues["experimentGroup"] })} name="experimentGroup">
          <option value="">—</option>
          <option value="CONTROL">ביקורת</option>
          <option value="TEST">ניסוי</option>
        </Select>
      </Label>
      <label className="flex items-center gap-2 self-end text-sm">
        <input type="checkbox" checked={v.followedRecommendation} onChange={(e) => set({ followedRecommendation: e.target.checked })} />
        פעלתי לפי המלצה
      </label>
      <Label className="sm:col-span-2 lg:col-span-3">
        הערות <Hint>(עומס, מזג אוויר וכו׳)</Hint>
        <Textarea value={v.notes} onChange={(e) => set({ notes: e.target.value })} name="notes" />
      </Label>
      {durationText && <p className="text-sm text-muted sm:col-span-2 lg:col-span-3" data-testid="duration-preview">{durationText}</p>}
      {error && <Alert level="error" className="sm:col-span-2 lg:col-span-3">{error}</Alert>}
      <div className="flex gap-2 sm:col-span-2 lg:col-span-3">
        <Button type="submit" disabled={busy}>{submitLabel}</Button>
        {onCancel && <Button type="button" variant="outline" onClick={onCancel}>ביטול</Button>}
      </div>
    </form>
  );
}
