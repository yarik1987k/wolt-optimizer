import { handle } from "@/lib/api";
import { listShiftsWithMetrics } from "@/lib/analytics/shifts";
import { createShift } from "@/lib/shift-service";

export const dynamic = "force-dynamic";

export const GET = () => handle(listShiftsWithMetrics);
export const POST = (req: Request) => handle(async () => createShift(await req.json()));
