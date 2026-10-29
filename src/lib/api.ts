import { NextResponse } from "next/server";
import { ZodError } from "zod";
import { Prisma } from "@prisma/client";
import { LinkConflictError, LinkValidationError } from "@/lib/analytics/shifts";
import { ImportValidationError } from "@/lib/import/service";

export function json(data: unknown, init?: ResponseInit) {
  return NextResponse.json(data, init);
}

/** Maps domain errors to HTTP responses with a Hebrew message. */
export async function handle(fn: () => Promise<unknown>) {
  try {
    const out = await fn();
    return out instanceof Response ? out : json(out);
  } catch (e) {
    if (e instanceof ZodError)
      return json({ error: "קלט לא תקין", issues: e.issues.map((i) => `${i.path.join(".")}: ${i.message}`) }, { status: 400 });
    if (e instanceof LinkConflictError) return json({ error: e.message, conflicts: e.conflicts }, { status: 409 });
    if (e instanceof LinkValidationError) return json({ error: e.message, recordIds: e.recordIds }, { status: 422 });
    if (e instanceof ImportValidationError) return json({ error: e.message }, { status: 422 });
    if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === "P2025") return json({ error: "לא נמצא" }, { status: 404 });
    if (e instanceof Error && /^(שעת|שעות|תאריך)/.test(e.message)) return json({ error: e.message }, { status: 400 });
    console.error(e);
    return json({ error: "שגיאה פנימית" }, { status: 500 });
  }
}

export async function readUpload(req: Request): Promise<{ text: string; fileName: string; includePossibleDuplicates: boolean }> {
  const form = await req.formData();
  const file = form.get("file");
  if (!(file instanceof File)) throw new ImportValidationError("לא צורף קובץ CSV");
  if (file.size > 5 * 1024 * 1024) throw new ImportValidationError("הקובץ גדול מ-5MB");
  return { text: await file.text(), fileName: file.name, includePossibleDuplicates: form.get("includePossibleDuplicates") === "true" };
}
