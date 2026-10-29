"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Hint, Input, Label, Textarea } from "@/components/ui/form";
import type { AppSettings } from "@/lib/settings";

export function SettingsForm({ settings }: { settings: AppSettings }) {
  const router = useRouter();
  const [cost, setCost] = useState(settings.vehicleCostPerKmAgorot != null ? (settings.vehicleCostPerKmAgorot / 100).toFixed(2) : "");
  const [bonus, setBonus] = useState(settings.classificationRules.bonusPatterns.join("\n"));
  const [adj, setAdj] = useState(settings.classificationRules.adjustmentPatterns.join("\n"));
  const [minObs, setMinObs] = useState(String(settings.minSampleObservation));
  const [minTest, setMinTest] = useState(String(settings.minSampleTestCandidate));
  const [minShifts, setMinShifts] = useState(String(settings.minShiftsPerGroup));
  const [msg, setMsg] = useState<{ level: "success" | "error"; text: string } | null>(null);
  const lines = (s: string) => s.split("\n").map((x) => x.trim()).filter(Boolean);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    const res = await fetch("/api/settings", {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        vehicleCostPerKmAgorot: cost.trim() ? Math.round(Number(cost) * 100) : null,
        classificationRules: { bonusPatterns: lines(bonus), adjustmentPatterns: lines(adj) },
        minSampleObservation: Number(minObs),
        minSampleTestCandidate: Number(minTest),
        minShiftsPerGroup: Number(minShifts),
      }),
    });
    const body = await res.json();
    setMsg(res.ok ? { level: "success", text: "נשמר" } : { level: "error", text: [body.error, ...(body.issues ?? [])].join(" · ") });
    router.refresh();
  }
  async function reclassify() {
    const res = await fetch("/api/settings/reclassify", { method: "POST" });
    const body = await res.json();
    setMsg({ level: "success", text: `נבדקו ${body.checked} רשומות שלא סווגו ידנית; ${body.changed} שינו סוג.` });
    router.refresh();
  }

  return (
    <form onSubmit={save} className="grid gap-4 sm:grid-cols-2">
      <Label>
        עלות רכב לק״מ (₪) — ברירת מחדל לטופס משמרת
        <Input type="number" min="0" step="0.01" value={cost} onChange={(e) => setCost(e.target.value)} placeholder="לא הוגדר" />
        <Hint>הערך האישי שלך (דלק, בלאי וכו׳). המערכת לא מעריכה אותו.</Hint>
      </Label>
      <div />
      <Label>
        ביטויי בונוס (שורה לכל ביטוי, regex)
        <Textarea rows={5} value={bonus} onChange={(e) => setBonus(e.target.value)} className="num text-start" />
      </Label>
      <Label>
        ביטויי תוספת (שורה לכל ביטוי, regex)
        <Textarea rows={5} value={adj} onChange={(e) => setAdj(e.target.value)} className="num text-start" />
      </Label>
      <Label>
        סף ״מדגם קטן״ (n &lt;)
        <Input type="number" min="1" value={minObs} onChange={(e) => setMinObs(e.target.value)} />
      </Label>
      <Label>
        סף ״מדגם סביר לניסוי״ (n ≥)
        <Input type="number" min="1" value={minTest} onChange={(e) => setMinTest(e.target.value)} />
      </Label>
      <Label>
        מינימום משמרות לכל קבוצה בניסוי
        <Input type="number" min="1" value={minShifts} onChange={(e) => setMinShifts(e.target.value)} />
      </Label>
      {msg && <Alert level={msg.level} className="sm:col-span-2">{msg.text}</Alert>}
      <div className="flex flex-wrap gap-2 sm:col-span-2">
        <Button type="submit">שמור הגדרות</Button>
        <Button type="button" variant="outline" onClick={reclassify}>החל כללי סיווג מחדש על רשומות קיימות</Button>
      </div>
    </form>
  );
}
