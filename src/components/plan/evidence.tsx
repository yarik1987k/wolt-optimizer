import { Badge } from "@/components/ui/badge";
import type { Evidence } from "@/lib/plan/decisions";

const LABELS: Record<Evidence, { text: string; variant: "success" | "warning" | "default" }> = {
  MEASURED: { text: "נמדד", variant: "success" },
  REPEATED: { text: "חוזר על עצמו", variant: "success" },
  TESTED: { text: "נבדק", variant: "success" },
  HYPOTHESIS: { text: "השערה", variant: "warning" },
  ESTIMATE: { text: "הערכה", variant: "warning" },
  NOT_ENOUGH_DATA: { text: "חסרים נתונים", variant: "default" },
};

/** How much a number can be trusted — shown next to every figure. */
export function EvidenceBadge({ e }: { e: Evidence }) {
  const l = LABELS[e];
  return <Badge variant={l.variant}>{l.text}</Badge>;
}

export function Shekel({ agorot }: { agorot: number }) {
  return <span dir="ltr">₪{(agorot / 100).toFixed(2)}</span>;
}

/** A money range, left-to-right; the web font draws an en dash like a quote mark, so a spaced hyphen is used. */
export function ShekelRange({ low, high, negative = false }: { low: number; high: number; negative?: boolean }) {
  const sign = negative ? "−" : "";
  return Math.abs(high - low) < 100 ? (
    <span dir="ltr">{sign}₪{((low + high) / 200).toFixed(2)}</span>
  ) : (
    <span dir="ltr">
      {sign}₪{(low / 100).toFixed(2)} - {sign}₪{(high / 100).toFixed(2)}
    </span>
  );
}

/** A clock-time range kept left-to-right inside Hebrew text. */
export function TimeRange({ from, to }: { from: string; to: string }) {
  return <span dir="ltr">{from} - {to}</span>;
}
