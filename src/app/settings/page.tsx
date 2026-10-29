import { SettingsForm } from "@/components/settings/settings-form";
import { Card, CardContent } from "@/components/ui/card";
import { getSettings } from "@/lib/settings";

export const dynamic = "force-dynamic";

export default async function SettingsPage() {
  const settings = await getSettings();
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">הגדרות</h1>
        <p className="text-sm text-muted">
          סיווג שורות מבוסס על תוכן ההערה/בית העסק בלבד — לעולם לא על גובה הסכום. שורה שלא מתאימה לאף כלל ואינה משלוח רגיל מסווגת ״לא מסווג״ ודורשת סיווג ידני בעמוד הרשומות.
        </p>
      </div>
      <Card><CardContent className="pt-4"><SettingsForm settings={settings} /></CardContent></Card>
    </div>
  );
}
