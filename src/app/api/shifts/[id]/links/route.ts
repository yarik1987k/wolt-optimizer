import { z } from "zod";
import { handle } from "@/lib/api";
import { confirmLinks, suggestRecordsForShift, unlinkRecords } from "@/lib/analytics/shifts";

type Ctx = { params: Promise<{ id: string }> };

/** Suggested matches only — nothing is linked until POST with confirm: true. */
export const GET = (_req: Request, ctx: Ctx) => handle(async () => suggestRecordsForShift((await ctx.params).id));

const confirmSchema = z.object({ recordIds: z.array(z.string()).min(1), confirm: z.literal(true) });

export const POST = (req: Request, ctx: Ctx) =>
  handle(async () => {
    const { recordIds } = confirmSchema.parse(await req.json());
    return confirmLinks((await ctx.params).id, recordIds);
  });

export const DELETE = (req: Request, ctx: Ctx) =>
  handle(async () => {
    const { recordIds } = z.object({ recordIds: z.array(z.string()).min(1) }).parse(await req.json());
    return unlinkRecords((await ctx.params).id, recordIds);
  });
