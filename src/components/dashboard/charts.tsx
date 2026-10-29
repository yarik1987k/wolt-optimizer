"use client";
import { Bar, BarChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatShekel } from "@/lib/money";
import type { Bucket } from "@/lib/analytics/metrics";

type Metric = "avg" | "sum";

function ChartTooltip({ active, payload }: { active?: boolean; payload?: { payload: Bucket }[] }) {
  if (!active || !payload?.length) return null;
  const b = payload[0].payload;
  return (
    <div dir="rtl" className="rounded-md border border-border bg-surface px-3 py-2 text-xs shadow-sm">
      <div className="mb-1 font-semibold"><span className="num">{b.label}</span></div>
      <div>רשומות: <span className="num">{b.n_records}</span></div>
      <div>סכום: <span className="num">{formatShekel(b.sum_agorot)}</span></div>
      <div>ממוצע לרשומה: <span className="num">{formatShekel(b.avg_amount_per_record_agorot)}</span></div>
      <div>חציון לרשומה: <span className="num">{formatShekel(b.median_amount_per_record_agorot)}</span></div>
    </div>
  );
}

export function BucketChart({
  buckets,
  metric,
  referenceAgorot,
  ariaLabel,
}: {
  buckets: Bucket[];
  metric: Metric;
  referenceAgorot?: number | null;
  ariaLabel: string;
}) {
  if (!buckets.length) return <p className="py-10 text-center text-sm text-muted">אין נתונים בטווח הסינון</p>;
  const data = buckets.map((b) => ({
    ...b,
    value: (metric === "avg" ? (b.avg_amount_per_record_agorot ?? 0) : b.sum_agorot) / 100,
  }));
  return (
    <>
    <div dir="ltr" role="img" aria-label={ariaLabel} className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 16, right: 8, bottom: 4, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--color-border)" />
          <XAxis dataKey="key" tick={{ fontSize: 11, fill: "var(--color-muted)" }} tickLine={false} axisLine={{ stroke: "var(--color-border)" }} />
          <YAxis
            tick={{ fontSize: 11, fill: "var(--color-muted)" }}
            tickLine={false}
            axisLine={false}
            width={48}
            tickFormatter={(v: number) => `₪${v}`}
          />
          <Tooltip content={<ChartTooltip />} cursor={{ fill: "var(--color-border)", opacity: 0.4 }} />
          {referenceAgorot != null && (
            <ReferenceLine
              y={referenceAgorot / 100}
              stroke="var(--color-chart-ref)"
              strokeDasharray="4 4"
            />
          )}
          <Bar dataKey="value" fill="var(--color-chart)" radius={[4, 4, 0, 0]} maxBarSize={36} />
        </BarChart>
      </ResponsiveContainer>
    </div>
    {referenceAgorot != null && (
      <p className="mt-1 text-xs text-muted">
        קו מקווקו: ממוצע כללי <span className="num">{formatShekel(referenceAgorot)}</span> לרשומה
      </p>
    )}
    </>
  );
}
