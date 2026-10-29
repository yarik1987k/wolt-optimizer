/**
 * Money is stored as integer agorot. Parsing works on the decimal string directly
 * so no binary floating point is involved.
 */
export function parseAmountToAgorot(input: string): number {
  let s = input.trim().replace(/[₪\s‏‎]/g, "");
  if (!s) throw new Error("סכום ריק");
  let negative = false;
  if (/^\(.*\)$/.test(s)) {
    negative = true;
    s = s.slice(1, -1);
  }
  if (s.startsWith("-") || s.startsWith("−")) {
    negative = !negative;
    s = s.slice(1);
  }
  const hasComma = s.includes(",");
  const hasDot = s.includes(".");
  if (hasComma && hasDot) {
    // Whichever separator comes last is the decimal separator.
    if (s.lastIndexOf(",") > s.lastIndexOf(".")) s = s.replace(/\./g, "").replace(",", ".");
    else s = s.replace(/,/g, "");
  } else if (hasComma) {
    // "71,42" = decimal comma; "1,203" = thousands separator.
    s = /^\d+,\d{1,2}$/.test(s) ? s.replace(",", ".") : s.replace(/,/g, "");
  }
  const m = /^(\d+)(?:\.(\d*))?$/.exec(s);
  if (!m) throw new Error(`סכום לא תקין: "${input}"`);
  const frac = m[2] ?? "";
  if (frac.length > 2 && /[1-9]/.test(frac.slice(2))) {
    throw new Error(`סכום עם יותר משתי ספרות אחרי הנקודה: "${input}"`);
  }
  const agorot = Number(m[1]) * 100 + Number((frac + "00").slice(0, 2));
  return negative ? -agorot : agorot;
}

const shekelFormatter = new Intl.NumberFormat("he-IL", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Formats agorot as "₪1,518.36". Uses integer math for the rounding of averages. */
export function formatShekel(agorot: number | null | undefined): string {
  if (agorot === null || agorot === undefined || Number.isNaN(agorot)) return "אין נתונים";
  return `₪${shekelFormatter.format(agorot / 100)}`;
}

/** Shekels with two decimals as a plain string, e.g. 3703.317 agorot -> "37.03". */
export function agorotToShekelString(agorot: number): string {
  return (Math.round(agorot) / 100).toFixed(2);
}
