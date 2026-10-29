import { z } from "zod";
import { handle } from "@/lib/api";
import { parseFilters } from "@/lib/analytics/filters";
import { getBuckets } from "@/lib/analytics/metrics";

export const dynamic = "force-dynamic";

const q = z.object({
  groupBy: z.enum(["hour", "weekday", "date", "merchant"]),
  windowMinutes: z.coerce.number().int().refine((n) => [15, 30, 60, 120].includes(n)).default(60),
});

export const GET = (req: Request) =>
  handle(async () => {
    const params = new URL(req.url).searchParams;
    const { groupBy, windowMinutes } = q.parse({
      groupBy: params.get("groupBy"),
      windowMinutes: params.get("windowMinutes") ?? undefined,
    });
    return getBuckets(groupBy, parseFilters(params), windowMinutes);
  });
