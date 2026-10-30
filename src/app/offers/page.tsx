import { BucketChart } from "@/components/dashboard/charts";
import { Money, Num } from "@/components/num";
import { OfferFilters } from "@/components/offers/offer-filters";
import { OfferLabels } from "@/components/offers/offer-labels";
import { OfferUploader } from "@/components/offers/offer-uploader";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, Td, Th } from "@/components/ui/table";
import { prisma } from "@/lib/db";
import { jerusalemParts } from "@/lib/offers/analytics";
import { getOfferAnalytics, parseOfferFilters } from "@/lib/offers/service";
import { formatDateHe } from "@/lib/time";

export const dynamic = "force-dynamic";

const pad = (n: number) => String(n).padStart(2, "0");
const pct = (x: number | null) => (x === null ? "—" : `${(x * 100).toFixed(0)}%`);
const minutes = (m: number | null) => (m === null ? "אין נתונים" : `${m.toFixed(1)} דק׳`);

export default async function OffersPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const filters = parseOfferFilters(sp);
  const { rows, analytics: a, hypotheses, totals } = await getOfferAnalytics(filters);
  const batches = await prisma.offerImportBatch.findMany({ orderBy: { importedAt: "desc" }, take: 5 });
  const initial = Object.fromEntries(Object.entries(sp).filter((e): e is [string, string] => typeof e[1] === "string"));
  const hourBuckets = a.by_hour.map((h) => ({
    key: `${pad(h.hour)}:00`, label: `${pad(h.hour)}:00–${pad(h.hour + 1)}:00`, n_records: h.n, sum_agorot: h.sum_agorot,
    avg_amount_per_record_agorot: h.mean_amount_agorot, median_amount_per_record_agorot: h.median_amount_agorot,
  }));
  const distBuckets = a.distribution.map((d) => ({
    key: `${d.from_agorot / 100}`, label: `₪${d.from_agorot / 100}–${d.to_agorot / 100}`, n_records: d.n, sum_agorot: 0,
    avg_amount_per_record_agorot: null, median_amount_per_record_agorot: null,
  }));

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">הצעות שנצפו</h1>
        <p className="text-sm text-muted">
          הצעות שהוצגו על המסך ונקלטו ע״י Wolt Offer Collector. לא הוכח שכל ההצעות נקלטו — אלה <strong>הצעות שנצפו</strong>, לא כל ההצעות שנשלחו.
          הסכומים כפי שמוצגים באפליקציה (לא ידוע אם כוללים מע״מ) ולכן אינם מושווים ישירות לדוחות התשלום (ללא מע״מ).
        </p>
      </div>

      <Card>
        <CardHeader><CardTitle>ייבוא מ-Android</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <OfferUploader />
          {batches.length > 0 && (
            <p className="text-xs text-muted">
              ייבוא אחרון: {batches[0].sourceFileName} · <Num>{batches[0].offerScreenCount}</Num> כרטיסי הצעה, <Num>{batches[0].newObservationCount}</Num> חדשים
            </p>
          )}
        </CardContent>
      </Card>

      <OfferFilters initial={initial} />

      {totals.partial > 0 && filters.detection !== "ALL" && (
        <Alert level="info"><Num>{totals.partial}</Num> הצעות עם זיהוי חלקי (ללא מרחק) לא מוצגות. בחר/י &quot;כולל זיהוי חלקי&quot; כדי לראות אותן.</Alert>
      )}

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="מדדים">
        <Card data-testid="offers-count"><CardHeader className="pb-0"><CardDescription>הצעות שנצפו</CardDescription></CardHeader>
          <CardContent><div className="text-2xl font-semibold"><Num>{a.observed_offer_count}</Num></div>
            <div className="text-xs text-muted">ודאי <Num>{a.confirmed_card_count}</Num> · חלקי <Num>{a.partial_card_count}</Num> · מאומתות ידנית <Num>{a.verified_offer_count}</Num></div></CardContent></Card>
        <Card data-testid="offers-avg"><CardHeader className="pb-0"><CardDescription>ממוצע / חציון להצעה</CardDescription></CardHeader>
          <CardContent><div className="text-2xl font-semibold"><Money agorot={a.amount.mean} /> <span className="text-base text-muted">/</span> <span className="text-lg"><Money agorot={a.amount.median} /></span></div>
            <div className="text-xs text-muted">כפי שמוצג באפליקציה</div></CardContent></Card>
        <Card data-testid="offers-km"><CardHeader className="pb-0"><CardDescription>מרחק ממוצע · ₪ לק״מ</CardDescription></CardHeader>
          <CardContent><div className="text-2xl font-semibold">{a.distance_m.mean === null ? <span className="text-muted">אין נתונים</span> : <Num>{(a.distance_m.mean / 1000).toFixed(1)} ק״מ</Num>}</div>
            <div className="text-xs text-muted">₪/ק״מ מצטבר <Money agorot={a.agorot_per_km_aggregate} /> · חציון <Money agorot={a.agorot_per_km.median} /> · <Num>{a.offers_with_distance}</Num> עם מרחק</div></CardContent></Card>
        <Card data-testid="offers-gaps"><CardHeader className="pb-0"><CardDescription>זמן בין הצעות שנצפו (אותו יום)</CardDescription></CardHeader>
          <CardContent><div className="text-2xl font-semibold"><Num>{minutes(a.gaps_minutes.median)}</Num></div>
            <div className="text-xs text-muted">חציון · <Num>{a.gaps_minutes.n}</Num> מרווחים · כולל זמנים שבהם לא היה ניטור</div></CardContent></Card>
      </section>

      {a.observed_offer_count > 0 && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader><CardTitle>הצעות שנצפו לפי שעה</CardTitle><CardDescription>ספירה, לא קצב: זמן הניטור בכל שעה לא ידוע.</CardDescription></CardHeader>
            <CardContent><BucketChart buckets={hourBuckets} metric="count" ariaLabel="הצעות לפי שעה" nLabel="הצעות" /></CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>התפלגות סכומים</CardTitle><CardDescription>טווחים של ₪5.</CardDescription></CardHeader>
            <CardContent><BucketChart buckets={distBuckets} metric="count" ariaLabel="התפלגות סכומי הצעות" nLabel="הצעות" /></CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>שיעור הצעות מעל סף מחיר</CardTitle></CardHeader>
            <CardContent>
              <Table data-testid="thresholds">
                <thead><tr><Th>סף</Th><Th>הצעות ≥ סף</Th><Th>שיעור</Th></tr></thead>
                <tbody>{a.thresholds.map((t) => (
                  <tr key={t.threshold_shekel}><Td><Num>₪{t.threshold_shekel}</Num></Td><Td><Num>{t.n_at_or_above}</Num></Td><Td><Num>{pct(t.share)}</Num></Td></tr>
                ))}</tbody>
              </Table>
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>השערות</CardTitle><CardDescription>תצפיות לבדיקה — לא סיבתיות ולא הבטחה להכנסה.</CardDescription></CardHeader>
            <CardContent className="space-y-3 text-sm" data-testid="offer-hypotheses">
              {hypotheses.explanation && <Alert level="info">{hypotheses.explanation}</Alert>}
              {hypotheses.hypotheses.map((h) => (
                <div key={h.key} className="space-y-1 border-b border-border pb-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={h.testable ? "warning" : "danger"}>{h.confidence_label}</Badge>
                    <span className="font-medium">{h.title}</span>
                  </div>
                  <p>{h.evidence} <span className="text-muted">(n=<Num>{h.sample_size}</Num>, הפרש: <Num>{h.effect}</Num>)</span></p>
                  <details><summary className="cursor-pointer text-muted">מגבלות</summary>
                    <ul className="list-disc ps-5 text-muted">{h.limitations.map((l) => <li key={l}>{l}</li>)}</ul></details>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      )}

      <Card>
        <CardHeader><CardTitle>הצעות</CardTitle><CardDescription>קבלה, ביצוע וקישור לתשלום — סימון ידני בלבד. הופעה על המסך אינה קבלה.</CardDescription></CardHeader>
        <CardContent>
          {rows.length === 0 ? <p className="text-sm text-muted">אין הצעות שנצפו בטווח הסינון.</p> : (
            <Table data-testid="offers-table">
              <thead><tr><Th>זמן</Th><Th>סכום</Th><Th>מרחק</Th><Th>₪/ק״מ</Th><Th>מסעדה</Th><Th>על המסך</Th><Th>זיהוי</Th><Th>סימון ידני</Th></tr></thead>
              <tbody>
                {rows.map((o) => {
                  const t = jerusalemParts(o.firstSeenAtUtc.getTime());
                  const names: string[] = JSON.parse(o.pickupNamesJson);
                  return (
                    <tr key={o.id} data-testid="offer-row">
                      <Td className="whitespace-nowrap"><Num>{formatDateHe(t.date)} {pad(t.hour)}:{pad(t.minute)}</Num></Td>
                      <Td className="font-medium" data-testid="offer-amount"><Money agorot={o.amountAgorot} /></Td>
                      <Td>{o.distanceMeters === null ? <span className="text-muted">NULL</span> : <Num>{(o.distanceMeters / 1000).toFixed(1)} ק״מ</Num>}</Td>
                      <Td>{o.distanceMeters ? <Money agorot={o.amountAgorot / (o.distanceMeters / 1000)} /> : <span className="text-muted">—</span>}</Td>
                      <Td className="text-xs">{names.length ? names.join(" · ") : <span className="text-muted">לא זוהתה</span>}</Td>
                      <Td><Num>~{o.onScreenSeconds} שנ׳</Num> <span className="text-xs text-muted">(<Num>{o.observationCount}</Num> צילומים)</span></Td>
                      <Td><Badge variant={o.detection === "CONFIRMED_CARD" ? "success" : "warning"}>{o.detection === "CONFIRMED_CARD" ? "ודאי" : "חלקי"}</Badge></Td>
                      <Td><OfferLabels id={o.id} verification={o.verification} verificationSource={o.verificationSource} outcome={o.outcome}
                        completed={o.completed} shiftId={o.shiftId} paymentRecordId={o.paymentRecordId} /></Td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
