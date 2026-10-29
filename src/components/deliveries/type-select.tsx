"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { RECORD_TYPE_LABELS, RECORD_TYPES, type RecordType } from "@/lib/types";

/** Manual classification override, e.g. approving an UNKNOWN row before it counts as a delivery. */
export function TypeSelect({ id, type, manual }: { id: string; type: RecordType; manual: boolean }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function change(next: string) {
    setBusy(true);
    await fetch(`/api/deliveries/${id}`, { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify({ type: next }) });
    setBusy(false);
    router.refresh();
  }
  return (
    <div className="flex flex-col gap-0.5">
      <select
        aria-label="סוג רשומה"
        disabled={busy}
        value={type}
        onChange={(e) => change(e.target.value)}
        className="rounded border border-border bg-surface px-1 py-0.5 text-xs"
      >
        {RECORD_TYPES.map((t) => (
          <option key={t} value={t}>{RECORD_TYPE_LABELS[t]}</option>
        ))}
      </select>
      {manual && <span className="text-[10px] text-muted">סווג ידנית</span>}
    </div>
  );
}
