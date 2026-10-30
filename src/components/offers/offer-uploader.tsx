"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Num } from "@/components/num";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import type { OfferImportResult } from "@/lib/offers/import";

export function OfferUploader() {
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<OfferImportResult | null>(null);

  async function upload() {
    if (!file) return;
    setBusy(true);
    setError(null);
    setResult(null);
    const fd = new FormData();
    fd.set("file", file);
    const res = await fetch("/api/offers/import", { method: "POST", body: fd });
    const body = await res.json();
    setBusy(false);
    if (!res.ok) return setError(body.error ?? "שגיאה");
    setResult(body);
    router.refresh();
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted">
        קובץ JSON מ-Wolt Offer Collector (הגדרות ← ייצוא JSON). ייבוא חוזר של אותו קובץ או של קובץ חופף לא יוצר כפילויות.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <input type="file" accept=".json,application/json" aria-label="קובץ JSON" data-testid="offer-json-input" className="text-sm"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
        <Button disabled={!file || busy} onClick={upload}>{busy ? "מייבא…" : "ייבוא הצעות"}</Button>
      </div>
      {error && <Alert level="error">{error}</Alert>}
      {result && (
        <Alert level="success" data-testid="offer-import-result">
          נקראו <Num>{result.screenEventCount}</Num> צילומי מסך, מתוכם <Num>{result.offerScreenCount}</Num> כרטיסי הצעה ·{" "}
          <Num>{result.newObservationCount}</Num> חדשים, <Num>{result.skippedObservationCount}</Num> כבר יובאו · סה״כ הצעות שנצפו: <Num>{result.offersTotal}</Num>
          {result.shiftSessionCount + result.locationSampleCount > 0 && (
            <span data-testid="location-import-result">
              {" "}· משמרות Collector: <Num>{result.shiftSessionCount}</Num> (<Num>{result.newShiftCount}</Num> חדשות) · דגימות מיקום: <Num>{result.locationSampleCount}</Num> (
              <Num>{result.newLocationSampleCount}</Num> חדשות)
            </span>
          )}
        </Alert>
      )}
    </div>
  );
}
