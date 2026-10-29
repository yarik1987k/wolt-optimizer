import Link from "next/link";
import { BucketChart } from "@/components/dashboard/charts";
import { FilterBar } from "@/components/dashboard/filter-bar";
import { InsightsList } from "@/components/dashboard/insights-list";
import { MerchantTable } from "@/components/dashboard/merchant-table";
import { Money, Num } from "@/components/num";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { filtersToSearchParams, parseFilters } from "@/lib/analytics/filters";
import { getInsights } from "@/lib/analytics/insights";
import { getBuckets, getOverview } from "@/lib/analytics/metrics";
import { prisma } from "@/lib/db";
import { listReportNumbers } from "@/lib/reports";

export const dynamic = "force-dynamic";

function Kpi({ title, children, hint, testId }: { title: string; children: React.ReactNode; hint?: React.ReactNode; testId?: string }) {
  return (
    <Card data-testid={testId}>
      <CardHeader className="pb-0">
        <CardDescription>{title}</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-semibold">{children}</div>
        {hint && <div className="mt-1 text-xs text-muted">{hint}</div>}
      </CardContent>
    </Card>
  );
}

export default async function Dashboard({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const filters = parseFilters(sp);
  const windowMinutes = [30, 60, 120].includes(Number(sp.window)) ? Number(sp.window) : 60;
  if ((await prisma.deliveryRecord.count()) === 0) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">דשבורד</h1>
        <Alert level="info">
          עדיין לא יובאו נתונים. <Link className="underline" href="/imports">ייבא קובץ CSV של תשלומי וולט</Link> כדי להתחיל.
        </Alert>
      </div>
    );
  }
  const [overview, hours, weekdays, dates, merchants, insights, reports] = await Promise.all([
    getOverview(filters),
    getBuckets("hour", filters, windowMinutes),
    getBuckets("weekday", filters),
    getBuckets("date", filters),
    getBuckets("merchant", filters),
    getInsights(filters, windowMinutes),
    listReportNumbers(),
  ]);
  const filterQuery = filtersToSearchParams(filters).toString();
  const typesNote = hours.record_types.join(", ") === "DELIVERY" ? "משלוחים בלבד (ללא בונוסים, תוספות וטיפים)" : `סוגים: ${hours.record_types.join(", ")}`;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">דשבורד</h1>
        <p className="text-sm text-muted">כל הסכומים ללא מע״מ. הדוחות אינם כוללים טיפים. המדדים הם לרשומת תשלום — רשומה יכולה להיות מקבץ של כמה משלוחים.</p>
      </div>
      <FilterBar
        basePath="/"
        reports={reports}
        showWindow
        initial={{ ...filters, window: windowMinutes }}
      />

      <section aria-label="מדדים" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Kpi title="הכנסה ממשלוחים בלבד" testId="kpi-delivery-income" hint="ללא מע״מ, טיפים, בונוסים ותוספות">
          <Money agorot={overview.delivery_income_ex_vat_agorot} />
        </Kpi>
        <Kpi title="רשומות משלוח" testId="kpi-delivery-count" hint={<>מתוכן <Num>{overview.bundle_record_count}</Num> מקבצים · זה לא מספר המשלוחים הבודדים</>}>
          <Num>{overview.delivery_record_count}</Num>
        </Kpi>
        <Kpi title="ממוצע / חציון לרשומת משלוח" testId="kpi-avg">
          <Money agorot={overview.avg_amount_per_record_agorot} /> <span className="text-base text-muted">/</span>{" "}
          <span className="text-lg"><Money agorot={overview.median_amount_per_record_agorot} /></span>
        </Kpi>
        <Kpi
          title="בונוסים ותוספות (בנפרד)"
          testId="kpi-extras"
          hint={<>בונוסים <Money agorot={overview.bonus_total_agorot} /> (<Num>{overview.bonus_record_count}</Num>) · תוספות <Money agorot={overview.adjustment_total_agorot} /> (<Num>{overview.adjustment_record_count}</Num>)</>}
        >
          <Money agorot={overview.bonus_total_agorot + overview.adjustment_total_agorot} />
        </Kpi>
        <Kpi title="משמרות שתועדו" testId="kpi-shifts">
          <Num>{overview.shifts_logged_count}</Num>
        </Kpi>
        <Kpi title="₪ לשעת משמרת" testId="kpi-per-hour" hint={overview.notes[0] ?? "משלוחים בלבד, לפי רשומות שאושרו למשמרות"}>
          <Money agorot={overview.delivery_per_shift_hour_agorot} />
        </Kpi>
        <Kpi title="₪ לק״מ" testId="kpi-per-km" hint="רק משמרות עם ק״מ מתועדים">
          <Money agorot={overview.delivery_per_km_agorot} />
        </Kpi>
        <Kpi title="סה״כ כל השורות בסינון" testId="kpi-all-rows" hint={<><Num>{overview.all_rows_count}</Num> שורות, כולל בונוסים ותוספות{overview.unknown_record_count ? ` · ${overview.unknown_record_count} לא מסווגות` : ""}</>}>
          <Money agorot={overview.all_rows_total_agorot} />
        </Kpi>
      </section>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>ממוצע לרשומה לפי שעת הרישום בדוח</CardTitle>
            <CardDescription>{typesNote}. חלון של {windowMinutes} דקות. שעת הרישום אינה בהכרח שעת ביצוע המשלוח.</CardDescription>
          </CardHeader>
          <CardContent>
            <BucketChart buckets={hours.buckets} metric="avg" referenceAgorot={hours.overall_avg_amount_per_record_agorot} ariaLabel="ממוצע לרשומה לפי שעה" />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>ממוצע לרשומה לפי יום בשבוע</CardTitle>
            <CardDescription>{typesNote}. מספר הרשומות בכל יום מופיע ב-tooltip.</CardDescription>
          </CardHeader>
          <CardContent>
            <BucketChart buckets={weekdays.buckets.map((b) => ({ ...b, key: b.label }))} metric="avg" referenceAgorot={weekdays.overall_avg_amount_per_record_agorot} ariaLabel="ממוצע לרשומה לפי יום בשבוע" />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>הכנסה יומית</CardTitle>
            <CardDescription>{typesNote}. סכום יומי; מספר רשומות ב-tooltip.</CardDescription>
          </CardHeader>
          <CardContent>
            <BucketChart buckets={dates.buckets.map((b) => ({ ...b, key: b.label.slice(0, 5) }))} metric="sum" ariaLabel="הכנסה יומית" />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>בתי עסק</CardTitle>
            <CardDescription>תשלום מצטבר וממוצע — רק רשומות של בית עסק יחיד.</CardDescription>
          </CardHeader>
          <CardContent>
            <MerchantTable buckets={merchants.buckets} bundles={merchants.bundles} />
          </CardContent>
        </Card>
      </div>

      <InsightsList result={insights} filterQuery={filterQuery} />
    </div>
  );
}
