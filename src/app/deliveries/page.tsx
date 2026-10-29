import Link from "next/link";
import { Suspense } from "react";
import { DeliveriesToolbar } from "@/components/deliveries/search-box";
import { TypeSelect } from "@/components/deliveries/type-select";
import { Money, Num } from "@/components/num";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Table, Td, Th } from "@/components/ui/table";
import { filtersToSearchParams, parseFilters } from "@/lib/analytics/filters";
import { listDeliveries, sortSchema, type SortKey } from "@/lib/deliveries";
import { listReportNumbers } from "@/lib/reports";
import { formatDateHe } from "@/lib/time";
import type { RecordType } from "@/lib/types";

export const dynamic = "force-dynamic";

export default async function DeliveriesPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const filters = parseFilters(sp);
  const sort = sortSchema.parse(typeof sp.sort === "string" ? sp.sort : "amount_desc");
  const page = Math.max(1, Number(sp.page) || 1);
  const [data, reports] = await Promise.all([listDeliveries(filters, sort, page), listReportNumbers()]);
  const qs = filtersToSearchParams(filters);
  const href = (patch: Record<string, string>) => {
    const p = new URLSearchParams(qs);
    p.set("sort", sort);
    for (const [k, v] of Object.entries(patch)) p.set(k, v);
    return `/deliveries?${p.toString()}`;
  };
  const sortLink = (asc: SortKey, desc: SortKey, label: string) => {
    const next = sort === desc ? asc : desc;
    const arrow = sort === desc ? " ↓" : sort === asc ? " ↑" : "";
    return <Link href={href({ sort: next, page: "1" })} className="underline-offset-2 hover:underline">{label}{arrow}</Link>;
  };
  const exportQs = new URLSearchParams(qs);
  exportQs.set("sort", sort);
  exportQs.set("format", "csv");
  const pages = Math.max(1, Math.ceil(data.total / data.pageSize));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">רשומות</h1>
          <p className="text-sm text-muted">
            <Num>{data.total}</Num> רשומות בסינון · סה״כ <Money agorot={data.filtered_sum_agorot} /> · ממוין כברירת מחדל מהסכום הגבוה לנמוך
          </p>
        </div>
        <a className={buttonVariants({ variant: "outline" })} href={`/api/deliveries?${exportQs.toString()}`} download>
          הורדת CSV מסונן
        </a>
      </div>
      <Suspense>
        <DeliveriesToolbar reports={reports} />
      </Suspense>
      <div className="rounded-lg border border-border bg-surface">
        <Table data-testid="deliveries-table">
          <thead>
            <tr>
              <Th>{sortLink("date_asc", "date_desc", "תאריך")}</Th>
              <Th>שעה</Th>
              <Th>בית עסק (כפי שבמקור)</Th>
              <Th>{sortLink("amount_asc", "amount_desc", "סכום (₪)")}</Th>
              <Th>סוג רשומה</Th>
              <Th>הערות</Th>
              <Th>קובץ מקור</Th>
              <Th>מקבץ</Th>
            </tr>
          </thead>
          <tbody>
            {data.rows.map((r) => (
              <tr key={r.id} data-testid="delivery-row">
                <Td className="whitespace-nowrap"><Num>{formatDateHe(r.eventDate)}</Num></Td>
                <Td><Num>{r.eventTime}</Num></Td>
                <Td className="min-w-48">{r.merchantRaw}</Td>
                <Td className="whitespace-nowrap font-medium" data-testid="amount"><Money agorot={r.amountAgorot} /></Td>
                <Td><TypeSelect id={r.id} type={r.type as RecordType} manual={r.typeManuallySet} /></Td>
                <Td className="max-w-64 text-xs text-muted">{r.notes}</Td>
                <Td className="text-xs whitespace-nowrap">{r.sourceFileName}</Td>
                <Td>{r.isBundle ? <Badge variant="info">מקבץ</Badge> : <span className="text-muted">—</span>}</Td>
              </tr>
            ))}
            {data.rows.length === 0 && (
              <tr><Td colSpan={8} className="py-8 text-center text-muted">אין רשומות בסינון הנוכחי</Td></tr>
            )}
          </tbody>
        </Table>
      </div>
      {pages > 1 && (
        <nav className="flex items-center gap-2 text-sm" aria-label="דפים">
          {page > 1 && <Link className={buttonVariants({ variant: "outline", size: "sm" })} href={href({ page: String(page - 1) })}>הקודם</Link>}
          <span>עמוד <Num>{page}</Num> מתוך <Num>{pages}</Num></span>
          {page < pages && <Link className={buttonVariants({ variant: "outline", size: "sm" })} href={href({ page: String(page + 1) })}>הבא</Link>}
        </nav>
      )}
    </div>
  );
}
