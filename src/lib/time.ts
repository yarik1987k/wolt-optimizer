export const TIME_ZONE = "Asia/Jerusalem";

/** "11.10.2026" -> "2026-10-11". Throws on invalid dates. */
export function parseReportDate(input: string): string {
  const m = /^(\d{1,2})[./](\d{1,2})[./](\d{4})$/.exec(input.trim());
  if (!m) throw new Error(`תאריך לא תקין (נדרש DD.MM.YYYY): "${input}"`);
  const [d, mo, y] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const dt = new Date(Date.UTC(y, mo - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) {
    throw new Error(`תאריך לא קיים: "${input}"`);
  }
  return `${y}-${String(mo).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

/** "9:05:07" -> "09:05:07". Throws on invalid times. */
export function parseReportTime(input: string): string {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(input.trim());
  if (!m) throw new Error(`שעה לא תקינה (נדרש HH:mm:ss): "${input}"`);
  const [h, mi, s] = [Number(m[1]), Number(m[2]), Number(m[3] ?? 0)];
  if (h > 23 || mi > 59 || s > 59) throw new Error(`שעה לא תקינה: "${input}"`);
  return [h, mi, s].map((n) => String(n).padStart(2, "0")).join(":");
}

/** Weekday (0=Sunday) of a calendar date. Calendar dates have no time zone. */
export function weekdayOf(isoDate: string): number {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
}

export function addDays(isoDate: string, days: number): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Adds minutes to a local wall-clock "YYYY-MM-DDTHH:mm[:ss]" string (calendar arithmetic). */
export function addMinutesLocal(local: string, minutes: number): string {
  const [date, time] = local.split("T");
  const [y, m, d] = date.split("-").map(Number);
  const [hh, mm, ss = 0] = time.split(":").map(Number);
  return new Date(Date.UTC(y, m - 1, d, hh, mm + minutes, ss)).toISOString().slice(0, 19);
}

function tzOffsetMs(utcMs: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(utcMs));
  const get = (t: string) => Number(parts.find((p) => p.type === t)!.value);
  const asUtc = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second"));
  return asUtc - utcMs;
}

/** Converts a wall-clock time in Asia/Jerusalem to a UTC epoch (DST aware). */
export function localToUtcMs(isoDate: string, hhmm: string, timeZone = TIME_ZONE): number {
  const [y, m, d] = isoDate.split("-").map(Number);
  const [hh, mm, ss = 0] = hhmm.split(":").map(Number);
  const naive = Date.UTC(y, m - 1, d, hh, mm, ss);
  let utc = naive - tzOffsetMs(naive, timeZone);
  utc = naive - tzOffsetMs(utc, timeZone);
  return utc;
}

export interface ShiftWindow {
  startedAt: string; // YYYY-MM-DDTHH:mm local
  endedAt: string;
  durationHours: number;
  crossesMidnight: boolean;
}

/**
 * duration_hours = (end - start) / 3600, where an end time earlier than the start
 * time means the shift ended on the next calendar day.
 */
export function computeShiftWindow(date: string, startTime: string, endTime: string): ShiftWindow {
  const re = /^([01]\d|2[0-3]):[0-5]\d$/;
  if (!re.test(startTime) || !re.test(endTime)) throw new Error("שעות משמרת חייבות להיות בפורמט HH:mm");
  if (startTime === endTime) throw new Error("שעת סיום זהה לשעת התחלה — משך המשמרת אפס");
  const crossesMidnight = endTime < startTime;
  const endDate = crossesMidnight ? addDays(date, 1) : date;
  const ms = localToUtcMs(endDate, endTime) - localToUtcMs(date, startTime);
  return {
    startedAt: `${date}T${startTime}`,
    endedAt: `${endDate}T${endTime}`,
    durationHours: ms / 3_600_000,
    crossesMidnight,
  };
}

export function formatDateHe(isoDate: string): string {
  const [y, m, d] = isoDate.split("-");
  return `${d}.${m}.${y}`;
}
