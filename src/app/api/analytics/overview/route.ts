import { handle } from "@/lib/api";
import { parseFilters } from "@/lib/analytics/filters";
import { getOverview } from "@/lib/analytics/metrics";

export const dynamic = "force-dynamic";

export const GET = (req: Request) =>
  handle(async () => ({ unit: "agorot (ex VAT, no tips)", ...(await getOverview(parseFilters(new URL(req.url).searchParams))) }));
