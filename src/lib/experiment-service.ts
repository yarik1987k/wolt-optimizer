import { z } from "zod";
import { prisma } from "@/lib/db";
import { parseFilters } from "@/lib/analytics/filters";
import { getInsights } from "@/lib/analytics/insights";
import { formatShekel } from "@/lib/money";

export const createExperimentSchema = z.union([
  z.object({
    fromInsight: z.object({ key: z.string(), filterQuery: z.string().default("") }),
    startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  }),
  z.object({
    title: z.string().trim().min(1, "יש להזין כותרת"),
    hypothesisText: z.string().trim().min(1, "יש להזין תיאור השערה"),
    startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  }),
]);

/**
 * Creating from an insight recomputes it on the server (the client only sends the key and
 * filters) and stores a snapshot of the evidence alongside the experiment.
 */
export async function createExperiment(body: unknown) {
  const input = createExperimentSchema.parse(body);
  if ("fromInsight" in input) {
    const filters = parseFilters(new URLSearchParams(input.fromInsight.filterQuery));
    const result = await getInsights(filters);
    const insight = [...result.hypotheses, ...result.observations].find((i) => i.key === input.fromInsight.key);
    if (!insight) throw new z.ZodError([{ code: "custom", path: ["fromInsight", "key"], message: "התובנה לא נמצאה בנתונים הנוכחיים", input: input.fromInsight.key }]);
    const evidence =
      `ראיות בזמן היצירה: ${insight.group_label} — ממוצע ${formatShekel(insight.group.mean)} לרשומה (n=${insight.group.n}) ` +
      `לעומת ${formatShekel(insight.baseline.mean)} בקו הבסיס (n=${insight.baseline.n}); ${insight.confidence_label}.`;
    const saved = await prisma.insight.create({
      data: {
        kind: insight.kind,
        insightKey: insight.key,
        filterSnapshotJson: JSON.stringify(filters),
        metricJson: JSON.stringify({ group: insight.group, baseline: insight.baseline, robust_gap_pct: insight.robust_gap_pct, distinct_dates: insight.distinct_dates }),
        rationale: `${insight.hypothesis}\n${insight.uncertainty}\n${insight.limitations.join("\n")}`,
        sampleSize: insight.sample_size,
        status: insight.status,
      },
    });
    return prisma.experiment.create({
      data: {
        title: insight.title,
        hypothesisText: `${insight.hypothesis}\n\n${evidence}`,
        startDate: input.startDate ?? null,
        insightId: saved.id,
      },
    });
  }
  return prisma.experiment.create({
    data: { title: input.title, hypothesisText: input.hypothesisText, startDate: input.startDate ?? null },
  });
}
