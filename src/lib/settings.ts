import { z } from "zod";
import { prisma } from "@/lib/db";
import { DEFAULT_RULES, type ClassificationRules } from "@/lib/import/classify";

export const settingsSchema = z.object({
  classificationRules: z.object({
    bonusPatterns: z.array(z.string().min(1)),
    adjustmentPatterns: z.array(z.string().min(1)),
  }),
  // Personal per-km cost (default ₪1.10, set by the courier; configurable). null = not set.
  vehicleCostPerKmAgorot: z.number().int().nonnegative().nullable(),
  minSampleObservation: z.number().int().positive(), // below this: "small sample"
  minSampleTestCandidate: z.number().int().positive(), // at/above this: "reasonable for an experiment"
  minShiftsPerGroup: z.number().int().positive(),
  // Hotspot comparison assumptions (explicit, user-entered; null = not set, never estimated by the app).
  hotspotAvgSpeedKmh: z.number().positive().max(200).nullable(),
  hotspotAcceptShare: z.number().min(0).max(1).nullable(), // null = use the share observed by the Collector, if any
  // Evidence rules (configurable, not statistical guarantees).
  evidenceMinWorkShiftMinutes: z.number().int().positive(), // shorter shift sessions are TEST_OR_SHORT periods
  evidenceMinShiftsForValidation: z.number().int().positive(), // independent work shifts (per arm) before a finding can be VALIDATED
  evidenceMinCoverage: z.number().min(0).max(1), // share of units with complete data
  evidenceSmallestEffectAgorotPerHour: z.number().int().nonnegative(), // smaller differences are not worth acting on
});
export type AppSettings = z.infer<typeof settingsSchema>;

export const DEFAULT_SETTINGS: AppSettings = {
  classificationRules: DEFAULT_RULES,
  vehicleCostPerKmAgorot: 110,
  minSampleObservation: 5,
  minSampleTestCandidate: 10,
  minShiftsPerGroup: 3,
  hotspotAvgSpeedKmh: null,
  hotspotAcceptShare: null,
  evidenceMinWorkShiftMinutes: 60,
  evidenceMinShiftsForValidation: 5,
  evidenceMinCoverage: 0.8,
  evidenceSmallestEffectAgorotPerHour: 500,
};

export async function getSettings(): Promise<AppSettings> {
  const rows = await prisma.setting.findMany();
  const merged: Record<string, unknown> = { ...DEFAULT_SETTINGS };
  for (const r of rows) merged[r.key] = JSON.parse(r.valueJson);
  const parsed = settingsSchema.safeParse(merged);
  return parsed.success ? parsed.data : DEFAULT_SETTINGS;
}

export async function updateSettings(patch: Partial<AppSettings>): Promise<AppSettings> {
  const next = settingsSchema.parse({ ...(await getSettings()), ...patch });
  await prisma.$transaction(
    Object.entries(patch).map(([key, value]) =>
      prisma.setting.upsert({
        where: { key },
        create: { key, valueJson: JSON.stringify(value) },
        update: { valueJson: JSON.stringify(value) },
      }),
    ),
  );
  return next;
}

export async function getRules(): Promise<ClassificationRules> {
  return (await getSettings()).classificationRules;
}
