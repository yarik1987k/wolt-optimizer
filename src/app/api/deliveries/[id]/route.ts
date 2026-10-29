import { z } from "zod";
import { handle } from "@/lib/api";
import { prisma } from "@/lib/db";
import { recordTypeSchema } from "@/lib/types";

const patchSchema = z.object({ type: recordTypeSchema });

/** Manual classification override (e.g. approving an UNKNOWN row). */
export const PATCH = (req: Request, ctx: { params: Promise<{ id: string }> }) =>
  handle(async () => {
    const { id } = await ctx.params;
    const { type } = patchSchema.parse(await req.json());
    const current = await prisma.deliveryRecord.findUniqueOrThrow({ where: { id } });
    return prisma.deliveryRecord.update({
      where: { id },
      data: { type, typeManuallySet: type !== current.typeAutoClassified },
    });
  });
