"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/form";
import { formatShekel } from "@/lib/money";
import { formatDateHe } from "@/lib/time";

interface Props {
  id: string;
  verification: string;
  verificationSource: string | null;
  outcome: string;
  completed: boolean | null;
  shiftId: string | null;
  paymentRecordId: string | null;
}

interface Candidates {
  shifts: { id: string; date: string; startTime: string; endTime: string; areaText: string }[];
  payments: { id: string; eventDate: string; eventTime: string; merchantRaw: string; amountAgorot: number }[];
}

const OUTCOMES: Record<string, string> = { UNKNOWN: "לא ידוע", ACCEPTED: "אושרה (סימון ידני)", DECLINED: "נדחתה", EXPIRED: "פג תוקף" };

/** Manual labels for one offer. Nothing here is inferred from the screen. */
export function OfferLabels(p: Props) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [source, setSource] = useState("");
  const [cands, setCands] = useState<Candidates | null>(null);

  async function patch(body: Record<string, unknown>) {
    setError(null);
    const res = await fetch(`/api/offers/${p.id}`, { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    if (!res.ok) return setError((await res.json()).error ?? "שגיאה");
    setVerifying(false);
    router.refresh();
  }
  async function loadCandidates() {
    if (!cands) setCands(await (await fetch(`/api/offers/${p.id}/candidates`)).json());
  }

  return (
    <div className="flex min-w-56 flex-col gap-1 text-xs" onFocus={loadCandidates} onMouseEnter={loadCandidates}>
      {p.verification === "USER_VERIFIED" ? (
        <div className="flex items-center gap-1">
          <span className="rounded bg-success-bg px-1.5 py-0.5 text-success-fg" title={p.verificationSource ?? ""}>מאומתת ידנית</span>
          <button className="underline" onClick={() => patch({ verification: "UNVERIFIED" })}>בטל</button>
        </div>
      ) : verifying ? (
        <div className="flex gap-1">
          <Input className="h-7 text-xs" placeholder="מקור האימות (מה ראית)" value={source} onChange={(e) => setSource(e.target.value)} />
          <Button size="sm" onClick={() => patch({ verification: "USER_VERIFIED", verificationSource: source })}>אשר</Button>
        </div>
      ) : (
        <Button size="sm" variant="outline" onClick={() => setVerifying(true)} data-testid="verify-offer">אמת ידנית</Button>
      )}
      <Select aria-label="תוצאת ההצעה" className="h-7 text-xs" value={p.outcome} onChange={(e) => patch({ outcome: e.target.value })}>
        {Object.entries(OUTCOMES).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
      </Select>
      {p.outcome === "ACCEPTED" && (
        <Select aria-label="בוצע" className="h-7 text-xs" value={p.completed === null ? "" : String(p.completed)}
          onChange={(e) => patch({ completed: e.target.value === "" ? null : e.target.value === "true" })}>
          <option value="">ביצוע: לא ידוע</option>
          <option value="true">בוצע</option>
          <option value="false">לא בוצע</option>
        </Select>
      )}
      <Select aria-label="משמרת" className="h-7 text-xs" value={p.shiftId ?? ""} onChange={(e) => patch({ shiftId: e.target.value || null })}>
        <option value="">ללא משמרת</option>
        {p.shiftId && !cands?.shifts.some((s) => s.id === p.shiftId) && <option value={p.shiftId}>משמרת מקושרת</option>}
        {cands?.shifts.map((s) => <option key={s.id} value={s.id}>{formatDateHe(s.date)} {s.startTime}–{s.endTime} · {s.areaText}</option>)}
      </Select>
      {p.completed === true && (
        <Select aria-label="תשלום בדוח" className="h-7 text-xs" value={p.paymentRecordId ?? ""} onChange={(e) => patch({ paymentRecordId: e.target.value || null })}>
          <option value="">ללא קישור לתשלום</option>
          {p.paymentRecordId && !cands?.payments.some((r) => r.id === p.paymentRecordId) && <option value={p.paymentRecordId}>תשלום מקושר</option>}
          {cands?.payments.map((r) => <option key={r.id} value={r.id}>{r.eventTime} · {formatShekel(r.amountAgorot)} (ללא מע״מ) · {r.merchantRaw}</option>)}
        </Select>
      )}
      {error && <Alert level="error" className="px-2 py-1 text-xs">{error}</Alert>}
    </div>
  );
}
