import Link from "next/link";
import { EvidenceBadge, Shekel, ShekelRange, TimeRange } from "@/components/plan/evidence";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { chainEconomics } from "@/lib/chains/economics";
import { prisma } from "@/lib/db";
import { performanceReport } from "@/lib/history/performance";
import { workShifts } from "@/lib/plan/decisions";
import { reconcileDay } from "@/lib/reconcile/day";
import { getSettings } from "@/lib/settings";
import { TIME_ZONE } from "@/lib/time";
import { cn } from "@/lib/utils";

export const dynamic = "force-dynamic";

const hm = (ms: number) => new Date(ms).toLocaleTimeString("en-GB", { timeZone: TIME_ZONE, hour: "2-digit", minute: "2-digit" });
const day = (ms: number) => new Date(ms).toLocaleDateString("en-CA", { timeZone: TIME_ZONE });
const dm = (ms: number) => new Date(ms).toLocaleDateString("he-IL", { timeZone: TIME_ZONE, day: "numeric", month: "numeric" });

const PARTS = [
  { key: "moving", label: "נסיעה בזמן משלוח", color: "#378ADD" },
  { key: "still", label: "עמידה בזמן משלוח", color: "#EF9F27" },
  { key: "between", label: "המתנה בין משלוחים", color: "#888780" },
  { key: "unknown", label: "לא ידוע", color: "#D3D1C7" },
] as const;

