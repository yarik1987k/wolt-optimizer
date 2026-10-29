import { prisma } from "@/lib/db";
import { formatShekel } from "@/lib/money";
import { getSettings } from "@/lib/settings";
import { WEEKDAY_LABELS } from "@/lib/types";
import { buildRecordWhere, type Filters } from "./filters";
import { hourBucketKey } from "./metrics";
import { summarize, type Summary } from "./stats";

export type InsightKind = "HOUR" | "WEEKDAY" | "MERCHANT";
export type InsightStatus = "OBSERVATION" | "TEST_CANDIDATE";

export interface InsightRecord {
  eventDate: string;
  eventTime: string;
  weekday: number;
  merchantRaw: string;
  isBundle: boolean;
  amountAgorot: number;
}

export interface Insight {
  key: string;
  kind: InsightKind;
  title: string;
  hypothesis: string;
  group_label: string;
  group: Summary;
  baseline: Summary;
  mean_gap_pct: number;
  median_gap_pct: number;
  /** Most conservative of mean gap, median gap and leave-the-max-out mean gap. */
  robust_gap_pct: number;
  sample_size: number;
  distinct_dates: number;
  confidence_label: string;
  status: InsightStatus;
  score: number;
  uncertainty: string;
  limitations: string[];
}

export interface InsightsResult {
  hypotheses: Insight[]; // up to 3, status TEST_CANDIDATE (n >= minSampleTestCandidate)
  observations: Insight[]; // up to 3, n < minSampleTestCandidate, observation only
  explanation: string | null;
  analyzed_record_count: number;
  method: string;
}

export const BASE_LIMITATIONS = [
  "שעת הרישום בדוח אינה בהכרח שעת תחילת המשלוח או ביצועו.",
  "אין בנתונים זמני ביצוע, זמני המתנה או מרחקים — ממוצע לרשומה אינו הכנסה לשעה.",
  "רשומה עשויה להיות מקבץ של כמה משלוחים; המדדים הם לרשומת תשלום ולא למשלוח בודד.",
  "לא בוצע מבחן סטטיסטי; אין לפרש את הפער כמובהק או כקשר סיבתי.",
];

export function confidenceLabel(n: number, minObs: number, minTest: number): string {
  if (n < minObs) return "מדגם קטן — לא להמלצה";
  if (n < minTest) return "אינדיקציה ראשונית";
  return "מדגם סביר לניסוי";
}

const pct = (x: number) => Math.round(x * 1000) / 10;

function describe(kind: InsightKind, rawLabel: string) {
  // Unicode isolates keep "19:00–20:00" in LTR order inside Hebrew sentences.
  const label = kind === "HOUR" ? `\u2066${rawLabel}\u2069` : rawLabel;
  if (kind === "HOUR")
    return {
      title: `חלון ${label}: תשלום ממוצע גבוה יותר לרשומה`,
      hypothesis: `השערה לבדיקה: משמרות שכוללות את חלון ${label} עשויות להניב יותר ₪ לשעת משמרת. כדי לבדוק — תעד משמרות שכוללות את החלון (ניסוי) ומשמרות ללא החלון (ביקורת) והשווה ₪/שעה.`,
    };
  if (kind === "WEEKDAY")
    return {
      title: `יום ${label}: תשלום ממוצע גבוה יותר לרשומה`,
      hypothesis: `השערה לבדיקה: משמרת ביום ${label} עשויה להניב יותר ₪ לשעת משמרת. כדי לבדוק — תעד משמרות ביום ${label} (ניסוי) מול ימים אחרים באותן שעות (ביקורת).`,
    };
  return {
    title: `בית עסק "${label}": תשלום ממוצע גבוה יותר לרשומה`,
    hypothesis: `השערה לבדיקה: עבודה בסביבת האיסוף של "${label}" עשויה להניב רשומות ששוות יותר. אין בכך המלצה על אזור — מיקום בית העסק לבדו אינו מעיד על אזור מיטבי; יש לבדוק ₪/שעת משמרת בפועל.`,
  };
}

