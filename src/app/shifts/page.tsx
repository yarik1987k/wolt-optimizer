import { ShiftsManager } from "@/components/shifts/shifts-manager";
import { listShiftsWithMetrics } from "@/lib/analytics/shifts";
import { prisma } from "@/lib/db";
import { getSettings } from "@/lib/settings";

export const dynamic = "force-dynamic";

export default async function ShiftsPage() {
  const [shifts, experiments, settings] = await Promise.all([
    listShiftsWithMetrics(),
    prisma.experiment.findMany({ where: { status: { not: "COMPLETE" } }, select: { id: true, title: true }, orderBy: { createdAt: "desc" } }),
    getSettings(),
  ]);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">יומן משמרות</h1>
        <p className="text-sm text-muted">
          תיעוד ידני בסוף משמרת — הנתונים החסרים בדוחות (משך, ק״מ). הכנסה משויכת למשמרת רק אחרי אישור ידני של הרשומות; רשומה יכולה להיות משויכת למשמרת אחת בלבד.
        </p>
      </div>
      <ShiftsManager
        shifts={shifts.map((s) => ({ ...s, links: undefined }))}
        experiments={experiments}
        defaultCostPerKmAgorot={settings.vehicleCostPerKmAgorot}
      />
    </div>
  );
}
