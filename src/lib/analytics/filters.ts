import { z } from "zod";
import type { Prisma } from "@prisma/client";
import { RECORD_TYPES, type RecordType } from "@/lib/types";

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const hour = z.coerce.number().int().min(0).max(23);

export const filtersSchema = z.object({
  from: isoDate.optional(),
  to: isoDate.optional(),
  hourFrom: hour.optional(),
  hourTo: hour.optional(),
  weekdays: z.array(z.number().int().min(0).max(6)).optional(),
  types: z.array(z.enum(RECORD_TYPES)).optional(),
  merchant: z.string().trim().min(1).optional(),
  report: z.string().trim().min(1).optional(),
  bundle: z.enum(["single", "bundle"]).optional(),
  q: z.string().trim().min(1).optional(),
});
export type Filters = z.infer<typeof filtersSchema>;

type ParamSource = URLSearchParams | Record<string, string | string[] | undefined>;

function read(src: ParamSource, key: string): string | undefined {
  if (src instanceof URLSearchParams) return src.get(key) ?? undefined;
  const v = src[key];
  return Array.isArray(v) ? v[0] : v;
}

/** Lenient: invalid or empty values are dropped rather than failing the page. */
export function parseFilters(src: ParamSource): Filters {
  const raw: Record<string, unknown> = {};
  for (const k of ["from", "to", "hourFrom", "hourTo", "merchant", "report", "bundle", "q"]) {
    const v = read(src, k);
    if (v !== undefined && v !== "") raw[k] = v;
  }
  const wd = read(src, "weekdays");
  if (wd) raw.weekdays = wd.split(",").filter(Boolean).map(Number);
  const types = read(src, "types");
  if (types) raw.types = types.split(",").filter(Boolean);
  const out: Filters = {};
  const shape = filtersSchema.shape;
  for (const [k, v] of Object.entries(raw)) {
    const r = shape[k as keyof typeof shape].safeParse(v);
    if (r.success) (out as Record<string, unknown>)[k] = r.data;
  }
  return out;
}

export function filtersToSearchParams(f: Filters): URLSearchParams {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(f)) {
    if (v === undefined) continue;
    p.set(k, Array.isArray(v) ? v.join(",") : String(v));
  }
  return p;
}

const pad = (h: number) => String(h).padStart(2, "0");

/** Prisma where for the record filters. `typeOverride` replaces the type filter. */
export function buildRecordWhere(f: Filters, typeOverride?: RecordType[] | null): Prisma.DeliveryRecordWhereInput {
  const and: Prisma.DeliveryRecordWhereInput[] = [];
  if (f.from) and.push({ eventDate: { gte: f.from } });
  if (f.to) and.push({ eventDate: { lte: f.to } });
  if (f.hourFrom !== undefined || f.hourTo !== undefined) {
    const hf = f.hourFrom ?? 0;
    const ht = f.hourTo ?? 23;
    const gte = `${pad(hf)}:00:00`;
    const lte = `${pad(ht)}:59:59`;
    // hourFrom > hourTo means a window crossing midnight, e.g. 22 -> 01.
    and.push(hf <= ht ? { eventTime: { gte, lte } } : { OR: [{ eventTime: { gte } }, { eventTime: { lte } }] });
  }
  if (f.weekdays?.length) and.push({ weekday: { in: f.weekdays } });
  const types = typeOverride === undefined ? f.types : typeOverride;
  if (types?.length) and.push({ type: { in: types } });
  if (f.merchant) and.push({ merchantRaw: { contains: f.merchant } });
  if (f.report) and.push({ reportNumber: f.report });
  if (f.bundle) and.push({ isBundle: f.bundle === "bundle" });
  if (f.q)
    and.push({
      OR: [
        { merchantRaw: { contains: f.q } },
        { notes: { contains: f.q } },
        { sourceFileName: { contains: f.q } },
        { eventDate: { contains: f.q } },
      ],
    });
  return and.length ? { AND: and } : {};
}
