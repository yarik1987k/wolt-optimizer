import { z } from "zod";
import { handle } from "@/lib/api";
import { prisma } from "@/lib/db";
import { getExperimentWithComparison } from "@/lib/analytics/experiments";
import { experimentStatusSchema } from "@/lib/types";

type Ctx = { params: Promise<{ id: string }> };

export const GET = (_req: Request, ctx: Ctx) => handle(async () => getExperimentWithComparison((await ctx.params).id));

const patchSchema = z.object({
  title: z.string().trim().min(1).optional(),
  hypothesisText: z.string().trim().min(1).optional(),
  startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullable().optional(),
  status: experimentStatusSchema.optional(),
});

export const PATCH = (req: Request, ctx: Ctx) =>
  handle(async () => prisma.experiment.update({ where: { id: (await ctx.params).id }, data: patchSchema.parse(await req.json()) }));
