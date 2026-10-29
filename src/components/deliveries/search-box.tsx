"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input, Label, Select } from "@/components/ui/form";
import { RECORD_TYPE_LABELS, RECORD_TYPES } from "@/lib/types";

export function DeliveriesToolbar({ reports }: { reports: string[] }) {
  const router = useRouter();
  const sp = useSearchParams();
  const [q, setQ] = useState(sp.get("q") ?? "");
  const [types, setTypes] = useState(sp.get("types") ?? "");
  const [bundle, setBundle] = useState(sp.get("bundle") ?? "");
  const [report, setReport] = useState(sp.get("report") ?? "");
  function apply(e: React.FormEvent) {
    e.preventDefault();
    const p = new URLSearchParams(sp.toString());
    for (const [k, v] of Object.entries({ q, types, bundle, report })) v ? p.set(k, v) : p.delete(k);
    p.delete("page");
    router.push(`/deliveries?${p.toString()}`);
  }
  return (
    <form onSubmit={apply} className="grid gap-3 rounded-lg border border-border bg-surface p-4 sm:grid-cols-2 lg:grid-cols-5" aria-label="חיפוש וסינון">
      <Label className="lg:col-span-2">
        חיפוש חופשי
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="בית עסק, הערה, קובץ…" />
      </Label>
      <Label>
        סוג רשומה
        <Select value={types} onChange={(e) => setTypes(e.target.value)} data-testid="type-filter">
          <option value="">הכל</option>
          {RECORD_TYPES.map((t) => (
            <option key={t} value={t}>{RECORD_TYPE_LABELS[t]}</option>
          ))}
          <option value="BONUS,ADJUSTMENT">בונוסים ותוספות</option>
        </Select>
      </Label>
      <Label>
        יחיד / מקבץ
        <Select value={bundle} onChange={(e) => setBundle(e.target.value)}>
          <option value="">הכל</option>
          <option value="single">בית עסק יחיד</option>
          <option value="bundle">מקבץ</option>
        </Select>
      </Label>
      <Label>
        דוח
        <Select value={report} onChange={(e) => setReport(e.target.value)}>
          <option value="">הכל</option>
          {reports.map((r) => (
            <option key={r} value={r}>{r}</option>
          ))}
        </Select>
      </Label>
      <div className="flex gap-2 sm:col-span-2 lg:col-span-5">
        <Button type="submit">סנן</Button>
        <Button type="button" variant="outline" onClick={() => router.push("/deliveries")}>נקה</Button>
      </div>
    </form>
  );
}
