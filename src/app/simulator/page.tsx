import { SimulatorForm } from "@/components/simulator/simulator-form";
import { simulatorPresets } from "@/lib/offers/service";

export const dynamic = "force-dynamic";

export default async function SimulatorPage() {
  const presets = await simulatorPresets();
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">סימולטור הכנסה למשמרת</h1>
        <p className="text-sm text-muted">
          הכנסה = מספר משלוחים × תשלום ממוצע. כלי לבדיקת תרחישים מול יעד של ₪400 — לא תחזית ולא הבטחה. כשזמן הביצוע למשלוח לא ידוע, התוצאה היא חישוב מתמטי בלבד.
        </p>
      </div>
      <SimulatorForm presets={presets} />
    </div>
  );
}
