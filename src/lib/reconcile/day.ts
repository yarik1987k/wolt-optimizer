import { prisma } from "@/lib/db";
import { agorotToShekelString } from "@/lib/money";
import { addDays, localToUtcMs } from "@/lib/time";

/**
 * Reconciles what the Collector detected on one local day against Wolt's own statistics.
 * Wolt's numbers are ground truth for the DAY TOTAL only: a gap is reported with the evidence for
 * its possible causes — never closed by inventing delivery records.
 *
 * Terms: an OFFER is a card; an ACCEPTED OFFER may add several ORDERS; offers overlap (an offer can be
 * accepted while earlier orders are still on board), so they group into CHAINS; a COMPLETED DELIVERY
 * is what Wolt counts. Orders are counted per accepted offer as the NEW orders it added to the route
 * (route drop-offs at acceptance − carried orders), so a carried order is never counted twice.
 */

export interface OfficialDay {
  day: string;
  completedDeliveries: number;
  earningsAgorot: number | null;
  earningsWithoutTipsAgorot: number | null;
  tipsAgorot: number | null;
  distanceMeters: number | null;
  distanceDefinition: string;
  source: string;
}

export interface TripForReconcile {
  startedAtMs: number;
  completedAtMs: number | null;
  endEvidence: string;
  offerAmountsAgorot: number[];
  orderEvidence: string | null; // SINGLE_ORDER | MULTI_ORDER | CHANGED | NO_TIMELINE | null (older export)
  /** Gross route drop-offs (includes carried orders) — kept for reference, not summed. */
  ordersLow: number | null;
  ordersHigh: number | null;
  cardOrderCount: number | null;
  chainKey: string | null;
  acceptContext: string | null;
  carriedOrders: number | null;
  carriedLink: string | null;
  newOrdersLow: number | null;
  newOrdersHigh: number | null;
}

/**
 * RECONCILED — exact match with no uncertain link; CONSISTENT_UNVERIFIED — Wolt's totals fall inside the
 * detected range but some links are uncertain (not validated); UNRECONCILED — outside the range.
 */
export type ReconcileStatus = "RECONCILED" | "CONSISTENT_UNVERIFIED" | "UNRECONCILED" | "NO_OFFICIAL_STATS";

export interface Reconciliation {
  day: string;
  official: OfficialDay | null;
  detectedTrips: number;
  detectedChains: number;
  /** Order count range supported by the route timelines; null when some trip has no order evidence. */
  detectedOrdersLow: number | null;
  detectedOrdersHigh: number | null;
  tripsWithoutOrderEvidence: number;
  tripsWithUnknownEnd: number;
  acceptedOfferEarningsAgorot: number;
  /** official − detected; a range because the detected order count is a range. */
  deliveryDifference: { min: number; max: number } | null;
  earningsDifferenceAgorot: number | null;
  status: ReconcileStatus;
  /** Facts the data supports. */
  findings: string[];
  /** What is still unexplained. */
  unresolved: string[];
}

