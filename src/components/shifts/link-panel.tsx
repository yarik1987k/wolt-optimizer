"use client";
import { useEffect, useState } from "react";
import { Money, Num } from "@/components/num";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, Td, Th } from "@/components/ui/table";
import { formatDateHe } from "@/lib/time";
import { RECORD_TYPE_LABELS, type RecordType } from "@/lib/types";

interface Suggestion {
  record: { id: string; eventDate: string; eventTime: string; merchantRaw: string; amountAgorot: number; type: string; isBundle: boolean };
  afterShiftEnd: boolean;
  linkedToShiftId: string | null;
  linkedToThisShift: boolean;
  linkedShift: { date: string; startTime: string; endTime: string } | null;
}

/** Suggested matches by report timestamp. Nothing is linked until the user confirms. */
export function LinkPanel({ shiftId, onChanged }: { shiftId: string; onChanged: () => void }) {
  const [items, setItems] = useState<Suggestion[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [message, setMessage] = useState<{ level: "error" | "success"; text: string } | null>(null);

  async function load() {
    const res = await fetch(`/api/shifts/${shiftId}/links`);
    const data: Suggestion[] = await res.json();
    setItems(data);
    // Pre-select only free records inside the window; the user still has to confirm.
    setSelected(new Set(data.filter((s) => !s.linkedToShiftId && !s.afterShiftEnd).map((s) => s.record.id)));
  }
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shiftId]);

  async function confirm() {
    const res = await fetch(`/api/shifts/${shiftId}/links`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ recordIds: [...selected], confirm: true }),
    });
    const body = await res.json();
    setMessage(res.ok ? { level: "success", text: `שויכו ${body.linked} רשומות` } : { level: "error", text: body.error });
    await load();
    onChanged();
  }
  async function unlink(id: string) {
    await fetch(`/api/shifts/${shiftId}/links`, { method: "DELETE", headers: { "content-type": "application/json" }, body: JSON.stringify({ recordIds: [id] }) });
    await load();
    onChanged();
  }

  if (!items) return <p className="text-sm text-muted">טוען…</p>;
  const toggle = (id: string) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    return n;
  });
  const pending = [...selected].filter((id) => !items.find((i) => i.record.id === id)?.linkedToShiftId);

  return (
    <div className="space-y-2" data-testid="link-panel">
      <p className="text-xs text-muted">
        הצעה בלבד: רשומות ששעת הרישום שלהן בדוח נמצאת בחלון המשמרת או עד 30 דקות אחריו. שעת הרישום עשויה להיות שונה משעת הביצוע — סמן ידנית ואשר.
      </p>
      {items.length === 0 ? (
        <Alert level="info">אין רשומות מתאימות בחלון הזמן של המשמרת (ייתכן שהדוח עדיין לא יובא).</Alert>
      ) : (
        <Table>
          <thead><tr><Th></Th><Th>תאריך</Th><Th>שעה</Th><Th>בית עסק</Th><Th>סכום</Th><Th>סוג</Th><Th>מצב</Th></tr></thead>
          <tbody>
            {items.map((s) => {
              const other = s.linkedToShiftId && !s.linkedToThisShift;
              return (
                <tr key={s.record.id} className={other ? "opacity-60" : undefined}>
                  <Td>
                    {s.linkedToThisShift ? (
                      <Button size="sm" variant="ghost" onClick={() => unlink(s.record.id)}>בטל שיוך</Button>
                    ) : (
                      <input type="checkbox" aria-label="בחר רשומה" disabled={!!other} checked={selected.has(s.record.id)} onChange={() => toggle(s.record.id)} />
                    )}
                  </Td>
                  <Td><Num>{formatDateHe(s.record.eventDate)}</Num></Td>
                  <Td><Num>{s.record.eventTime}</Num></Td>
                  <Td className="text-xs">{s.record.merchantRaw}</Td>
                  <Td><Money agorot={s.record.amountAgorot} /></Td>
                  <Td className="text-xs">{RECORD_TYPE_LABELS[s.record.type as RecordType]}</Td>
                  <Td className="space-x-1 text-xs">
                    {s.linkedToThisShift && <Badge variant="success">מאושר</Badge>}
                    {other && s.linkedShift && <Badge variant="warning">משויך למשמרת {formatDateHe(s.linkedShift.date)} {s.linkedShift.startTime}</Badge>}
                    {s.afterShiftEnd && <Badge>אחרי סיום המשמרת</Badge>}
                  </Td>
                </tr>
              );
            })}
          </tbody>
        </Table>
      )}
      {message && <Alert level={message.level}>{message.text}</Alert>}
      {pending.length > 0 && (
        <Button size="sm" onClick={confirm}>אשר שיוך של {pending.length} רשומות</Button>
      )}
    </div>
  );
}
