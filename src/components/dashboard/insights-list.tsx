"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Alert } from "@/components/ui/alert";
import { formatShekel } from "@/lib/money";
import type { Insight, InsightsResult } from "@/lib/analytics/insights";

function confidenceVariant(i: Insight) {
  if (i.status === "OBSERVATION") return "danger" as const;
  return i.confidence_label.startsWith("מדגם סביר") ? ("success" as const) : ("warning" as const);
}

function InsightCard({ insight, filterQuery }: { insight: Insight; filterQuery: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function test() {
    setBusy(true);
    setError(null);
    const res = await fetch("/api/experiments", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ fromInsight: { key: insight.key, filterQuery } }),
    });
    const body = await res.json();
    setBusy(false);
    if (!res.ok) return setError(body.error ?? "שגיאה");
    router.push(`/experiments?id=${body.id}`);
  }
  const g = insight.group;
  const b = insight.baseline;
  return (
    <Card data-testid="insight-card">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={confidenceVariant(insight)}>{insight.confidence_label}</Badge>
          {insight.status === "OBSERVATION" && <Badge>תצפית בלבד</Badge>}
        </div>
        <CardTitle>{insight.title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        <p>
          <strong>A:</strong> ממוצע <span className="num">{formatShekel(g.mean)}</span>, חציון <span className="num">{formatShekel(g.median)}</span> לרשומה
          (n=<span className="num">{g.n}</span>, {insight.distinct_dates === 1 ? "תאריך אחד" : <><span className="num">{insight.distinct_dates}</span> תאריכים</>})
          <br />
          <strong>B (קו בסיס — שאר הרשומות):</strong> ממוצע <span className="num">{formatShekel(b.mean)}</span>, חציון{" "}
          <span className="num">{formatShekel(b.median)}</span> (n=<span className="num">{b.n}</span>)
        </p>
        <p>
          פער ממוצעים <span className="num">{insight.mean_gap_pct}%</span> · פער חציונים <span className="num">{insight.median_gap_pct}%</span> · פער שמרני{" "}
          <span className="num">{insight.robust_gap_pct}%</span>
        </p>
        <p className="text-muted">{insight.uncertainty}</p>
        <p>{insight.hypothesis}</p>
        <details>
          <summary className="cursor-pointer text-muted">מגבלות</summary>
          <ul className="mt-1 list-disc space-y-1 ps-5 text-muted">
            {insight.limitations.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </details>
        {error && <Alert level="error">{error}</Alert>}
        <Button size="sm" onClick={test} disabled={busy}>
          {busy ? "יוצר ניסוי…" : "בדוק במשמרת"}
        </Button>
      </CardContent>
    </Card>
  );
}

export function InsightsList({ result, filterQuery }: { result: InsightsResult; filterQuery: string }) {
  return (
    <section aria-labelledby="insights-h" className="space-y-3">
      <h2 id="insights-h" className="text-lg font-semibold">השערות לבדיקה</h2>
      <p className="text-sm text-muted">
        השערות מבוססות נתוני עבר — לא הוראות. {result.method}
      </p>
      {result.explanation && <Alert level="info">{result.explanation}</Alert>}
      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
        {result.hypotheses.map((i) => (
          <InsightCard key={i.key} insight={i} filterQuery={filterQuery} />
        ))}
      </div>
      {result.observations.length > 0 && (
        <>
          <h3 className="pt-2 font-semibold">תצפיות על מדגם קטן (לא להמלצה)</h3>
          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
            {result.observations.map((i) => (
              <InsightCard key={i.key} insight={i} filterQuery={filterQuery} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