export function reconcile(day: string, official: OfficialDay | null, trips: TripForReconcile[]): Reconciliation {
  const noEvidence = trips.filter((t) => t.newOrdersLow === null || t.newOrdersHigh === null);
  const low = noEvidence.length ? null : trips.reduce((s, t) => s + (t.newOrdersLow ?? 0), 0);
  const high = noEvidence.length ? null : trips.reduce((s, t) => s + (t.newOrdersHigh ?? 0), 0);
  const chains = new Set(trips.map((t) => t.chainKey ?? `trip:${t.startedAtMs}`));
  const added = trips.filter((t) => t.acceptContext === "ADDED_TO_ACTIVE_ROUTE");
  const shapeLinks = trips.filter((t) => t.carriedLink === "SHAPE_MATCH");
  const ranged = trips.filter((t) => t.newOrdersLow !== null && t.newOrdersLow !== t.newOrdersHigh);
  const earnings = trips.reduce((s, t) => s + t.offerAmountsAgorot.reduce((a, b) => a + b, 0), 0);
  const unknownEnd = trips.filter((t) => t.endEvidence === "UNKNOWN").length;
  const findings: string[] = [];
  const unresolved: string[] = [];

  const earningsDiff = official?.earningsWithoutTipsAgorot == null ? null : official.earningsWithoutTipsAgorot - earnings;
  if (earningsDiff === 0) {
    findings.push(`סכום ההצעות שהתקבלו (₪${agorotToShekelString(earnings)}) שווה בדיוק לרווח בלי טיפים לפי Wolt — לא חסרה אף הצעה שהתקבלה; ההפרש בספירה הוא בהזמנות שבתוך הנסיעות.`);
  } else if (earningsDiff !== null) {
    unresolved.push(`סכום ההצעות שהתקבלו ₪${agorotToShekelString(earnings)} מול ₪${agorotToShekelString(official!.earningsWithoutTipsAgorot!)} לפי Wolt (הפרש ₪${agorotToShekelString(earningsDiff)}) — ייתכן שחסרה נסיעה.`);
  }
  const multi = trips.filter((t) => (t.newOrdersHigh ?? 0) > 1).length;
  if (multi) findings.push(`${multi} מתוך ${trips.length} הצעות שהתקבלו הוסיפו יותר מהזמנה אחת — הצעה אינה הזמנה.`);
  if (added.length) {
    findings.push(
      `${added.length} הצעות התקבלו בזמן שהזמנה קודמת עוד הייתה ברכב (${added.reduce((s, t) => s + (t.carriedOrders ?? 0), 0)} הזמנות שנישאו) — ${trips.length} הצעות ב-${chains.size} שרשראות משלוח. הזמנה שנישאה נספרת פעם אחת בלבד.`,
    );
  }
  const looked = trips.filter((t) => t.orderEvidence === "MULTI_ORDER" && t.cardOrderCount === null).length;
  if (looked) findings.push(`${looked} כרטיסים לא הציגו ״заказы N״ אבל המסלול הראה כמה הזמנות — הכרטיס לבדו לא מספיק לספירה.`);
  const changed = trips.filter((t) => t.orderEvidence === "CHANGED").length;
  if (changed) findings.push(`${changed} נסיעות שבהן מספר נקודות המסירה במסלול השתנה במהלך הנסיעה (הזמנה נוספה/הוסרה, או שהרשימה לא צוירה במלואה).`);
  if (noEvidence.length) unresolved.push(`${noEvidence.length} הצעות בלי ציר מסלול — מספר ההזמנות שהוסיפו לא ידוע.`);
  if (shapeLinks.length) unresolved.push(`${shapeLinks.length} קישורים של הזמנה שנישאה מבוססים על צורת התחנה בלבד (בלי מזהה הזמנה) — לא ודאיים.`);
  for (const t of ranged) unresolved.push(`הצעה ₪${agorotToShekelString(t.offerAmountsAgorot[0] ?? 0)}: ${t.newOrdersLow}–${t.newOrdersHigh} הזמנות (מספר נקודות המסירה במסלול השתנה בזמן האיסוף).`);
  if (unknownEnd) findings.push(`${unknownEnd} נסיעות שזמן הסיום שלהן לא ידוע (נשארות מחוץ לאימון המשך).`);

  let deliveryDifference: Reconciliation["deliveryDifference"] = null;
  let status: ReconcileStatus = "NO_OFFICIAL_STATS";
  if (official) {
    if (low !== null && high !== null) {
      deliveryDifference = { min: official.completedDeliveries - high, max: official.completedDeliveries - low };
      if (low > official.completedDeliveries) {
        unresolved.push(
          `המסלולים מראים ${low}–${high} נקודות מסירה, ו-Wolt ספר ${official.completedDeliveries} משלוחים שהושלמו. סיבות אפשריות שלא אומתו: הזמנה שבוטלה או הועברה אחרי הקבלה, או כמה הזמנות לאותה כתובת שנספרו כמשלוח אחד.`,
        );
      } else if (high < official.completedDeliveries) {
        unresolved.push(`זוהו ${low}–${high} הזמנות, Wolt ספר ${official.completedDeliveries} — חסרות הזמנות שלא נראו על המסך.`);
      } else if (low !== high) {
        findings.push(`הספירה של Wolt (${official.completedDeliveries}) בתוך הטווח שזוהה (${low}–${high}).`);
      }
    }
    if (official.distanceMeters !== null && official.distanceDefinition === "UNVERIFIED") {
      findings.push(`המרחק לפי Wolt (${(official.distanceMeters / 1000).toFixed(1)} ק״מ): לא ידוע אם זה מרחק המשימות בלבד או כל הנסיעה — לא משמש לעלות רכב עד שיאומת.`);
    }
    const exact = low !== null && low === high && low === official.completedDeliveries;
    const inside = low !== null && high !== null && low <= official.completedDeliveries && official.completedDeliveries <= high;
    const earningsOk = earningsDiff === null || earningsDiff === 0;
    status =
      exact && earningsOk && noEvidence.length === 0 && shapeLinks.length === 0
        ? "RECONCILED"
        : inside && earningsOk && noEvidence.length === 0
          ? "CONSISTENT_UNVERIFIED"
          : "UNRECONCILED";
  }

  return {
    day,
    official,
    detectedTrips: trips.length,
    detectedChains: chains.size,
    detectedOrdersLow: low,
    detectedOrdersHigh: high,
    tripsWithoutOrderEvidence: noEvidence.length,
    tripsWithUnknownEnd: unknownEnd,
    acceptedOfferEarningsAgorot: earnings,
    deliveryDifference,
    earningsDifferenceAgorot: earningsDiff,
    status,
    findings,
    unresolved,
  };
}

/** Records (or corrects) Wolt's statistics for a day. */
export async function recordOfficialDay(o: OfficialDay) {
  const { day, ...data } = o;
  return prisma.officialDayStat.upsert({ where: { day }, create: o, update: data });
}

/** Loads the day's trips (by start time, local day) and its official stats, then reconciles. */
export async function reconcileDay(day: string): Promise<Reconciliation> {
  const from = new Date(localToUtcMs(day, "00:00"));
  const to = new Date(localToUtcMs(addDays(day, 1), "00:00"));
  const [official, trips] = await Promise.all([
    prisma.officialDayStat.findUnique({ where: { day } }),
    prisma.collectorTrip.findMany({ where: { startedAtUtc: { gte: from, lt: to } }, orderBy: { startedAtUtc: "asc" } }),
  ]);
  return reconcile(
    day,
    official,
    trips.map((t) => ({
      startedAtMs: t.startedAtUtc.getTime(),
      completedAtMs: t.completedAtUtc?.getTime() ?? null,
      endEvidence: t.endEvidence,
      offerAmountsAgorot: JSON.parse(t.offerAmountsJson) as number[],
      orderEvidence: t.orderEvidence,
      ordersLow: t.ordersLow,
      ordersHigh: t.ordersHigh,
      cardOrderCount: t.cardOrderCount,
      chainKey: t.chainKey,
      acceptContext: t.acceptContext,
      carriedOrders: t.carriedOrders,
      carriedLink: t.carriedLink,
      newOrdersLow: t.newOrdersLow,
      newOrdersHigh: t.newOrdersHigh,
    })),
  );
}