/**
 * Compares every group (hour window / weekday / single merchant) with the baseline of all
 * *other* records in the filtered range, then ranks by a conservative gap weighted by
 * sample size and stability. Pure function — no I/O.
 */
export function generateInsights(
  records: InsightRecord[],
  opts: { minSampleObservation: number; minSampleTestCandidate: number; windowMinutes?: number; minGapPct?: number },
): InsightsResult {
  const windowMinutes = opts.windowMinutes ?? 60;
  const minGap = (opts.minGapPct ?? 5) / 100;
  const dims: { kind: InsightKind; keyOf: (r: InsightRecord) => { key: string; label: string } | null }[] = [
    { kind: "HOUR", keyOf: (r) => hourBucketKey(r.eventTime, windowMinutes) },
    { kind: "WEEKDAY", keyOf: (r) => ({ key: String(r.weekday), label: WEEKDAY_LABELS[r.weekday] }) },
    // Bundles are excluded: their amount can't be attributed to any single merchant.
    { kind: "MERCHANT", keyOf: (r) => (r.isBundle ? null : { key: r.merchantRaw.trim(), label: r.merchantRaw.trim() }) },
  ];

  const all: Insight[] = [];
  for (const dim of dims) {
    const groups = new Map<string, { label: string; idx: Set<number> }>();
    records.forEach((r, i) => {
      const k = dim.keyOf(r);
      if (!k) return;
      if (!groups.has(k.key)) groups.set(k.key, { label: k.label, idx: new Set() });
      groups.get(k.key)!.idx.add(i);
    });
    for (const [key, g] of groups) {
      const groupVals = [...g.idx].map((i) => records[i].amountAgorot);
      const baseVals = records.filter((_, i) => !g.idx.has(i)).map((r) => r.amountAgorot);
      if (baseVals.length < opts.minSampleObservation) continue;
      const gs = summarize(groupVals);
      const bs = summarize(baseVals);
      if (!bs.mean || !bs.median || gs.mean == null || gs.median == null) continue;
      const meanGap = (gs.mean - bs.mean) / bs.mean;
      const medianGap = (gs.median - bs.median) / bs.median;
      // Outlier guardrail: the gap must survive removing the group's single largest record.
      const withoutMax = gs.n >= 2 ? (gs.sum - gs.max!) / (gs.n - 1) : gs.mean;
      const lmoGap = (withoutMax - bs.mean) / bs.mean;
      const robustGap = Math.min(meanGap, medianGap, lmoGap);
      const cv = gs.sd != null && gs.mean > 0 ? gs.sd / gs.mean : 1;
      // A group whose records all come from one or two dates may just reflect a single good day.
      const distinctDates = new Set([...g.idx].map((i) => records[i].eventDate)).size;
      const dateSpread = Math.sqrt(Math.min(distinctDates, 3) / 3);
      const score = (robustGap * Math.sqrt(Math.min(gs.n, 30)) * dateSpread) / (1 + cv);
      // Only the higher, configured test threshold promotes a group to a hypothesis for an experiment.
      const status: InsightStatus = gs.n >= opts.minSampleTestCandidate && robustGap > 0 ? "TEST_CANDIDATE" : "OBSERVATION";
      const d = describe(dim.kind, g.label);
      const limitations = [...BASE_LIMITATIONS];
      if (dim.kind === "MERCHANT") limitations.push("ממוצע לפי בית עסק מבוסס רק על רשומות של בית עסק יחיד; מקבצים לא נכללו.");
      if (distinctDates < 3)
        limitations.unshift(
          distinctDates === 1
            ? "כל הרשומות בקבוצה הן מתאריך אחד — ייתכן שהפער משקף יום מסוים ולא דפוס חוזר."
            : `הרשומות בקבוצה הן מ-${distinctDates} תאריכים בלבד — ייתכן שהפער משקף ימים מסוימים ולא דפוס חוזר.`,
        );
      if (gs.n < opts.minSampleObservation) limitations.unshift("תצפית בלבד — המסקנה דורשת בדיקת שטח.");
      else if (gs.n < opts.minSampleTestCandidate)
        limitations.unshift(`אינדיקציה ראשונית בלבד — פחות מ-${opts.minSampleTestCandidate} רשומות; לא מספיק להשערה לניסוי.`);
      all.push({
        key: `${dim.kind}:${key}`,
        kind: dim.kind,
        title: d.title,
        hypothesis: d.hypothesis,
        group_label: g.label,
        group: gs,
        baseline: bs,
        mean_gap_pct: pct(meanGap),
        median_gap_pct: pct(medianGap),
        robust_gap_pct: pct(robustGap),
        sample_size: gs.n,
        distinct_dates: distinctDates,
        confidence_label:
          confidenceLabel(gs.n, opts.minSampleObservation, opts.minSampleTestCandidate) +
          (gs.n >= opts.minSampleObservation && distinctDates === 1 ? " (תאריך יחיד)" : ""),
        status,
        score,
        uncertainty:
          `טווח בקבוצה: ${formatShekel(gs.min)}–${formatShekel(gs.max)}; ` +
          (gs.sd != null ? `סטיית תקן ${formatShekel(gs.sd)}. ` : "אין סטיית תקן (רשומה אחת). ") +
          `קו בסיס: ${bs.n} רשומות, טווח ${formatShekel(bs.min)}–${formatShekel(bs.max)}.`,
        limitations,
      });
    }
  }

  const hypotheses = all
    .filter((i) => i.status === "TEST_CANDIDATE" && i.robust_gap_pct / 100 >= minGap)
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);
  // Small samples need a larger gap to be shown at all.
  const observations = all
    .filter((i) => i.status === "OBSERVATION" && i.robust_gap_pct / 100 >= (i.sample_size < opts.minSampleObservation ? minGap * 2 : minGap))
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);

  let explanation: string | null = null;
  if (records.length === 0) explanation = "אין רשומות משלוח בטווח הסינון — לא ניתן ליצור השערות.";
  else if (hypotheses.length < 3)
    explanation =
      `נמצאו ${hypotheses.length} השערות בלבד עם בסיס של לפחות ${opts.minSampleTestCandidate} רשומות ופער חיובי יציב (≥${opts.minGapPct ?? 5}%) ` +
      `לעומת שאר הרשומות. כדי לקבל השערות נוספות נדרשים נתונים נוספים (דוחות נוספים ומשמרות מתועדות).`;

  return {
    hypotheses,
    observations,
    explanation,
    analyzed_record_count: records.length,
    method:
      `כל קבוצה (חלון של ${windowMinutes} דקות לפי שעת הרישום, יום בשבוע, בית עסק יחיד) מושווית לכל שאר רשומות המשלוח בטווח הסינון. ` +
      `הפער השמרני הוא הנמוך מבין: פער ממוצעים, פער חציונים ופער ממוצע ללא הרשומה הגבוהה בקבוצה. ` +
      `הדירוג: פער שמרני × √n × פיזור תאריכים ÷ (1 + מקדם השונות).`,
  };
}

export async function getInsights(filters: Filters, windowMinutes = 60): Promise<InsightsResult> {
  const settings = await getSettings();
  const records = await prisma.deliveryRecord.findMany({
    where: buildRecordWhere(filters, ["DELIVERY"]),
    select: { eventDate: true, eventTime: true, weekday: true, merchantRaw: true, isBundle: true, amountAgorot: true },
  });
  return generateInsights(records, {
    minSampleObservation: settings.minSampleObservation,
    minSampleTestCandidate: settings.minSampleTestCandidate,
    windowMinutes,
  });
}
