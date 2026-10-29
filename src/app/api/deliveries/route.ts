import { handle } from "@/lib/api";
import { parseFilters } from "@/lib/analytics/filters";
import { exportDeliveriesCsv, listDeliveries, sortSchema } from "@/lib/deliveries";

export const dynamic = "force-dynamic";

export const GET = (req: Request) =>
  handle(async () => {
    const params = new URL(req.url).searchParams;
    const filters = parseFilters(params);
    const sort = sortSchema.parse(params.get("sort") ?? "amount_desc");
    if (params.get("format") === "csv") {
      return new Response(await exportDeliveriesCsv(filters, sort), {
        headers: {
          "content-type": "text/csv; charset=utf-8",
          "content-disposition": 'attachment; filename="wolt-records-filtered.csv"',
        },
      });
    }
    const page = Math.max(1, Number(params.get("page")) || 1);
    const pageSize = Math.min(500, Math.max(1, Number(params.get("pageSize")) || 50));
    return listDeliveries(filters, sort, page, pageSize);
  });
