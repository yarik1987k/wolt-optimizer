import Link from "next/link";
import { ExperimentCreate } from "@/components/experiments/experiment-create";
import { ExperimentDetail } from "@/components/experiments/experiment-detail";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getExperimentWithComparison } from "@/lib/analytics/experiments";
import { listShiftsWithMetrics } from "@/lib/analytics/shifts";
import { prisma } from "@/lib/db";
import { cn } from "@/lib/utils";
import { EXPERIMENT_STATUS_LABELS } from "@/lib/types";

export const dynamic = "force-dynamic";

export default async function ExperimentsPage({ searchParams }: { searchParams: Promise<{ id?: string }> }) {
  const { id } = await searchParams;
  const experiments = await prisma.experiment.findMany({ orderBy: { createdAt: "desc" }, include: { _count: { select: { shifts: true } } } });
  const selectedId = id && experiments.some((e) => e.id === id) ? id : experiments[0]?.id;
  const detail = selectedId ? await getExperimentWithComparison(selectedId) : null;
  const shifts = detail ? await listShiftsWithMetrics() : [];
  const groupOf = new Map(detail?.experiment.shifts.map((s) => [s.shiftId, s.group as "CONTROL" | "TEST"]));

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">ניסויים</h1>
        <p className="text-sm text-muted">צור ניסוי מהשערה (בדשבורד: ״בדוק במשמרת״) או ידנית, שייך משמרות לקבוצת ביקורת וניסוי, והשווה ₪ לשעת משמרת.</p>
      </div>
      <div className="grid gap-4 lg:grid-cols-[16rem_1fr]">
        <aside className="min-w-0 space-y-2" aria-label="רשימת ניסויים">
          {experiments.length === 0 && <p className="text-sm text-muted">עדיין אין ניסויים.</p>}
          {experiments.map((e) => (
            <Link
              key={e.id}
              href={`/experiments?id=${e.id}`}
              className={cn("block rounded-md border border-border bg-surface p-3 text-sm", e.id === selectedId && "outline-2 outline-primary")}
            >
              <div className="font-medium">{e.title}</div>
              <div className="mt-1 flex gap-1">
                <Badge>{EXPERIMENT_STATUS_LABELS[e.status as keyof typeof EXPERIMENT_STATUS_LABELS]}</Badge>
                <Badge>{e._count.shifts} משמרות</Badge>
              </div>
            </Link>
          ))}
        </aside>
        <div className="min-w-0 space-y-4">
          {detail && (
            <ExperimentDetail
              experiment={detail.experiment}
              comparison={detail.comparison}
              shifts={shifts.map((s) => ({
                id: s.id,
                date: s.date,
                startTime: s.startTime,
                endTime: s.endTime,
                areaText: s.areaText,
                durationHours: s.metrics.duration_hours,
                linkedRecordCount: s.metrics.linked_record_count,
                group: groupOf.get(s.id) ?? null,
              }))}
            />
          )}
          <Card>
            <CardHeader><CardTitle>ניסוי חדש (ידני)</CardTitle></CardHeader>
            <CardContent><ExperimentCreate /></CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
