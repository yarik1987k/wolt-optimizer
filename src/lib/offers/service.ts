import { z } from "zod";
import { prisma } from "@/lib/db";
import { localToUtcMs } from "@/lib/time";
import { analyzeOffers, applyOfferFilters, jerusalemParts, type OfferFilters, type OfferPoint } from "./analytics";
import { generateOfferHypotheses } from "./hypotheses";
import { getSettings } from "@/lib/settings";

const num = z.coerce.number().finite();

/** Lenient parsing of URL filters (₪ and km in the URL, agorot/meters internally). */
export function parseOfferFilters(sp: Record<string, string | string[] | undefined>): OfferFilters {
  const get = (k: string) => {
    const v = sp[k];
    const s = Array.isArray(v) ? v[0] : v;
    return s === undefined || s === "" ? undefined : s;
  };
  const date = (k: string) => (/^\d{4}-\d{2}-\d{2}$/.test(get(k) ?? "") ? get(k) : undefined);
  const n = (k: string, scale = 1) => {
    const r = num.safeParse(get(k));
    return r.success ? Math.round(r.data * scale) : undefined;
  };
  const hour = (k: string) => {
    const v = n(k);
    return v !== undefined && v >= 0 && v <= 23 ? v : undefined;
  };
  return {
    from: date("from"),
    to: date("to"),
    hourFrom: hour("hourFrom"),
    hourTo: hour("hourTo"),
    minAmountAgorot: n("minAmount", 100),
    maxAmountAgorot: n("maxAmount", 100),
    minDistanceMeters: n("minKm", 1000),
    maxDistanceMeters: n("maxKm", 1000),
    detection: get("detection") === "ALL" ? "ALL" : "CONFIRMED_CARD",
  };
}

export async function loadOffers() {
  return prisma.offer.findMany({
    orderBy: { firstSeenAtUtc: "desc" },
    include: { shift: { select: { id: true, date: true, startTime: true, endTime: true } }, paymentRecord: true },
  });
}
export type OfferRow = Awaited<ReturnType<typeof loadOffers>>[number];

export const toPoint = (o: OfferRow): OfferPoint => ({
  id: o.id,
  firstSeenMs: o.firstSeenAtUtc.getTime(),
  amountAgorot: o.amountAgorot,
  distanceMeters: o.distanceMeters,
  onScreenSeconds: o.onScreenSeconds,
  detection: o.detection as OfferPoint["detection"],
  verification: o.verification as OfferPoint["verification"],
});

export async function getOfferAnalytics(filters: OfferFilters) {
  const rows = await loadOffers();
  const all = rows.map(toPoint);
  const filtered = applyOfferFilters(all, filters);
  const ids = new Set(filtered.map((p) => p.id));
  const settings = await getSettings();
  return {
    filters,
    rows: rows.filter((r) => ids.has(r.id)),
    analytics: analyzeOffers(filtered),
    hypotheses: generateOfferHypotheses(filtered, {
      minSampleObservation: settings.minSampleObservation,
      minSampleTestCandidate: settings.minSampleTestCandidate,
    }),
    // Totals across BOTH detection levels, so partial reads are always visible separately.
    totals: {
      confirmed: all.filter((p) => p.detection === "CONFIRMED_CARD").length,
      partial: all.filter((p) => p.detection === "PARTIAL_CARD").length,
    },
  };
}

export class OfferUpdateError extends Error {
  constructor(message: string, public status = 422) {
    super(message);
  }
}

export const offerPatchSchema = z
  .object({
    verification: z.enum(["UNVERIFIED", "USER_VERIFIED"]).optional(),
    verificationSource: z.string().trim().max(500).optional(),
    outcome: z.enum(["UNKNOWN", "ACCEPTED", "DECLINED", "EXPIRED"]).optional(),
    completed: z.boolean().nullable().optional(),
    shiftId: z.string().nullable().optional(),
    paymentRecordId: z.string().nullable().optional(),
  })
  .strict();

