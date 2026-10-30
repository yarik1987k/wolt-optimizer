import { handle } from "@/lib/api";
import { updateOffer } from "@/lib/offers/service";

type Ctx = { params: Promise<{ id: string }> };

/** Manual labels: verification (with source), outcome, completed, shift and payment links. */
export const PATCH = (req: Request, ctx: Ctx) => handle(async () => updateOffer((await ctx.params).id, await req.json()));
