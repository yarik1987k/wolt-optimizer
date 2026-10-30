import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { prisma } from "@/lib/db";

export const FIXTURE_NAME = "wolt_deliveries_sorted.csv";
export const fixtureText = () => readFileSync(resolve(__dirname, "../fixtures", FIXTURE_NAME), "utf8");

/**
 * The fixture plus the 11 rows of report 116000001 again, attributed to the duplicate PDF
 * "116000001 (1).pdf" exactly as that copy appeared in the source material. Derived from the
 * real fixture — no invented values.
 */
export function fixtureWithDuplicateReportCopy(): string {
  const lines = fixtureText().replace(/^﻿/, "").trimEnd().split(/\r?\n/);
  const copies = lines.filter((l) => l.endsWith(",116000001.pdf")).map((l) => l.replace(/,116000001\.pdf$/, ",116000001 (1).pdf"));
  return [...lines, ...copies].join("\n") + "\n";
}

export const OFFER_EXPORT_NAME = "offer-collector-export-2026-10-29.json";
export const offerExportText = () => readFileSync(resolve(__dirname, "../fixtures", OFFER_EXPORT_NAME), "utf8");
export const offerVerifications = () =>
  JSON.parse(readFileSync(resolve(__dirname, "../fixtures/offer-verifications.json"), "utf8")).verifications;

export async function resetDb() {
  await prisma.locationSample.deleteMany();
  await prisma.collectorShift.deleteMany();
  await prisma.collectorDeliveryEvent.deleteMany();
  await prisma.collectorTrip.deleteMany();
  await prisma.officialDayStat.deleteMany();
  await prisma.collectorChain.deleteMany();
  await prisma.collectorEtaCheck.deleteMany();
  await prisma.collectorPickupPlace.deleteMany();
  await prisma.shadowPrediction.deleteMany();
  await prisma.collectorTrackPoint.deleteMany();
  await prisma.offerObservation.deleteMany();
  await prisma.offer.deleteMany();
  await prisma.offerImportBatch.deleteMany();
  await prisma.experimentShift.deleteMany();
  await prisma.experiment.deleteMany();
  await prisma.insight.deleteMany();
  await prisma.shiftRecordLink.deleteMany();
  await prisma.shift.deleteMany();
  await prisma.deliveryRecord.deleteMany();
  await prisma.importBatch.deleteMany();
  await prisma.setting.deleteMany();
}