/** Manual labels only. Nothing here is inferred from the screen. */
export async function updateOffer(id: string, body: unknown) {
  const p = offerPatchSchema.parse(body);
  const offer = await prisma.offer.findUniqueOrThrow({ where: { id } });
  const data: Record<string, unknown> = {};
  if (p.verification === "USER_VERIFIED") {
    if (!p.verificationSource) throw new OfferUpdateError("אימות ידני מחייב לציין מקור (למשל: ראיתי את ההצעה ב-Wolt והסכום תואם)");
    Object.assign(data, { verification: "USER_VERIFIED", verificationSource: p.verificationSource, verifiedAt: new Date() });
  } else if (p.verification === "UNVERIFIED") {
    Object.assign(data, { verification: "UNVERIFIED", verificationSource: null, verifiedAt: null });
  }
  if (p.outcome) Object.assign(data, { outcome: p.outcome, outcomeSource: p.outcome === "UNKNOWN" ? null : "USER_LABEL" });
  if (p.completed !== undefined) {
    if (p.completed === true && (p.outcome ?? offer.outcome) !== "ACCEPTED") throw new OfferUpdateError("משלוח שבוצע חייב להיות הצעה שסומנה כמאושרת");
    data.completed = p.completed;
  }
  if (p.shiftId !== undefined) {
    if (p.shiftId) await prisma.shift.findUniqueOrThrow({ where: { id: p.shiftId } });
    data.shiftId = p.shiftId;
  }
  if (p.paymentRecordId !== undefined) {
    if (p.paymentRecordId) {
      const rec = await prisma.deliveryRecord.findUniqueOrThrow({ where: { id: p.paymentRecordId } });
      if (rec.type !== "DELIVERY") throw new OfferUpdateError("אפשר לקשר רק לרשומת משלוח (לא לבונוס או תוספת)");
      const taken = await prisma.offer.findFirst({ where: { paymentRecordId: p.paymentRecordId, NOT: { id } } });
      if (taken) throw new OfferUpdateError("רשומת התשלום כבר מקושרת להצעה אחרת", 409);
      if ((p.completed ?? offer.completed) !== true) throw new OfferUpdateError("קישור לתשלום מחייב לסמן קודם שהמשלוח בוצע");
    }
    data.paymentRecordId = p.paymentRecordId;
  }
  return prisma.offer.update({ where: { id }, data });
}

/** Shifts whose window contains the offer's local time (suggestion only). */
export async function shiftCandidates(firstSeenMs: number) {
  const shifts = await prisma.shift.findMany({ orderBy: [{ date: "desc" }, { startTime: "desc" }] });
  return shifts.filter((s) => {
    const start = localToUtcMs(s.date, s.startTime);
    const [ed, et] = s.endedAt.split("T");
    const end = localToUtcMs(ed, et);
    return firstSeenMs >= start && firstSeenMs <= end;
  });
}

/** Delivery records of the same local date, at or after the offer time (manual pick; never auto-matched). */
export async function paymentCandidates(firstSeenMs: number) {
  const { date, hour, minute } = jerusalemParts(firstSeenMs);
  const time = `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00`;
  return prisma.deliveryRecord.findMany({
    where: { type: "DELIVERY", eventDate: date, eventTime: { gte: time }, offer: null },
    orderBy: { eventTimestampLocal: "asc" },
    take: 20,
  });
}

/** Inputs for the simulator presets. Two bases, never combined. */
export async function simulatorPresets() {
  const deliveries = await prisma.deliveryRecord.aggregate({ where: { type: "DELIVERY" }, _avg: { amountAgorot: true }, _count: true });
  const offers = await prisma.offer.aggregate({ where: { detection: "CONFIRMED_CARD" }, _avg: { amountAgorot: true, distanceMeters: true }, _count: true });
  const settings = await getSettings();
  return {
    reports: deliveries._count ? { avgAgorot: Math.round(deliveries._avg.amountAgorot!), n: deliveries._count } : null,
    offers: offers._count
      ? {
          avgAgorot: Math.round(offers._avg.amountAgorot!),
          avgKm: offers._avg.distanceMeters === null ? null : Math.round(offers._avg.distanceMeters / 100) / 10,
          n: offers._count,
        }
      : null,
    vehicleCostPerKmAgorot: settings.vehicleCostPerKmAgorot,
  };
}
