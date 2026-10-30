import { ZodError } from "zod";
import { prisma } from "@/lib/db";
import { contentHash } from "@/lib/import/deduplicate";
import { collectorExportSchema, COLLECTOR_EXPORT_FORMAT } from "./collector-format";
import { DETECTION_CONFIDENCE, groupObservations, isWeightTag, isWoltLabel, toObservation, type OfferObservationInput } from "./detect";
import { importLocationData, type LocationImportCounts } from "@/lib/hotspots/import";

export class OfferImportError extends Error {}

export const MAX_OFFER_EXPORT_BYTES = 50 * 1024 * 1024;

export interface OfferImportResult extends LocationImportCounts {
  batchId: string;
  screenEventCount: number;
  offerScreenCount: number;
  newObservationCount: number;
  skippedObservationCount: number;
  offersTotal: number;
  offersCreated: number;
}

function parseExport(text: string) {
  let raw: unknown;
  try {
    raw = JSON.parse(text.replace(/^﻿/, ""));
  } catch {
    throw new OfferImportError("הקובץ אינו JSON תקין");
  }
  try {
    return collectorExportSchema.parse(raw);
  } catch (e) {
    if (e instanceof ZodError) {
      const fmt = (raw as { export_format?: unknown })?.export_format;
      if (fmt !== COLLECTOR_EXPORT_FORMAT)
        throw new OfferImportError(`פורמט לא נתמך (${String(fmt)}). נדרש ייצוא Diagnostics מ-Wolt Offer Collector (${COLLECTOR_EXPORT_FORMAT}).`);
      throw new OfferImportError(`מבנה הקובץ לא תקין: ${e.issues.slice(0, 3).map((i) => i.path.join(".")).join(", ")}`);
    }
    throw e;
  }
}

const parseNames = (json: string): string[] => {
  try {
    const v = JSON.parse(json);
    // Observations stored by older imports may still hold a size tag or Wolt step label.
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && !isWeightTag(x) && !isWoltLabel(x)) : [];
  } catch {
    return [];
  }
};

/**
 * Idempotent import of a Collector export. A screen snapshot already imported (same Collector id)
 * is skipped, so overlapping or repeated exports never create duplicate offers. After inserting,
 * all observations are regrouped; manual labels on existing offers are kept.
 */
