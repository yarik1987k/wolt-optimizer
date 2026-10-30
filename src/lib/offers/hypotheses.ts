import { formatShekel } from "@/lib/money";
import { summarize } from "@/lib/analytics/stats";
import { confidenceLabel } from "@/lib/analytics/insights";
import { jerusalemParts, type OfferPoint } from "./analytics";

export interface OfferHypothesis {
  key: string;
  title: string;
  evidence: string;
  sample_size: number;
  effect: string;
  limitations: string[];
  confidence_label: string;
  testable: boolean; // false = observation only
}

export interface OfferHypothesesResult {
  hypotheses: OfferHypothesis[];
  explanation: string | null;
}

export const OFFER_LIMITATIONS = [
  "אלה הצעות שנצפו על המסך, לא כל ההצעות שנשלחו — לא הוכח שכל ההצעות נקלטו.",
  "לא ידוע כמה זמן האפליקציה ניטרה בכל שעה, ולכן ספירת הצעות לשעה אינה קצב הצעות.",
  "סכום ההצעה מוצג כפי שבאפליקציה; לא ידוע אם כולל מע״מ — אין להשוות ישירות לדוחות.",
  "קשר סטטיסטי אינו סיבתיות ואינו הבטחה להכנסה.",
];

function pearson(xs: number[], ys: number[]): number | null {
  const n = xs.length;
  if (n < 3) return null;
  const mx = xs.reduce((a, b) => a + b, 0) / n;
  const my = ys.reduce((a, b) => a + b, 0) / n;
  let sxy = 0, sxx = 0, syy = 0;
  for (let i = 0; i < n; i++) {
    sxy += (xs[i] - mx) * (ys[i] - my);
    sxx += (xs[i] - mx) ** 2;
    syy += (ys[i] - my) ** 2;
  }
  return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : null;
}

/**
 * Hypotheses over observed offers. With few offers everything is labeled as an observation;
 * nothing is presented as proven or causal.
 */
