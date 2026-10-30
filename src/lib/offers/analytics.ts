import { summarize, type Summary } from "@/lib/analytics/stats";
import { TIME_ZONE } from "@/lib/time";

/** An observed offer as the analytics need it. Amounts: agorot as displayed (VAT basis unknown). */
export interface OfferPoint {
  id: string;
  firstSeenMs: number;
  amountAgorot: number;
  distanceMeters: number | null;
  onScreenSeconds: number;
  detection: "CONFIRMED_CARD" | "PARTIAL_CARD";
  verification: "UNVERIFIED" | "USER_VERIFIED";
}

export interface OfferFilters {
  from?: string; // YYYY-MM-DD (Asia/Jerusalem)
  to?: string;
  hourFrom?: number;
  hourTo?: number;
  minAmountAgorot?: number;
  maxAmountAgorot?: number;
  minDistanceMeters?: number;
  maxDistanceMeters?: number;
  detection?: "CONFIRMED_CARD" | "ALL"; // default: confirmed only
}

const partsFmt = new Intl.DateTimeFormat("en-CA", {
  timeZone: TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** Local Asia/Jerusalem date + hour of a UTC instant. */
export function jerusalemParts(ms: number): { date: string; hour: number; minute: number } {
  const p = Object.fromEntries(partsFmt.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, hour: Number(p.hour), minute: Number(p.minute) };
}

export function applyOfferFilters(points: OfferPoint[], f: OfferFilters): OfferPoint[] {
  return points.filter((o) => {
    if ((f.detection ?? "CONFIRMED_CARD") === "CONFIRMED_CARD" && o.detection !== "CONFIRMED_CARD") return false;
    const { date, hour } = jerusalemParts(o.firstSeenMs);
    if (f.from && date < f.from) return false;
    if (f.to && date > f.to) return false;
    if (f.hourFrom !== undefined && hour < f.hourFrom) return false;
    if (f.hourTo !== undefined && hour > f.hourTo) return false;
    if (f.minAmountAgorot !== undefined && o.amountAgorot < f.minAmountAgorot) return false;
    if (f.maxAmountAgorot !== undefined && o.amountAgorot > f.maxAmountAgorot) return false;
    // A distance filter can only keep offers whose distance is known.
    if (f.minDistanceMeters !== undefined && (o.distanceMeters === null || o.distanceMeters < f.minDistanceMeters)) return false;
    if (f.maxDistanceMeters !== undefined && (o.distanceMeters === null || o.distanceMeters > f.maxDistanceMeters)) return false;
    return true;
  });
}

export const PRICE_THRESHOLDS_SHEKEL = [25, 30, 35, 40, 45, 50, 60];

export interface OfferAnalytics {
  observed_offer_count: number;
  verified_offer_count: number;
  confirmed_card_count: number;
  partial_card_count: number;
  amount: Summary; // agorot as displayed
  distance_m: Summary; // only offers with a known distance
  offers_with_distance: number;
  /** sum(amount)/sum(km) over offers with a known distance, agorot per km. */
  agorot_per_km_aggregate: number | null;
  /** per-offer ₪/km, summarized. */
  agorot_per_km: Summary;
  by_hour: { hour: number; n: number; mean_amount_agorot: number; median_amount_agorot: number; sum_agorot: number }[];
  /** ₪5 bins: [lower, upper) in agorot. */
  distribution: { from_agorot: number; to_agorot: number; n: number }[];
  /** Minutes between consecutive observed offers on the same local date. */
  gaps_minutes: Summary;
  thresholds: { threshold_shekel: number; n_at_or_above: number; share: number | null }[];
  on_screen_seconds: Summary;
}

export function analyzeOffers(points: OfferPoint[]): OfferAnalytics {
  const amounts = points.map((o) => o.amountAgorot);
  const withDistance = points.filter((o): o is OfferPoint & { distanceMeters: number } => o.distanceMeters !== null && o.distanceMeters > 0);
  const sumKm = withDistance.reduce((a, o) => a + o.distanceMeters, 0) / 1000;

  const hours = new Map<number, number[]>();
  for (const o of points) {
    const h = jerusalemParts(o.firstSeenMs).hour;
    if (!hours.has(h)) hours.set(h, []);
    hours.get(h)!.push(o.amountAgorot);
  }

  const BIN = 500;
  const bins = new Map<number, number>();
  for (const a of amounts) {
    const b = Math.floor(a / BIN) * BIN;
    bins.set(b, (bins.get(b) ?? 0) + 1);
  }

  const gaps: number[] = [];
  const sorted = [...points].sort((a, b) => a.firstSeenMs - b.firstSeenMs);
  for (let i = 1; i < sorted.length; i++) {
    if (jerusalemParts(sorted[i].firstSeenMs).date !== jerusalemParts(sorted[i - 1].firstSeenMs).date) continue;
    gaps.push((sorted[i].firstSeenMs - sorted[i - 1].firstSeenMs) / 60_000);
  }

  return {
    observed_offer_count: points.length,
    verified_offer_count: points.filter((o) => o.verification === "USER_VERIFIED").length,
    confirmed_card_count: points.filter((o) => o.detection === "CONFIRMED_CARD").length,
    partial_card_count: points.filter((o) => o.detection === "PARTIAL_CARD").length,
    amount: summarize(amounts),
    distance_m: summarize(withDistance.map((o) => o.distanceMeters)),
    offers_with_distance: withDistance.length,
    agorot_per_km_aggregate: sumKm > 0 ? withDistance.reduce((a, o) => a + o.amountAgorot, 0) / sumKm : null,
    agorot_per_km: summarize(withDistance.map((o) => o.amountAgorot / (o.distanceMeters / 1000))),
    by_hour: [...hours.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([hour, v]) => {
        const s = summarize(v);
        return { hour, n: s.n, mean_amount_agorot: s.mean!, median_amount_agorot: s.median!, sum_agorot: s.sum };
      }),
    distribution: [...bins.entries()].sort((a, b) => a[0] - b[0]).map(([b, n]) => ({ from_agorot: b, to_agorot: b + BIN, n })),
    gaps_minutes: summarize(gaps),
    thresholds: PRICE_THRESHOLDS_SHEKEL.map((t) => {
      const n = amounts.filter((a) => a >= t * 100).length;
      return { threshold_shekel: t, n_at_or_above: n, share: amounts.length ? n / amounts.length : null };
    }),
    on_screen_seconds: summarize(points.map((o) => o.onScreenSeconds)),
  };
}
