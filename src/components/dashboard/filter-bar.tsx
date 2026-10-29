"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input, Label, Select } from "@/components/ui/form";
import { RECORD_TYPE_LABELS, RECORD_TYPES, WEEKDAY_LABELS } from "@/lib/types";

export interface FilterValues {
  from?: string;
  to?: string;
  hourFrom?: number;
  hourTo?: number;
  weekdays?: number[];
  types?: string[];
  merchant?: string;
  report?: string;
  bundle?: string;
  window?: number;
}

export function FilterBar({ initial, reports, basePath, showWindow }: { initial: FilterValues; reports: string[]; basePath: string; showWindow?: boolean }) {
  const router = useRouter();
  const [v, setV] = useState<FilterValues>(initial);
  const set = (patch: Partial<FilterValues>) => setV((s) => ({ ...s, ...patch }));
  const toggle = <T,>(arr: T[] | undefined, x: T) => (arr?.includes(x) ? arr.filter((a) => a !== x) : [...(arr ?? []), x]);

  function apply(e: React.FormEvent) {
    e.preventDefault();
    const p = new URLSearchParams();
    for (const [k, val] of Object.entries(v)) {
      if (val === undefined || val === "" || (Array.isArray(val) && val.length === 0)) continue;
      p.set(k, Array.isArray(val) ? val.join(",") : String(val));
    }
    router.push(`${basePath}?${p.toString()}`);
  }

  const hours = Array.from({ length: 24 }, (_, h) => h);
  return (
    <form onSubmit={apply} className="grid gap-3 rounded-lg border border-border bg-surface p-4 sm:grid-cols-2 lg:grid-cols-4" aria-label="מסננים">
      <Label>
        מתאריך
        <Input type="date" value={v.from ?? ""} onChange={(e) => set({ from: e.target.value || undefined })} />
      </Label>
      <Label>
        עד תאריך
        <Input type="date" value={v.to ?? ""} onChange={(e) => set({ to: e.target.value || undefined })} />
      </Label>
      <div className="grid grid-cols-2 gap-2">
        <Label>
          משעה
          <Select value={v.hourFrom ?? ""} onChange={(e) => set({ hourFrom: e.target.value === "" ? undefined : Number(e.target.value) })}>
            <option value="">הכל</option>
            {hours.map((h) => (
              <option key={h} value={h}>{`${String(h).padStart(2, "0")}:00`}</option>
            ))}
          </Select>
        </Label>
        <Label>
          עד שעה
          <Select value={v.hourTo ?? ""} onChange={(e) => set({ hourTo: e.target.value === "" ? undefined : Number(e.target.value) })}>
            <option value="">הכל</option>
            {hours.map((h) => (
              <option key={h} value={h}>{`${String(h).padStart(2, "0")}:59`}</option>
            ))}
          </Select>
        </Label>
      </div>
      <Label>
        דוח
        <Select value={v.report ?? ""} onChange={(e) => set({ report: e.target.value || undefined })}>
          <option value="">כל הדוחות</option>
          {reports.map((r) => (
            <option key={r} value={r}>{r}</option>
          ))}
        </Select>
      </Label>
      <Label>
        בית עסק (חיפוש)
        <Input value={v.merchant ?? ""} onChange={(e) => set({ merchant: e.target.value || undefined })} placeholder="למשל: וולט מרקט" />
      </Label>
      <Label>
        יחיד / מקבץ
        <Select value={v.bundle ?? ""} onChange={(e) => set({ bundle: e.target.value || undefined })}>
          <option value="">הכל</option>
          <option value="single">בית עסק יחיד</option>
          <option value="bundle">מקבץ (כמה בתי עסק)</option>
        </Select>
      </Label>
      {showWindow && (
        <Label>
          חלון שעות בגרף
          <Select value={v.window ?? 60} onChange={(e) => set({ window: Number(e.target.value) })}>
            {[30, 60, 120].map((m) => (
              <option key={m} value={m}>{`${m} דקות`}</option>
            ))}
          </Select>
        </Label>
      )}
      <fieldset className="flex flex-col gap-1 text-sm sm:col-span-2">
        <legend className="font-medium">ימים בשבוע</legend>
        <div className="flex flex-wrap gap-3">
          {WEEKDAY_LABELS.map((d, i) => (
            <label key={d} className="flex items-center gap-1">
              <input type="checkbox" checked={v.weekdays?.includes(i) ?? false} onChange={() => set({ weekdays: toggle(v.weekdays, i) })} />
              {d}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset className="flex flex-col gap-1 text-sm sm:col-span-2">
        <legend className="font-medium">סוג רשומה <span className="font-normal text-muted">(ברירת מחדל בגרפים: משלוחים בלבד)</span></legend>
        <div className="flex flex-wrap gap-3">
          {RECORD_TYPES.map((t) => (
            <label key={t} className="flex items-center gap-1">
              <input type="checkbox" checked={v.types?.includes(t) ?? false} onChange={() => set({ types: toggle(v.types, t) })} />
              {RECORD_TYPE_LABELS[t]}
            </label>
          ))}
        </div>
      </fieldset>
      <div className="flex items-end gap-2 sm:col-span-2 lg:col-span-4">
        <Button type="submit">החל סינון</Button>
        <Button type="button" variant="outline" onClick={() => { setV({}); router.push(basePath); }}>
          נקה
        </Button>
      </div>
    </form>
  );
}
