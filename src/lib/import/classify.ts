import type { RecordType } from "@/lib/types";

/**
 * Classification is rule-based on the notes/merchant text only — never on the
 * amount. Rules are editable (Settings key "classificationRules").
 */
export interface ClassificationRules {
  bonusPatterns: string[];
  adjustmentPatterns: string[];
}

export const DEFAULT_RULES: ClassificationRules = {
  bonusPatterns: ["Funnel boost", "WK\\d+\\s*D\\d+G\\d+", "\\bbonus\\b", "בונוס"],
  adjustmentPatterns: ["Restaurant Late"],
};

/** Placeholder the report uses for an empty cell. */
export function isPlaceholder(value: string): boolean {
  return /^[\s—–\-]*$/.test(value);
}

function matchesAny(text: string, patterns: string[]): boolean {
  return patterns.some((p) => {
    try {
      return new RegExp(p, "i").test(text);
    } catch {
      return text.toLowerCase().includes(p.toLowerCase());
    }
  });
}

export function classifyRow(
  row: { merchantRaw: string; notes: string; amountAgorot: number },
  rules: ClassificationRules = DEFAULT_RULES,
): RecordType {
  const text = `${row.notes} ${row.merchantRaw}`;
  if (matchesAny(text, rules.bonusPatterns)) return "BONUS";
  if (matchesAny(text, rules.adjustmentPatterns)) return "ADJUSTMENT";
  if (isPlaceholder(row.notes) && !isPlaceholder(row.merchantRaw) && row.amountAgorot > 0) return "DELIVERY";
  return "UNKNOWN";
}

/**
 * Merchant names in a bundle are separated by ", " (comma + whitespace).
 * A comma without a following space (e.g. "Rishon LeZion ,20 הנגיד") is part of an
 * address, so it is not treated as a separator; such rows are flagged for review.
 */
export function splitMerchants(merchantRaw: string): string[] {
  if (isPlaceholder(merchantRaw)) return [];
  return merchantRaw
    .split(/,\s+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export function isBundle(merchantRaw: string): boolean {
  return splitMerchants(merchantRaw).length > 1;
}

export function hasAmbiguousComma(merchantRaw: string): boolean {
  return /,(?!\s)/.test(merchantRaw);
}