/** After the shift: what you really earned per shift hour, where the time went, which deliveries paid. */
export default async function ReviewPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const sp = await searchParams;
  const { work } = await workShifts();
  if (!work.length) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">אחרי משמרת</h1>
        <Alert level="info">עדיין אין משמרת עבודה. <Link className="underline" href="/imports">ייבא נתונים מהאפליקציה</Link> אחרי משמרת.</Alert>
      </div>
    );
  }
  const w = work.find((x) => x.id === sp.shift) ?? work[0];
  const s = w.summary;
  const settings = await getSettings();
  const [shiftRow, chainRows, perf, rec] = await Promise.all([
    prisma.collectorShift.findUnique({ where: { id: w.id } }),
    prisma.collectorChain.findMany({ where: { startLatestAtUtc: { gte: new Date(w.startMs), lte: new Date(w.endMs) } }, orderBy: { startLatestAtUtc: "asc" } }),
    performanceReport(),
    reconcileDay(day(w.startMs)),
  ]);
  const chains = chainRows.map((c) => ({
    row: c,
    e: chainEconomics(
      {
        chainKey: c.chainKey, offers: c.offers, revenueAgorot: c.revenueAgorot, ordersLow: c.ordersLow, ordersHigh: c.ordersHigh,
        startEarliestMs: c.startEarliestAtUtc?.getTime() ?? null, startLatestMs: c.startLatestAtUtc.getTime(),
        endEarliestMs: c.endEarliestAtUtc?.getTime() ?? null, endLatestMs: c.endLatestAtUtc?.getTime() ?? null, endEvidence: c.endEvidence,
        gpsSamples: c.gpsSamples, gpsMaxGapMs: c.gpsMaxGapMs, gpsCoverage: c.gpsCoverage, gpsPathKm: c.gpsPathKm, gpsSpeedKm: c.gpsSpeedKm,
        gpsDistanceReliable: c.gpsDistanceReliable, missing: JSON.parse(c.missingJson) as string[], addedOffers: c.offers - 1,
        completionSlides: c.completionSlidesJson ? (JSON.parse(c.completionSlidesJson) as number[]).length : 0,
      },
      settings.vehicleCostPerKmAgorot,
    ),
  }));
  const perMin = (c: (typeof chains)[number]) => (c.e.durationMin ? c.row.revenueAgorot / ((c.e.durationMin.low + c.e.durationMin.high) / 2) : null);
  const rated = chains.filter((c) => perMin(c) !== null).sort((a, b) => perMin(a)! - perMin(b)!);

  // Where the time went: shares of the shift (wall clock).
  const totalMs = w.endMs - w.startMs;
  const moving = chainRows.reduce((a, c) => a + (c.gpsMovingMs ?? 0), 0);
  const still = chainRows.reduce((a, c) => a + (c.gpsStationaryMs ?? 0), 0);
  const between = shiftRow?.betweenChainsMs ?? null;
  const split = between !== null && chainRows.every((c) => c.gpsMovingMs !== null)
    ? { moving: moving / totalMs, still: still / totalMs, between: between / totalMs, unknown: Math.max(0, 1 - (moving + still + between) / totalMs) }
    : null;

  const filter = sp.filter === "accepted" || sp.filter === "declined" ? sp.filter : "all";
  const decisions = perf.decisions.filter((d) => d.seenAtMs >= w.startMs && d.seenAtMs <= w.endMs);
  const offerRows = await prisma.offer.findMany({ where: { id: { in: decisions.map((d) => d.offerId) } } });
  const shown = decisions
    .filter((d) => filter === "all" || d.decision === filter.toUpperCase())
    .sort((a, b) => b.seenAtMs - a.seenAtMs);
  const q = (extra: Record<string, string>) => `/review?${new URLSearchParams({ shift: w.id, ...extra }).toString()}`;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">אחרי משמרת</h1>
        <p className="text-sm text-muted">כמה באמת הרווחת לשעה, אחרי דלק ובלאי, ולאן הלך הזמן.</p>
      </div>
      <nav aria-label="משמרות" className="flex flex-wrap gap-2">
        {work.map((x) => (
          <Link key={x.id} href={`/review?shift=${x.id}`} aria-current={x.id === w.id ? "page" : undefined} className={cn("rounded-md border border-border px-3 py-1 text-sm", x.id === w.id && "bg-primary text-primary-foreground")}>
            {dm(x.startMs)} {hm(x.startMs)}
          </Link>
        ))}
      </nav>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <CardDescription>נטו לשעת משמרת</CardDescription>
              <EvidenceBadge e={s.netPerShiftHourAgorot ? "ESTIMATE" : "NOT_ENOUGH_DATA"} />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-4xl font-semibold" data-testid="net-per-shift-hour">{s.netPerShiftHourAgorot ? <ShekelRange {...s.netPerShiftHourAgorot} /> : "—"}</div>
            <p className="mt-2 text-sm text-muted">
              <TimeRange from={hm(w.startMs)} to={hm(w.endMs)} /> · {s.shiftHours.toFixed(1)} שעות כולל המתנות. ההכנסה נמדדה; הדלק מוערך מק״מ × ₪{((settings.vehicleCostPerKmAgorot ?? 0) / 100).toFixed(2)} לק״מ.
            </p>
            {s.netPerActiveHourAgorot && <p className="mt-1 text-sm">נטו לשעת משלוחים בלבד: <ShekelRange {...s.netPerActiveHourAgorot} /></p>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>כסף</CardTitle></CardHeader>
          <CardContent className="space-y-1.5 text-sm">
            <div className="flex justify-between"><span>הכנסה מהצעות שקיבלת</span><Shekel agorot={s.revenueAgorot} /></div>
            <div className="flex justify-between"><span>דלק ובלאי ({s.distanceKm ? `${((s.distanceKm.low + s.distanceKm.high) / 2).toFixed(1)} ק״מ` : "?"})</span><span>{s.vehicleCostAgorot ? <ShekelRange {...s.vehicleCostAgorot} negative /> : "—"}</span></div>
            {s.vehicleCostAgorot && <div className="flex justify-between font-semibold"><span>נטו</span><ShekelRange low={s.revenueAgorot - s.vehicleCostAgorot.high} high={s.revenueAgorot - s.vehicleCostAgorot.low} /></div>}
            {rated.length >= 2 && (
              <>
                <div className="flex justify-between pt-2"><span>₪ לדקת משלוח — הכי טוב</span><span><Shekel agorot={perMin(rated.at(-1)!)!} /> (<Shekel agorot={rated.at(-1)!.row.revenueAgorot} />)</span></div>
                <div className="flex justify-between"><span>₪ לדקת משלוח — הכי חלש</span><span><Shekel agorot={perMin(rated[0])!} /> (<Shekel agorot={rated[0].row.revenueAgorot} />)</span></div>
              </>
            )}
            {rec.official && (
              <div className="mt-3 rounded-md bg-background p-2">
                <div className="mb-1 flex items-center gap-2 font-medium">לפי Wolt ({day(w.startMs)}) <EvidenceBadge e="MEASURED" /></div>
                <div className="flex justify-between"><span>רווח כולל טיפים</span>{rec.official.earningsAgorot !== null ? <Shekel agorot={rec.official.earningsAgorot} /> : "—"}</div>
                <div className="flex justify-between"><span>מתוכם טיפים</span>{rec.official.tipsAgorot !== null ? <Shekel agorot={rec.official.tipsAgorot} /> : "—"}</div>
                <div className="flex justify-between"><span>משלוחים שהושלמו</span><span>{rec.official.completedDeliveries}</span></div>
                <p className="mt-1 text-xs text-muted">התאמה: {rec.status === "RECONCILED" ? "תואם" : rec.status === "CONSISTENT_UNVERIFIED" ? "תואם, עם קישורים לא ודאיים" : rec.status === "UNRECONCILED" ? "לא תואם" : "—"}</p>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {split && (
        <Card>
          <CardHeader>
            <CardTitle>לאן הלך הזמן</CardTitle>
            <CardDescription>עמידה = מהירות GPS מתחת ל-1 מ׳/ש׳: המתנה במסעדה, מסירה או רמזור — עוד לא אפשר להפריד.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex h-6 w-full overflow-hidden rounded-md" aria-hidden>
              {PARTS.filter((p) => split[p.key] > 0.005).map((p) => <div key={p.key} style={{ width: `${split[p.key] * 100}%`, background: p.color }} />)}
            </div>
            <ul className="space-y-1.5 text-sm">
              {PARTS.filter((p) => split[p.key] > 0.005).map((p) => (
                <li key={p.key} className="flex items-center gap-3">
                  <span className="inline-block size-3.5 rounded-sm" style={{ background: p.color }} aria-hidden />
                  <span className="flex-1">{p.label}</span>
                  <span>{Math.round((split[p.key] * totalMs) / 60_000)} דק׳</span>
                  <span className="w-12 font-semibold">{Math.round(split[p.key] * 100)}%</span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>משלוחים (שרשראות)</CardTitle>
          <CardDescription>שרשרת = מקבלה ועד המסירה האחרונה; הצעות שנוספו בזמן משלוח נספרות יחד איתה.</CardDescription>
        </CardHeader>
        <CardContent>
          <table className="w-full text-sm">
            <thead className="text-muted">
              <tr>
                <th className="text-start font-normal">התחלה</th>
                <th className="text-start font-normal">הצעות</th>
                <th className="text-start font-normal">הכנסה</th>
                <th className="text-start font-normal">דקות</th>
                <th className="text-start font-normal">₪ לדקה</th>
                <th className="text-start font-normal">ק״מ</th>
              </tr>
            </thead>
            <tbody>
              {chains.map((c) => (
                <tr key={c.row.chainKey} className="border-t border-border">
                  <td className="py-1.5">{hm(Number(c.row.chainKey))}</td>
                  <td>{c.row.offers}{c.row.offers > 1 ? " (כולל נוספות)" : ""}</td>
                  <td><Shekel agorot={c.row.revenueAgorot} /></td>
                  <td>{c.e.durationMin ? Math.round((c.e.durationMin.low + c.e.durationMin.high) / 2) : "—"}</td>
                  <td>{perMin(c) !== null ? <Shekel agorot={perMin(c)!} /> : "—"}</td>
                  <td>{c.e.distanceKm ? ((c.e.distanceKm.low + c.e.distanceKm.high) / 2).toFixed(1) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!w.precise && <p className="mt-2 text-xs text-muted">המרחק במשמרת הזו מחושב מריבועי 500 מ׳ ולכן כנראה נמוך מהאמת.</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>ההצעות במשמרת</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <nav aria-label="סינון הצעות" className="flex flex-wrap gap-2">
            {([["all", "הכל", decisions.length], ["accepted", "התקבלו", decisions.filter((d) => d.decision === "ACCEPTED").length], ["declined", "נדחו", decisions.filter((d) => d.decision === "DECLINED").length]] as const).map(([k, label, n]) => (
              <Link key={k} href={q(k === "all" ? {} : { filter: k })} aria-current={filter === k ? "page" : undefined} className={cn("rounded-md border border-border px-3 py-1 text-sm", filter === k && "bg-primary text-primary-foreground")}>
                {label} ({n})
              </Link>
            ))}
          </nav>
          <ul className="divide-y divide-border text-sm">
            {shown.map((d) => {
              const o = offerRows.find((x) => x.id === d.offerId);
              const names = o ? (JSON.parse(o.pickupNamesJson) as string[]) : [];
              return (
                <li key={d.offerId} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <span>{hm(d.seenAtMs)} · <Shekel agorot={d.amountAgorot} />{d.distanceKm !== null ? ` · ${d.distanceKm} ק״מ` : ""}{names.length ? ` · ${names.join(", ")}` : ""}</span>
                  <span className={cn("rounded-full px-2 py-0.5 text-xs", d.decision === "ACCEPTED" ? "bg-success-bg text-success-fg" : d.decision === "DECLINED" ? "bg-danger-bg text-danger-fg" : "bg-background")}>
                    {d.decision === "ACCEPTED" ? "התקבלה" : d.decision === "DECLINED" ? "נדחתה" : "לא ידוע"}
                  </span>
                </li>
              );
            })}
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
