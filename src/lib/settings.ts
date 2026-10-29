import { z } from "zod";
import { prisma } from "@/lib/db";
import { DEFAULT_RULES, type ClassificationRules } from "@/lib/import/classify";

export const settingsSchema = z.object({
  classificationRules: z.object({
    bonusPatterns: z.array(z.string().min(1)),
    adjustmentPatterns: z.array(z.string().min(1)),
  }),
  // Personal default; null = not set. Never estimated by the app.
  vehicleCostPerKmAgorot: z.number().int().nonnegative().nullable(),
  minSampleObservation: z.number().int().positive(), // below this: "small sample"
  minSampleTestCandidate: z.number().int().positive(), // at/above this: "reasonable for an experiment"
  minShiftsPerGroup: z.number().int().positive(),
});
export type AppSettings = z.infer<typeof settingsSchema>;

export const DEFAULT_SETTINGS: AppSettings = {
  classificationRules: DEFAULT_RULES,
  vehicleCostPerKmAgorot: null,
  minSampleObservation: 5,
  minSampleTestCandidate: 10,
  minShiftsPerGroup: 3,
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
