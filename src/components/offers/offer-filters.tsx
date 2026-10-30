"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input, Label, Select } from "@/components/ui/form";

export function OfferFilters({ initial }: { initial: Record<string, string> }) {
  const router = useRouter();
  const [v, setV] = useState<Record<string, string>>(initial);
  const field = (k: string, label: string, type = "number", step?: string) => (
    <Label>
      {label}
      <Input type={type} step={step} value={v[k] ?? ""} onChange={(e) => setV({ ...v, [k]: e.target.value })} name={k} />
    </Label>
  );
  function apply(e: React.FormEvent) {
    e.preventDefault();
    const p = new URLSearchParams();
    for (const [k, val] of Object.entries(v)) if (val !== "") p.set(k, val);
    router.push(`/offers?${p.toString()}`);
  }
  return (
    <form onSubmit={apply} className="grid gap-3 rounded-lg border border-border bg-surface p-4 sm:grid-cols-2 lg:grid-cols-5" aria-label="מסננים">
      {field("from", "מתאריך", "date")}
      {field("to", "עד תאריך", "date")}
      {field("hourFrom", "משעה (0–23)")}
      {field("hourTo", "עד שעה (0–23)")}
      <Label>
        זיהוי
        <Select value={v.detection ?? ""} onChange={(e) => setV({ ...v, detection: e.target.value })} name="detection">
          <option value="">ודאי בלבד</option>
          <option value="ALL">כולל זיהוי חלקי</option>
        </Select>
      </Label>
      {field("minAmount", "סכום מינימלי (₪)", "number", "0.01")}
      {field("maxAmount", "סכום מקסימלי (₪)", "number", "0.01")}
      {field("minKm", "מרחק מינימלי (ק״מ)", "number", "0.1")}
      {field("maxKm", "מרחק מקסימלי (ק״מ)", "number", "0.1")}
      <div className="flex items-end gap-2">
        <Button type="submit">סנן</Button>
        <Button type="button" variant="outline" onClick={() => { setV({}); router.push("/offers"); }}>נקה</Button>
      </div>
    </form>
  );
}
