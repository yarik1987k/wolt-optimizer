import { handle } from "@/lib/api";
import { prisma } from "@/lib/db";
import { listShiftsWithMetrics } from "@/lib/analytics/shifts";
import { updateShift } from "@/lib/shift-service";

type Ctx = { params: Promise<{ id: string }> };

export const GET = (_req: Request, ctx: Ctx) =>
  handle(async () => {
    const { id } = await ctx.params;
    const s = (await listShiftsWithMetrics()).find((x) => x.id === id);
    if (!s) return Response.json({ error: "לא נמצא" }, { status: 404 });
    return s;
  });

export const PATCH = (req: Request, ctx: Ctx) => handle(async () => updateShift((await ctx.params).id, await req.json()));

export const DELETE = (_req: Request, ctx: Ctx) =>
  handle(async () => {
    const { id } = await ctx.params;
    await prisma.shift.delete({ where: { id } });
    return { deleted: id };
  });
