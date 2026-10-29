"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input, Label, Textarea } from "@/components/ui/form";

export function ExperimentCreate() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [hypothesisText, setHypothesis] = useState("");
  const [startDate, setStartDate] = useState("");
  const [error, setError] = useState<string | null>(null);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const res = await fetch("/api/experiments", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ title, hypothesisText, ...(startDate ? { startDate } : {}) }),
    });
    const body = await res.json();
    if (!res.ok) return setError(body.error);
    router.push(`/experiments?id=${body.id}`);
    router.refresh();
  }
  return (
    <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2">
      <Label>
        כותרת
        <Input required value={title} onChange={(e) => setTitle(e.target.value)} />
      </Label>
      <Label>
        תאריך התחלה
        <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
      </Label>
      <Label className="sm:col-span-2">
        תיאור ההשערה / ההמלצה
        <Textarea required value={hypothesisText} onChange={(e) => setHypothesis(e.target.value)} />
      </Label>
      {error && <Alert level="error" className="sm:col-span-2">{error}</Alert>}
      <div><Button type="submit">צור ניסוי</Button></div>
    </form>
  );
}