export function generateOfferHypotheses(
  points: OfferPoint[],
  opts: { minSampleObservation: number; minSampleTestCandidate: number; expensiveThresholdAgorot?: number; targetAgorot?: number; shiftHours?: number },
): OfferHypothesesResult {
  const label = (n: number) => confidenceLabel(n, opts.minSampleObservation, opts.minSampleTestCandidate);
  const testable = (n: number) => n >= opts.minSampleObservation;
  const out: OfferHypothesis[] = [];
  const n = points.length;
  if (n === 0) return { hypotheses: [], explanation: "אין הצעות שנצפו בטווח הסינון." };

  const byHour = new Map<number, OfferPoint[]>();
  for (const o of points) {
    const h = jerusalemParts(o.firstSeenMs).hour;
    if (!byHour.has(h)) byHour.set(h, []);
    byHour.get(h)!.push(o);
  }
  const all = summarize(points.map((o) => o.amountAgorot));

  // 1) Hour with higher average offer amount, vs all other offers.
  if (byHour.size >= 2) {
    const ranked = [...byHour.entries()]
      .map(([h, list]) => {
        const g = summarize(list.map((o) => o.amountAgorot));
        const rest = summarize(points.filter((o) => !list.includes(o)).map((o) => o.amountAgorot));
        return { h, g, rest, gap: rest.mean ? (g.mean! - rest.mean) / rest.mean : 0 };
      })
      .sort((a, b) => b.gap - a.gap);
    const top = ranked[0];
    if (top.gap > 0)
      out.push({
        key: `HOUR_AMOUNT:${top.h}`,
        title: `שעה ${String(top.h).padStart(2, "0")}:00: סכום ממוצע גבוה יותר להצעה`,
        evidence: `ממוצע ${formatShekel(top.g.mean)} (n=${top.g.n}) מול ${formatShekel(top.rest.mean)} בשאר השעות (n=${top.rest.n}).`,
        sample_size: top.g.n,
        effect: `+${(top.gap * 100).toFixed(1)}%`,
        limitations: OFFER_LIMITATIONS,
        confidence_label: label(top.g.n),
        testable: testable(top.g.n) && testable(top.rest.n),
      });
  }

  // 2) Hours with more observed offers.
  if (byHour.size >= 2) {
    const [h, list] = [...byHour.entries()].sort((a, b) => b[1].length - a[1].length)[0];
    out.push({
      key: `HOUR_COUNT:${h}`,
      title: `שעה ${String(h).padStart(2, "0")}:00: נצפו בה הכי הרבה הצעות`,
      evidence: `${list.length} מתוך ${n} הצעות שנצפו.`,
      sample_size: list.length,
      effect: `${((list.length / n) * 100).toFixed(0)}% מההצעות שנצפו`,
      limitations: [OFFER_LIMITATIONS[1], ...OFFER_LIMITATIONS.filter((_, i) => i !== 1)],
      confidence_label: label(list.length),
      testable: false, // monitoring exposure per hour is unknown
    });
  }

  // 3) Distance vs payment.
  const wd = points.filter((o) => o.distanceMeters !== null && o.distanceMeters > 0);
  if (wd.length >= 2) {
    const r = pearson(wd.map((o) => o.distanceMeters!), wd.map((o) => o.amountAgorot));
    const perKm = summarize(wd.map((o) => o.amountAgorot / (o.distanceMeters! / 1000)));
    out.push({
      key: "DISTANCE_PAYMENT",
      title: "קשר בין מרחק לתשלום",
      evidence:
        (r === null ? "מתאם לא חושב (נדרשות לפחות 3 הצעות עם מרחק). " : `מתאם פירסון r=${r.toFixed(2)}. `) +
        `₪ לק״מ: חציון ${formatShekel(perKm.median)}, טווח ${formatShekel(perKm.min)}–${formatShekel(perKm.max)}.`,
      sample_size: wd.length,
      effect: r === null ? "לא חושב" : `r=${r.toFixed(2)}`,
      limitations: ["המרחק הוא כפי שמוצג בהצעה; אין זמני נסיעה בפועל.", ...OFFER_LIMITATIONS],
      confidence_label: label(wd.length),
      testable: testable(wd.length) && r !== null,
    });
  }

  // 4) Expensive offers by hour window.
  const thr = opts.expensiveThresholdAgorot ?? 4000;
  const expensive = points.filter((o) => o.amountAgorot >= thr);
  if (expensive.length > 0 && byHour.size >= 2) {
    const [h, list] = [...byHour.entries()]
      .map(([h, l]) => [h, l] as const)
      .sort((a, b) => b[1].filter((o) => o.amountAgorot >= thr).length / b[1].length - a[1].filter((o) => o.amountAgorot >= thr).length / a[1].length)[0];
    const share = list.filter((o) => o.amountAgorot >= thr).length / list.length;
    out.push({
      key: `EXPENSIVE_HOUR:${h}`,
      title: `הצעות של ${formatShekel(thr)}+ נפוצות יותר בשעה ${String(h).padStart(2, "0")}:00`,
      evidence: `${(share * 100).toFixed(0)}% מההצעות בשעה זו, מול ${((expensive.length / n) * 100).toFixed(0)}% בכלל ההצעות.`,
      sample_size: list.length,
      effect: `${(share * 100).toFixed(0)}% מול ${((expensive.length / n) * 100).toFixed(0)}%`,
      limitations: OFFER_LIMITATIONS,
      confidence_label: label(list.length),
      testable: testable(list.length),
    });
  }

  // 5) Could the volume of observed offers support a higher shift income? (upper bound, math only)
  const days = new Set(points.map((o) => jerusalemParts(o.firstSeenMs).date));
  if (all.mean !== null) {
    const target = opts.targetAgorot ?? 40000;
    const needed = Math.ceil(target / all.mean);
    out.push({
      key: "VOLUME_VS_TARGET",
      title: "האם כמות ההצעות מאפשרת הכנסה גבוהה יותר?",
      evidence: `בממוצע ${formatShekel(all.mean)} להצעה שנצפתה, נדרשות ~${needed} הצעות כאלה כדי להגיע ל-${formatShekel(target)} במשמרת. נצפו ${n} הצעות ב-${days.size} ימים.`,
      sample_size: n,
      effect: `${needed} הצעות למשמרת ליעד`,
      limitations: [
        "חישוב מתמטי בלבד: לא ידוע כמה הצעות אפשר לבצע בזמן משמרת (זמני ביצוע לא נמדדו).",
        "לא ידוע אם ההצעות שנצפו התקבלו או נדחו.",
        ...OFFER_LIMITATIONS,
      ],
      confidence_label: label(n),
      testable: false,
    });
  }

  const testableCount = out.filter((h) => h.testable).length;
  return {
    hypotheses: out,
    explanation:
      testableCount === 0
        ? `נצפו ${n} הצעות בלבד — כל הממצאים הם תצפיות ולא המלצות. נדרשות לפחות ${opts.minSampleObservation} הצעות בכל קבוצה כדי לבדוק השערה.`
        : null,
  };
}
