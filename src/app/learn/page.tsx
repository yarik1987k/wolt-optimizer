import { EvidenceBadge } from "@/components/plan/evidence";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { prisma } from "@/lib/db";
import { evidenceReport } from "@/lib/evidence/plan";
import { MIN_SHIFTS_REPEATED, type Evidence } from "@/lib/plan/decisions";

export const dynamic = "force-dynamic";

const sh = (agorot: number) => `₪${(agorot / 100).toFixed(2)}`;

interface Item {
  title: string;
  e: Evidence;
  body: React.ReactNode;
}

/** What works: the same findings as the app's "מה עובד" tab, each with how much it can be trusted — and the next experiment. */
export default async function LearnPage() {
  const [r, eta] = await Promise.all([evidenceReport(), prisma.collectorEtaCheck.findMany()]);
  const etaErr = eta.map((k) => k.errorVsFirstRouteMin).filter((x): x is number => x !== null);
  const work = r.workShifts;
  const last = work.at(-1);
  const chains = work.flatMap((w) => w.chainRates);
  const withAdded = chains.filter((c) => c.offers > 1).length;
  const standalone = chains.filter((c) => c.offers === 1).length;
  const minShifts = r.settings.evidenceMinShiftsForValidation;
  const e = r.experiment;

  const items: Item[] = [];
  if (last?.netPerShiftHourAgorot) {
    const n = last.netPerShiftHourAgorot;
    items.push({
      title: "נטו לשעת משמרת",
      e: "MEASURED",
      body: (
        <>
          במשמרת האחרונה: <span dir="ltr">{sh(n.low)} - {sh(n.high)}</span> לשעה, כולל זמני המתנה ואחרי דלק ובלאי.{" "}
          {work.length < 2 ? "משמרת אחת — עוד לא ידוע כמה זה משתנה בין משמרות." : `${work.length} משמרות עד עכשיו.`}
        </>
      ),
    });
  }
  if (last?.chainStationaryMin != null) {
    items.push({
      title: "עמידה בתוך משלוחים",
      e: work.length >= MIN_SHIFTS_REPEATED ? "REPEATED" : "HYPOTHESIS",
      body: `${Math.round((last.chainStationaryMin / (last.hours * 60)) * 100)}% מזמן המשמרת האחרונה עמדת במקום בתוך משלוחים (המתנה במסעדה, מסירה, רמזורים — עוד לא אפשר להפריד). זה החלק הגדול ביותר אחרי הנסיעה.`,
    });
  }
  items.push({
    title: "לקבל הצעה נוספת בזמן משלוח",
    e: withAdded === 0 ? "NOT_ENOUGH_DATA" : "HYPOTHESIS",
    body:
      withAdded === 0
        ? "עוד לא היו משלוחים עם הצעה נוספת."
        : `${withAdded} ${withAdded === 1 ? "שרשרת" : "שרשראות"} עם הצעות נוספות. שרשרת כזו הרוויחה יותר לשעה, אבל דוגמה אחת לא מוכיחה — צריך להשוות משמרות (ניסוי 1).`,
  });
  items.push({
    title: "מחיר וק״מ בכרטיס מנבאים רווח",
    e: "NOT_ENOUGH_DATA",
    body: `${standalone} משלוחים בודדים עד עכשיו. צריך הרבה יותר משלוחים, במחירים ובמרחקים שונים, לפני שאפשר לדעת.`,
  });
  if (etaErr.length > 0) {
    items.push({
      title: "שעת היעד של Wolt",
      e: "TESTED",
      body: (
        <>
          נבדק מול {etaErr.length} מסירות: הגעת תמיד אחרי השעה שהציגה Wolt, ב-
          <span dir="ltr">{Math.round(Math.min(...etaErr))}-{Math.round(Math.max(...etaErr))}</span> דק׳. זו שעת יעד קבועה, לא הערכת נסיעה — לא לתכנן לפיה.
        </>
      ),
    });
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">מה עובד</h1>
        <p className="text-sm text-muted">
          מה כבר הוכח, מה רק השערה, ומה עוד לא ידוע — מהמשמרות שלך. {work.length} {work.length === 1 ? "משמרת עבודה" : "משמרות עבודה"} עד עכשיו; ממצא מסומן
          &quot;חוזר על עצמו&quot; רק אחרי {MIN_SHIFTS_REPEATED} משמרות לפחות.
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        {items.map((f) => (
          <Card key={f.title}>
            <CardHeader>
              <div className="flex flex-wrap items-center gap-2">
                <CardTitle>{f.title}</CardTitle>
                <EvidenceBadge e={f.e} />
              </div>
            </CardHeader>
            <CardContent className="text-sm">{f.body}</CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>ניסוי 1: הצעה נוספת בזמן משלוח</CardTitle>
          <CardDescription>לא התחיל — רק אם תחליט/י.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p><span className="font-medium">השאלה: </span>כשכבר יש לך הזמנה ברכב ומגיעה הצעה נוספת — לקבל אותה מכניס יותר לשעה, או פחות?</p>
          <p>
            <span className="font-medium">איך בודקים: </span>חלק מהמשמרות כרגיל (לקבל), חלק עם כלל אחד — לדחות כל הצעה שמגיעה בזמן משלוח. הטלת מטבע לפני המשמרת
            קובעת איזה סוג. אחרי כ-{minShifts} משמרות מכל סוג משווים נטו לשעת משמרת.
          </p>
          <div className="flex flex-wrap gap-6">
            <span>משמרות רגילות: 0 מתוך {minShifts}</span>
            <span>משמרות בלי הצעות נוספות: 0 מתוך {minShifts}</span>
          </div>
          <p className="text-xs text-muted">דחיית הצעות עלולה להשפיע על המעמד שלך ב-Wolt — ההחלטה שלך.</p>
          <details>
            <summary className="cursor-pointer">פרטי התכנון המלאים (אנגלית)</summary>
            <div className="mt-2 space-y-2" dir="ltr" lang="en">
              <p><span className="font-medium">Baseline: </span>{e.baseline.basis}. {e.baseline.uncertainty}.</p>
              <p><span className="font-medium">Primary metric: </span>{e.primaryMetric}</p>
              <p className="font-medium">Would support / contradict:</p>
              <ul className="list-disc ps-5">
                <li>{e.supporting}</li>
                {e.contradicting.map((c) => <li key={c}>{c}</li>)}
              </ul>
              <p className="font-medium">Confounders:</p>
              <ul className="list-disc ps-5">{e.confounders.map((c) => <li key={c.factor}>{c.factor} — {c.mitigation}</li>)}</ul>
              <p className="font-medium">Not yet built:</p>
              <ul className="list-disc ps-5">{e.dataRequired.filter((d) => d.status === "NOT_YET_BUILT").map((d) => <li key={d.item}>{d.item}</li>)}</ul>
            </div>
          </details>
        </CardContent>
      </Card>
    </div>
  );
}
