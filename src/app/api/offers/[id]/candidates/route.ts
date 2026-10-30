import { handle } from "@/lib/api";
import { prisma } from "@/lib/db";
import { paymentCandidates, shiftCandidates } from "@/lib/offers/service";

type Ctx = { params: Promise<{ id: string }> };

/** Suggestions for manual linking. Nothing is linked automatically. */
export const GET = (_req: Request, ctx: Ctx) =>
  handle(async () => {
    const offer = await prisma.offer.findUniqueOrThrow({ where: { id: (await ctx.params).id } });
    const t = offer.firstSeenAtUtc.getTime();
    return { shifts: await shiftCandidates(t), payments: await paymentCandidates(t) };
  });