export async function importCollectorExport(text: string, fileName: string): Promise<OfferImportResult> {
  const exp = parseExport(text);
  const observations = exp.screen_events.map(toObservation).filter((o): o is OfferObservationInput => o !== null);
  const known = new Set(
    (await prisma.offerObservation.findMany({ where: { screenEventId: { in: observations.map((o) => o.screenEventId) } }, select: { screenEventId: true } })).map(
      (o) => o.screenEventId,
    ),
  );
  const fresh = observations.filter((o) => !known.has(o.screenEventId));

  return prisma.$transaction(async (tx) => {
    const batch = await tx.offerImportBatch.create({
      data: {
        sourceFileName: fileName,
        sourceHash: contentHash(text),
        exportFormat: COLLECTOR_EXPORT_FORMAT,
        exportedAtUtc: exp.exported_at_utc,
        appVersion: exp.app_version,
        selectedPackage: exp.selected_package,
        screenEventCount: exp.screen_events.length,
        offerScreenCount: observations.length,
        newObservationCount: fresh.length,
        skippedObservationCount: observations.length - fresh.length,
      },
    });
    for (const o of fresh) {
      await tx.offerObservation.create({
        data: {
          screenEventId: o.screenEventId,
          batchId: batch.id,
          observedAtUtc: new Date(o.observedAtMs),
          sourcePackage: o.sourcePackage,
          amountAgorot: o.amountAgorot,
          distanceMeters: o.distanceMeters,
          durationMin: o.durationMin,
          stopsCount: o.stopsCount,
          pickupNamesJson: JSON.stringify(o.pickupNames),
          parseVersion: o.parseVersion,
          coalescedEventCount: o.coalescedEventCount,
        },
      });
    }

    // Regroup everything we have, so snapshots arriving in a later export join their offer.
    const all = await tx.offerObservation.findMany();
    const groups = groupObservations(
      all.map((r) => ({
        screenEventId: r.screenEventId,
        observedAtMs: r.observedAtUtc.getTime(),
        sourcePackage: r.sourcePackage,
        amountAgorot: r.amountAgorot,
        distanceMeters: r.distanceMeters,
        durationMin: r.durationMin,
        stopsCount: r.stopsCount,
        pickupNames: parseNames(r.pickupNamesJson),
        parseVersion: r.parseVersion,
        coalescedEventCount: r.coalescedEventCount,
      })),
    );
    const before = await tx.offer.count();
    const keys = new Set<string>();
    for (const g of groups) {
      keys.add(g.offerKey);
      const data = {
        sourcePackage: g.sourcePackage,
        firstSeenAtUtc: new Date(g.firstSeenMs),
        lastSeenAtUtc: new Date(g.lastSeenMs),
        onScreenSeconds: Math.round((g.lastSeenMs - g.firstSeenMs) / 1000),
        amountAgorot: g.amountAgorot,
        distanceMeters: g.distanceMeters,
        durationMin: g.durationMin,
        stopsCount: g.stopsCount,
        pickupNamesJson: JSON.stringify(g.pickupNames),
        observationCount: g.observations.length,
        detection: g.detection,
        detectionConfidence: DETECTION_CONFIDENCE[g.detection],
      };
      const offer = await tx.offer.upsert({ where: { offerKey: g.offerKey }, create: { offerKey: g.offerKey, ...data }, update: data });
      await tx.offerObservation.updateMany({ where: { screenEventId: { in: g.observations.map((o) => o.screenEventId) } }, data: { offerId: offer.id } });
    }
    // Offers that no longer correspond to a group (regrouping merged them) — only drop unlabeled ones.
    await tx.offer.deleteMany({
      where: { offerKey: { notIn: [...keys] }, verification: "UNVERIFIED", outcome: "UNKNOWN", shiftId: null, paymentRecordId: null, completed: null },
    });
    const total = await tx.offer.count();
    // Shift sessions / location samples / delivery events (absent in older exports → all zero).
    const location = await importLocationData(tx, exp);
    return {
      ...location,
      batchId: batch.id,
      screenEventCount: exp.screen_events.length,
      offerScreenCount: observations.length,
      newObservationCount: fresh.length,
      skippedObservationCount: observations.length - fresh.length,
      offersTotal: total,
      offersCreated: Math.max(0, total - before),
    };
  }, { timeout: 60_000 });
}

export interface VerificationEntry {
  firstSeenAtUtc: string;
  amountAgorot: number;
  distanceKm: string | null;
  source: string;
}

/**
 * Marks offers as USER_VERIFIED from an explicit list (e.g. fixtures/offer-verifications.json).
 * An entry must match price, distance and first-seen time (±2 min) exactly; nothing is ever
 * verified just because a price was read from the screen.
 */
export async function applyVerifications(entries: VerificationEntry[]) {
  const results: { entry: VerificationEntry; offerId: string | null }[] = [];
  for (const e of entries) {
    if (!e.source.trim()) throw new OfferImportError("לכל אימות חייב להיות מקור");
    const t = Date.parse(e.firstSeenAtUtc);
    const candidates = await prisma.offer.findMany({
      where: { amountAgorot: e.amountAgorot, firstSeenAtUtc: { gte: new Date(t - 120_000), lte: new Date(t + 120_000) } },
    });
    const meters = e.distanceKm === null ? null : Math.round(Number(e.distanceKm) * 1000);
    const match = candidates.filter((c) => c.distanceMeters === meters);
    if (match.length !== 1) {
      results.push({ entry: e, offerId: null });
      continue;
    }
    await prisma.offer.update({
      where: { id: match[0].id },
      data: { verification: "USER_VERIFIED", verificationSource: e.source, verifiedAt: new Date() },
    });
    results.push({ entry: e, offerId: match[0].id });
  }
  return results;
}
