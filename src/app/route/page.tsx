import Link from "next/link";
import { COLOR_BETWEEN, COLOR_DELIVERING, COLOR_DROPOFF, COLOR_PICKUP, RouteMap } from "@/components/plan/route-map";
import { TimeRange } from "@/components/plan/evidence";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent } from "@/components/ui/card";
import { workShifts } from "@/lib/plan/decisions";
import { shiftRoute } from "@/lib/plan/route";
import { TIME_ZONE } from "@/lib/time";
import { cn } from "@/lib/utils";

export const dynamic = "force-dynamic";

const hm = (ms: number) => new Date(ms).toLocaleTimeString("en-GB", { timeZone: TIME_ZONE, hour: "2-digit", minute: "2-digit" });
const dm = (ms: number) => new Date(ms).toLocaleDateString("he-IL", { timeZone: TIME_ZONE, day: "numeric", month: "numeric" });

const LEGEND = [
  { color: COLOR_DELIVERING, text: "בזמן משלוח (מקבלה ועד המסירה האחרונה)", line: true },
  { color: COLOR_BETWEEN, text: "בין משלוחים", line: true },
  { color: COLOR_PICKUP, text: "איסוף (משוער: כשהופיע מסך המסירה)", line: false },
  { color: COLOR_DROPOFF, text: "מסירה (נצפתה: החלקת «заказ доставлен»)", line: false },
  { color: "#2C2C2A", text: "התחלה / סוף", line: false },
];

/** Where you drove: one shift, or all shifts on one map (others faded). */
export default async function RoutePage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const sp = await searchParams;
  const { work } = await workShifts();
  if (!work.length) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">מסלול</h1>
        <Alert level="info">עדיין אין משמרת עבודה עם מיקום.</Alert>
      </div>
    );
  }
  const all = sp.shift === "all";
  const focus = all ? null : (work.find((w) => w.id === sp.shift) ?? work[0]).id;
  const routes = await Promise.all((all ? work : work.filter((w) => w.id === focus)).map((w) => shiftRoute(w.id)));
  const f = routes.find((r) => r.shiftId === focus);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">מסלול</h1>
        <p className="text-sm text-muted">איפה נסעת: קו המסלול, איסופים ומסירות. לצפייה אחרי המשמרת.</p>
      </div>
      <nav aria-label="משמרות" className="flex flex-wrap gap-2">
        {work.map((w) => (
          <Link key={w.id} href={`/route?shift=${w.id}`} aria-current={w.id === focus ? "page" : undefined} className={cn("rounded-md border border-border px-3 py-1 text-sm", w.id === focus && "bg-primary text-primary-foreground")}>
            {dm(w.startMs)} {hm(w.startMs)}
          </Link>
        ))}
        {work.length > 1 && (
          <Link href="/route?shift=all" aria-current={all ? "page" : undefined} className={cn("rounded-md border border-border px-3 py-1 text-sm", all && "bg-primary text-primary-foreground")}>
            כל המשמרות
          </Link>
        )}
      </nav>
      {f && (
        <p className="text-sm">
          <TimeRange from={hm(f.startMs ?? 0)} to={hm(f.endMs ?? 0)} /> · {f.km.toFixed(1)} ק״מ סה״כ · {f.deliveringKm.toFixed(1)} ק״מ במשלוחים · {f.points} נקודות ·{" "}
          {f.precision === "PRECISE" ? "מסלול מדויק (~5 מ׳)" : <span className="text-warning-fg">מסלול גס: במשמרת הזו נשמרו רק ריבועים של ~500 מ׳, הקו קופץ בין מרכזי ריבועים ולא עובר ברחובות.</span>}
        </p>
      )}
      <RouteMap routes={routes} focus={focus} />
      <Card>
        <CardContent className="grid gap-1.5 pt-4 text-sm sm:grid-cols-2">
          {LEGEND.map((l) => (
            <div key={l.text} className="flex items-center gap-2">
              <span className={l.line ? "inline-block h-1 w-6 rounded" : "inline-block size-3 rounded-full"} style={{ background: l.color }} aria-hidden />
              {l.text}
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
