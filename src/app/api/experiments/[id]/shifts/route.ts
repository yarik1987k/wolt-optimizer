import { z } from "zod";
import { handle } from "@/lib/api";
import { prisma } from "@/lib/db";
import { experimentGroupSchema } from "@/lib/types";

type Ctx = { params: Promise<{ id: string }> };

/** Assign a shift to the CONTROL or TEST group (re-assigning replaces the group). */
export const POST = (req: Request, ctx: Ctx) =>
  handle(async () => {
    const experimentId = (await ctx.params).id;
    const { shiftId, group } = z.object({ shiftId: z.string(), group: experimentGroupSchema }).parse(await req.json());
    return prisma.experimentShift.upsert({
      where: { experimentId_shiftId: { experimentId, shiftId } },
      create: { experimentId, shiftId, group },
      update: { group },
    });
  });

export const DELETE = (req: Request, ctx: Ctx) =>
  handle(async () => {
    const experimentId = (await ctx.params).id;
    const { shiftId } = z.object({ shiftId: z.string() }).parse(await req.json());
    await prisma.experimentShift.deleteMany({ where: { experimentId, shiftId } });
    return { removed: shiftId };
  });
