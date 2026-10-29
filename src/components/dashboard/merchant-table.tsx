import { Money, Num } from "@/components/num";
import { Table, Td, Th } from "@/components/ui/table";
import type { Bucket } from "@/lib/analytics/metrics";

/** Merchant totals for single-merchant records only; bundles are a separate row, never split. */
export function MerchantTable({ buckets, bundles, limit = 12 }: { buckets: Bucket[]; bundles: Bucket | null; limit?: number }) {
  const shown = buckets.slice(0, limit);
  const max = Math.max(1, ...shown.map((b) => b.sum_agorot), bundles?.sum_agorot ?? 0);
  if (!buckets.length && !bundles?.n_records) return <p className="py-6 text-center text-sm text-muted">אין נתונים בטווח הסינון</p>;
  const row = (b: Bucket, muted = false) => (
    <tr key={b.key} className={muted ? "bg-background" : undefined}>
      <Td className="max-w-64">{b.label}</Td>
      <Td><Num>{b.n_records}</Num></Td>
      <Td className="w-1/3">
        <div className="flex items-center gap-2">
          <div className="h-2 w-24 shrink-0 rounded-full bg-background">
            <div className="h-2 rounded-full" style={{ width: `${Math.max(2, (b.sum_agorot / max) * 100)}%`, background: muted ? "var(--color-chart-ref)" : "var(--color-chart)" }} />
          </div>
          <Money agorot={b.sum_agorot} />
        </div>
      </Td>
      <Td><Money agorot={b.avg_amount_per_record_agorot} /></Td>
    </tr>
  );
  return (
    <>
      <Table aria-label="בתי עסק לפי תשלום מצטבר">
        <thead>
          <tr><Th>בית עסק (רשומות של עסק יחיד)</Th><Th>רשומות</Th><Th>סכום מצטבר</Th><Th>ממוצע לרשומה</Th></tr>
        </thead>
        <tbody>
          {shown.map((b) => row(b))}
          {bundles && bundles.n_records > 0 && row(bundles, true)}
        </tbody>
      </Table>
      <p className="mt-2 text-xs text-muted">
        מוצגים {shown.length} מתוך {buckets.length} בתי עסק. מקבצים מוצגים כקטגוריה נפרדת — הסכום אינו מחולק ואינו מיוחס לאף אחד מבתי העסק במקבץ.
      </p>
    </>
  );
}
