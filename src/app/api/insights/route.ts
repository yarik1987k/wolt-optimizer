import { handle } from "@/lib/api";
import { parseFilters } from "@/lib/analytics/filters";
import { getInsights } from "@/lib/analytics/insights";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => getInsights(parseFilters(new URL(req.url).searchParams)));
