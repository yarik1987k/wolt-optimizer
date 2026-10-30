import Link from "next/link";
import { EvidenceBadge, Shekel } from "@/components/plan/evidence";
import { WaitSpots } from "@/components/plan/wait-spots";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { performanceReport } from "@/lib/history/performance";
import { byShiftHour, MIN_SHIFTS_REPEATED, timeWindows, waitSpots, workShifts, type Evidence } from "@/lib/plan/decisions";

export const dynamic = "force-dynamic";

const STRATEGY_HE: Record<string, string> = {
  "accept-all": "לקבל כל הצעה",
  "min-₪25": "לקבל רק מ-₪25 ומעלה",
  "min-₪6/km": "לקבל רק מ-₪6 לק״מ ומעלה",
};

function Title({ children, e }: { children: React.ReactNode; e: Evidence }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <CardTitle>{children}</CardTitle>
      <EvidenceBadge e={e} />
    </div>
  );
}

/** Before the shift: when to work, where to wait, which offers, when to stop — from your own shifts. */
export default async function PlanPage() {
  const { work, short } = await workShifts();
  const [spots, hours, perf] = await Promise.all([waitSpots(work), byShiftHour(work), performanceReport()]);
  const windows = timeWindows(work);
  const windowsEvidence: Evidence = windows.length === 0 ? "NOT_ENOUGH_DATA" : windows.some((w) => w.evidence === "REPEATED") ? "REPEATED" : "HYPOTHESIS";

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">לפני משמרת</h1>
        <p className="text-sm text-muted">
          מתי, איפה ואיך לעבוד — לפי {work.length} {work.length === 1 ? "משמרת עבודה" : "משמרות עבודה"} שלך
          {short.length ? ` (${short.length} סשנים קצרים/ניסיון לא נספרים)` : ""}. תיאור בלבד — בלי המלצות בזמן משמרת. דבר לא נקבע &quot;טוב יותר&quot; לפני
          שחזר על עצמו ב-{MIN_SHIFTS_REPEATED} משמרות לפחות.
        </p>
      </div>
      {work.length === 0 && (
        <Alert level="info">
          עדיין אין משמרת עבודה (שעה ומעלה). <Link className="underline" href="/imports">ייבא נתונים מהאפליקציה</Link> אחרי משמרת.
        </Alert>
      )}

      <Card>
        <CardHeader>
          <Title e={windowsEvidence}>מתי לעבוד</Title>
          <CardDescription>נטו לשעת משמרת (כולל המתנות, אחרי דלק) לפי יום ושעת התחלה.</CardDescription>
        </CardHeader>
        <CardContent>
          {windows.length === 0 ? (
            <p className="text-sm text-muted">אין עדיין נתונים.</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-muted">
                <tr>
                  <th className="text-start font-normal">חלון</th>
                  <th className="text-start font-normal">משמרות</th>
                  <th className="text-start font-normal">נטו לשעה (ממוצע)</th>
                  <th className="text-start font-normal">טווח</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {windows.map((w) => (
                  <tr key={w.label} className="border-t border-border">
                    <td className="py-1.5">{w.label}</td>
                    <td>{w.shifts}</td>
                    <td><Shekel agorot={w.mean} /></td>
                    <td>{w.shifts > 1 ? <span dir="ltr">₪{(w.low / 100).toFixed(0)} - ₪{(w.high / 100).toFixed(0)}</span> : "—"}</td>
                    <td><EvidenceBadge e={w.evidence} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="mt-3 text-xs text-muted">
            כל עוד לחלון יש פחות מ-{MIN_SHIFTS_REPEATED} משמרות זה רק מה שקרה, לא השוואה. ההבדל בין חלונות נחשב רק כשהטווחים לא חופפים.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Title e={spots.length === 0 ? "NOT_ENOUGH_DATA" : spots.some((s) => s.evidence === "REPEATED") ? "REPEATED" : "HYPOTHESIS"}>איפה לחכות</Title>
          <CardDescription>
            כמה דקות (חציון) עברו מסוף משלוח או תחילת משמרת ועד ההצעה הבאה, לפי המקום שבו התחלת לחכות. המספרים על המפה = המקומות ברשימה.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {spots.length === 0 ? <p className="text-sm text-muted">עוד אין המתנות.</p> : <WaitSpots spots={spots} />}
          <p className="mt-3 text-xs text-muted">צריך כמה המתנות באותו מקום בשתי משמרות לפחות לפני השוואה בין מקומות.</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Title e={hours.length === 0 ? "NOT_ENOUGH_DATA" : hours.every((h) => h.evidence === "REPEATED") ? "REPEATED" : "HYPOTHESIS"}>מתי לסיים</Title>
          <CardDescription>הכנסה מהצעות שהתחילו בכל שעה של המשמרת, לפני דלק. אם השעות האחרונות חלשות באופן קבוע — שם כדאי לסיים.</CardDescription>
        </CardHeader>
        <CardContent>
          {hours.length === 0 ? (
            <p className="text-sm text-muted">אין עדיין נתונים.</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {hours.map((h) => {
                const max = Math.max(...hours.map((x) => x.mean), 1);
                return (
                  <li key={h.index} className="flex items-center gap-3">
                    <span className="w-28 shrink-0">שעה {h.index} · {h.shifts} {h.shifts === 1 ? "משמרת" : "משמרות"}</span>
                    <span className="h-3 rounded-sm bg-[#185FA5]" style={{ width: `${Math.max(2, (h.mean / max) * 60)}%` }} aria-hidden />
                    <Shekel agorot={h.mean} />
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <Title e="NOT_ENOUGH_DATA">כללים להצעות</Title>
          <CardDescription>
            הכללים שלך נקבעים באפליקציה (לפני ← הכללים שלך). כאן: איך כללים לדוגמה היו מתנהגים על ההחלטות שלך. מה היה קורה במקום ההצעות שהכלל היה משנה — לא ידוע, לכן אין כאן סכום כסף.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <table className="w-full text-sm">
            <thead className="text-muted">
              <tr>
                <th className="text-start font-normal">כלל</th>
                <th className="text-start font-normal">מסכים איתך</th>
                <th className="text-start font-normal">היה דוחה מה שקיבלת</th>
                <th className="text-start font-normal">היה מקבל מה שדחית</th>
              </tr>
            </thead>
            <tbody>
              {perf.strategies.map((s) => (
                <tr key={s.name} className="border-t border-border">
                  <td className="py-1.5">{STRATEGY_HE[s.name] ?? s.description}</td>
                  <td>{s.agreeAccept + s.agreeDecline} / {s.offers}</td>
                  <td>{s.wouldDeclineAccepted}</td>
                  <td>{s.wouldAcceptDeclined}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
