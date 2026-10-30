"use client";
import { useMemo, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Hint, Input, Label, Select } from "@/components/ui/form";
import { formatShekel } from "@/lib/money";
import { BASIS_LABEL, simulateShift, SimulationInputError, type AmountBasis, type SimulationResult } from "@/lib/offers/simulator";

export interface Presets {
  reports: { avgAgorot: number; n: number } | null;
  offers: { avgAgorot: number; avgKm: number | null; n: number } | null;
  vehicleCostPerKmAgorot: number | null;
}

type Form = {
  basis: AmountBasis;
  deliveries: string;
  avgPayment: string; // ₪
  shiftHours: string;
  target: string; // ₪
  minutes: string;
  km: string;
  costPerKm: string; // ₪
};

const shekelStr = (agorot: number) => (agorot / 100).toFixed(2);
const optNum = (s: string) => (s.trim() === "" ? null : Number(s));

export function SimulatorForm({ presets }: { presets: Presets }) {
  const [f, setF] = useState<Form>({
    basis: "EX_VAT",
    deliveries: presets.reports ? String(Math.round(22000 / presets.reports.avgAgorot)) : "6",
    avgPayment: presets.reports ? shekelStr(presets.reports.avgAgorot) : "",
    shiftHours: "4",
    target: "400",
    minutes: "",
    km: "",
    costPerKm: presets.vehicleCostPerKmAgorot === null ? "" : shekelStr(presets.vehicleCostPerKmAgorot),
  });
  const set = (p: Partial<Form>) => setF((s) => ({ ...s, ...p }));

  const { result, error } = useMemo((): { result: SimulationResult | null; error: string | null } => {
    try {
      if (f.avgPayment.trim() === "" || f.deliveries.trim() === "") return { result: null, error: null };
      const r = simulateShift({
        basis: f.basis,
        deliveries: Number(f.deliveries),
        avgPaymentAgorot: Math.round(Number(f.avgPayment) * 100),
        shiftHours: Number(f.shiftHours),
        targetAgorot: Math.round(Number(f.target) * 100),
        avgMinutesPerDelivery: optNum(f.minutes),
        avgKmPerDelivery: optNum(f.km),
        vehicleCostPerKmAgorot: optNum(f.costPerKm) === null ? null : Math.round(Number(f.costPerKm) * 100),
      });
      return { result: r, error: null };
    } catch (e) {
      return { result: null, error: e instanceof SimulationInputError ? e.message : "קלט לא תקין" };
    }
  }, [f]);

  const feasibility = result && {
    MATH_ONLY: { text: "סימולציה מתמטית בלבד", variant: "warning" as const },
    FITS_IN_SHIFT: { text: "נכנס בזמן המשמרת (לפי הזמן שהוזן)", variant: "success" as const },
    EXCEEDS_SHIFT: { text: "חורג מזמן המשמרת", variant: "danger" as const },
  }[result.feasibility];

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle>הנחות</CardTitle>
          <CardDescription>כל הסכומים באותו בסיס. אין לערבב סכומים ללא מע״מ עם סכומי הצעות.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {presets.reports && (
              <Button variant="outline" size="sm" onClick={() => set({ basis: "EX_VAT", avgPayment: shekelStr(presets.reports!.avgAgorot), km: "" })}>
                מהדוחות: {formatShekel(presets.reports.avgAgorot)} (ללא מע״מ, n={presets.reports.n})
              </Button>
            )}
            {presets.offers && (
              <Button variant="outline" size="sm" onClick={() => set({ basis: "AS_DISPLAYED", avgPayment: shekelStr(presets.offers!.avgAgorot), km: presets.offers!.avgKm?.toString() ?? "" })}>
                מהצעות שנצפו: {formatShekel(presets.offers.avgAgorot)} (כפי שמוצג, n={presets.offers.n})
              </Button>
            )}
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Label>
              בסיס הסכומים
              <Select value={f.basis} onChange={(e) => set({ basis: e.target.value as AmountBasis })} name="basis">
                <option value="EX_VAT">{BASIS_LABEL.EX_VAT}</option>
                <option value="AS_DISPLAYED">{BASIS_LABEL.AS_DISPLAYED}</option>
              </Select>
            </Label>
            <Label>
              מספר משלוחים במשמרת
              <Input type="number" min="0" value={f.deliveries} onChange={(e) => set({ deliveries: e.target.value })} name="deliveries" />
            </Label>
            <Label>
              תשלום ממוצע למשלוח (₪)
              <Input type="number" min="0" step="0.01" value={f.avgPayment} onChange={(e) => set({ avgPayment: e.target.value })} name="avgPayment" />
            </Label>
            <Label>
              משך משמרת (שעות)
              <Input type="number" min="0.5" step="0.5" value={f.shiftHours} onChange={(e) => set({ shiftHours: e.target.value })} name="shiftHours" />
            </Label>
            <Label>
              יעד למשמרת (₪, אותו בסיס)
              <Input type="number" min="0" value={f.target} onChange={(e) => set({ target: e.target.value })} name="target" />
            </Label>
            <Label>
              זמן ממוצע למשלוח (דקות)
              <Input type="number" min="1" placeholder="לא ידוע" value={f.minutes} onChange={(e) => set({ minutes: e.target.value })} name="minutes" />
              <Hint>ריק = לא ידוע. לא נמדד עדיין — אין להמציא.</Hint>
            </Label>
            <Label>
              ק״מ ממוצע למשלוח
              <Input type="number" min="0" step="0.1" placeholder="לא ידוע" value={f.km} onChange={(e) => set({ km: e.target.value })} name="km" />
            </Label>
            <Label>
              עלות רכב לק״מ (₪)
              <Input type="number" min="0" step="0.01" placeholder="לא הוגדר" value={f.costPerKm} onChange={(e) => set({ costPerKm: e.target.value })} name="costPerKm" />
            </Label>
          </div>
        </CardContent>
      </Card>

      <Card data-testid="simulation-result">
        <CardHeader>
          <CardTitle>תוצאה</CardTitle>
          {result && <CardDescription>בסיס: {BASIS_LABEL[result.basis]}</CardDescription>}
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {error && <Alert level="error">{error}</Alert>}
          {!result && !error && <p className="text-muted">הזן/י מספר משלוחים ותשלום ממוצע.</p>}
          {result && feasibility && (
            <>
              <Badge variant={feasibility.variant} data-testid="feasibility">{feasibility.text}</Badge>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-2">
                <dt className="text-muted">הכנסה ברוטו למשמרת</dt><dd className="num text-lg font-semibold" data-testid="gross">{formatShekel(result.gross_agorot)}</dd>
                <dt className="text-muted">לשעה</dt><dd className="num">{formatShekel(result.gross_per_hour_agorot)}</dd>
                <dt className="text-muted">עלות נסיעה משוערת</dt><dd className="num">{formatShekel(result.estimated_vehicle_cost_agorot)}</dd>
                <dt className="text-muted">תרומה אחרי רכב, לפני מס</dt><dd className="num">{formatShekel(result.contribution_agorot)}</dd>
                <dt className="text-muted">פער מהיעד</dt>
                <dd className="num font-medium" data-testid="gap">{result.target_reached ? `היעד הושג (+${formatShekel(-result.gap_to_target_agorot)})` : formatShekel(result.gap_to_target_agorot)}</dd>
                <dt className="text-muted">משלוחים נדרשים בממוצע הזה</dt><dd className="num" data-testid="needed">{result.deliveries_needed_at_this_avg ?? "—"}</dd>
                <dt className="text-muted">ממוצע נדרש במספר הזה</dt><dd className="num">{formatShekel(result.avg_needed_at_this_count_agorot)}</dd>
                {result.required_minutes !== null && (<>
                  <dt className="text-muted">זמן נדרש / זמין</dt><dd className="num">{result.required_minutes} / {result.available_minutes} דק׳</dd>
                </>)}
              </dl>
              {result.notes.map((n) => <Alert key={n} level="warning">{n}</Alert>)}
              <p className="text-xs text-muted">
                הכנסה = מספר משלוחים × תשלום ממוצע. &quot;תרומה&quot; אינה רווח נקי (אין מס הכנסה, ביטוח, עלויות קבועות). סימולציה אינה הבטחה שהתרחיש אפשרי.
              </p>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
